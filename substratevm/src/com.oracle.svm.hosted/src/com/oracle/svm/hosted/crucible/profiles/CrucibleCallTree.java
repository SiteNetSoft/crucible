/*
 * Copyright (c) 2026, CrucibleVM contributors. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.crucible.profiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.crucible.CrucibleProfile;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.PGOUtils;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * A calling-context tree built from a CrucibleVM profile, served to the priority inliner as
 * {@link PrefixTree.Cursor}s.
 * <p>
 * Upstream builds the equivalent tree in {@code PrefixTree} from {@code getSampleCounts}, but
 * nothing in the community edition ever constructs one, so CrucibleVM supplies its own rather than
 * feeding a tree that is never built. Only the shape the inliner reads is reproduced: for a call
 * site, which methods the call reached and how often.
 * <p>
 * Each node is a method; its children are grouped by the bytecode index of the call that reached
 * them, which is what {@code profileFor} looks up.
 */
public final class CrucibleCallTree {

    private final Map<AnalysisMethod, Node> roots = new HashMap<>();
    private final HostedUniverse universe;
    private long totalCount;
    private int resolved;
    private int unresolvedType;
    private int unresolvedTarget;

    public CrucibleCallTree(CrucibleProfile profile, HostedUniverse universe) {
        this.universe = universe;
        Map<String, AnalysisType> typesByName = new HashMap<>();
        for (HostedType type : universe.getTypes()) {
            typesByName.putIfAbsent(type.getName(), type.getWrapped());
        }
        Map<String, AnalysisMethod> methodsById = new HashMap<>();
        for (HostedMethod method : universe.getMethods()) {
            methodsById.putIfAbsent(ProfileKey.methodId(method), method.wrapped);
        }
        for (CrucibleProfile.Method method : profile.methods()) {
            for (CrucibleProfile.VirtualInvoke invoke : method.virtualInvokes()) {
                add(invoke, typesByName, methodsById);
            }
        }
    }

    private void add(CrucibleProfile.VirtualInvoke invoke, Map<String, AnalysisType> typesByName, Map<String, AnalysisMethod> methodsById) {
        AnalysisMethod target = methodsById.get(invoke.target());
        if (target == null) {
            unresolvedTarget++;
            return;
        }
        /* Contexts are recorded innermost first; the tree is walked from the compilation root. */
        List<String> frames = new ArrayList<>(invoke.ctx());
        java.util.Collections.reverse(frames);
        if (frames.isEmpty()) {
            return;
        }
        AnalysisMethod rootMethod = methodsById.get(methodIdOf(frames.get(0)));
        if (rootMethod == null) {
            unresolvedTarget++;
            return;
        }
        Node node = roots.computeIfAbsent(rootMethod, Node::new);
        for (int i = 0; i < frames.size() - 1; i++) {
            AnalysisMethod next = methodsById.get(methodIdOf(frames.get(i + 1)));
            if (next == null) {
                unresolvedTarget++;
                return;
            }
            node = node.childFor(bciOf(frames.get(i)), next);
        }
        int callBci = bciOf(frames.get(frames.size() - 1));
        for (CrucibleProfile.ObservedType observed : invoke.types()) {
            AnalysisType receiver = typesByName.get(observed.name());
            if (receiver == null) {
                unresolvedType++;
                continue;
            }
            AnalysisMethod callee = receiver.resolveConcreteMethod(target, null);
            if (callee == null) {
                unresolvedType++;
                continue;
            }
            node.childFor(callBci, callee).count += observed.count();
            totalCount += observed.count();
            resolved++;
        }
    }

    private static String methodIdOf(String frame) {
        int separator = frame.lastIndexOf(':');
        return separator < 0 ? frame : frame.substring(0, separator);
    }

    private static int bciOf(String frame) {
        int separator = frame.lastIndexOf(':');
        return separator < 0 ? -1 : Integer.parseInt(frame.substring(separator + 1));
    }

    /** The cursor for a compilation root, or {@code null} when the profile says nothing about it. */
    public PrefixTree.Cursor cursorFor(HostedMethod compilationRoot) {
        return roots.get(compilationRoot.wrapped);
    }

    public String summary() {
        return "Crucible: call tree has " + roots.size() + " roots and " + resolved + " resolved call edges (" +
                        totalCount + " observations); " + unresolvedType + " receiver types and " +
                        unresolvedTarget + " call targets could not be resolved.";
    }

    /** One method in the tree. Children are the methods its calls reached, grouped by call bci. */
    public final class Node implements PrefixTree.Cursor {

        private final AnalysisMethod method;
        private final Node parent;
        private final Map<Integer, List<Node>> children = new HashMap<>();
        private long count;

        Node(AnalysisMethod method) {
            this(method, null);
        }

        Node(AnalysisMethod method, Node parent) {
            this.method = method;
            this.parent = parent;
        }

        Node childFor(int bci, AnalysisMethod callee) {
            List<Node> atBci = children.computeIfAbsent(bci, _ -> new ArrayList<>());
            for (Node child : atBci) {
                if (child.method.equals(callee)) {
                    return child;
                }
            }
            Node child = new Node(callee, this);
            atBci.add(child);
            return child;
        }

        private List<Node> childrenAt(int bci) {
            return children.get(bci);
        }

        /** Mirrors {@code PrefixTree.Node.find}: walk from the outermost frame inwards. */
        private List<Node> find(BytecodePosition position) {
            if (position == null) {
                return null;
            }
            if (position.getCaller() == null) {
                return childrenAt(position.getBCI());
            }
            List<Node> nodes = find(position.getCaller());
            if (nodes == null) {
                return null;
            }
            Node node = match(nodes, position.getMethod());
            return node == null ? null : node.childrenAt(position.getBCI());
        }

        private static Node match(List<Node> nodes, ResolvedJavaMethod target) {
            AnalysisMethod wanted = target instanceof HostedMethod hosted ? hosted.wrapped : (AnalysisMethod) target;
            for (Node node : nodes) {
                if (node.method.equals(wanted)) {
                    return node;
                }
            }
            return null;
        }

        @Override
        public AnalysisMethod getMethod() {
            return method;
        }

        @Override
        public JavaMethodProfile profileFor(HostedUniverse hostedUniverse, BytecodePosition position) {
            List<Node> candidates = find(position);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Map<HostedMethod, Long> occurrences = new HashMap<>();
            for (Node candidate : candidates) {
                occurrences.put(hostedUniverse.lookup(candidate.method), candidate.subtreeCount());
            }
            return PGOUtils.createJavaMethodProfile(occurrences);
        }

        @Override
        public PrefixTree.Cursor findForMethod(BytecodePosition position, ResolvedJavaMethod target) {
            List<Node> nodes = find(position);
            return nodes == null ? null : match(nodes, target);
        }

        @Override
        public PrefixTree.Cursor parent() {
            return parent;
        }

        @Override
        public boolean isHot(double hotContextRatioThreshold) {
            return totalCount > 0 && (double) subtreeCount() / totalCount >= hotContextRatioThreshold;
        }

        @Override
        public double ratio(NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
            List<Node> candidates = find(callPosition);
            if (candidates == null || candidates.isEmpty()) {
                return 0;
            }
            long total = 0;
            long forMethod = 0;
            for (Node candidate : candidates) {
                total += candidate.subtreeCount();
            }
            Node node = match(candidates, dispatchedMethod);
            if (node != null) {
                forMethod = node.subtreeCount();
            }
            return total == 0 ? 0 : (double) forMethod / total;
        }

        @Override
        public boolean safeToReuse() {
            return true;
        }

        @Override
        public long getSubtreeCount() {
            return subtreeCount();
        }

        private long subtreeCount() {
            long total = count;
            for (List<Node> atBci : children.values()) {
                for (Node child : atBci) {
                    total += child.subtreeCount();
                }
            }
            return total;
        }

        @Override
        public void updateState(HostedMethod callee, PrefixTree.State newState) {
            /* Inliner bookkeeping; the profile is read-only here. */
        }

        @Override
        public HostedMethod findCompilationRootParent(HostedMethod target) {
            for (Node node = this; node != null; node = node.parent) {
                if (node.parent == null) {
                    return universe.lookup(node.method);
                }
            }
            return null;
        }
    }
}
