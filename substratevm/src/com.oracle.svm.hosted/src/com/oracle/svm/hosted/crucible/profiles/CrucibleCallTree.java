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
import com.oracle.svm.core.crucible.CrucibleOptions;
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

    /** How often the compiler asked where a call goes in some context, and how often the tree knew. */
    public static final java.util.concurrent.atomic.AtomicLong TARGET_LOOKUPS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TARGET_TOO_FEW = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TARGET_HITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TARGET_HITS_SINGLE = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CONTEXT_LOOKUPS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CONTEXT_HITS = new java.util.concurrent.atomic.AtomicLong();

    private final Map<AnalysisMethod, Node> roots = new HashMap<>();
    private final HostedUniverse universe;
    private long totalCount;
    private int resolved;
    private int unresolvedType;
    private int unresolvedTarget;
    private boolean sampled;
    private int sampledStacks;

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
        sampled = !profile.samples().isEmpty();
        if (sampled) {
            /*
             * Stacks say everything the receiver counters do about where calls go, in every calling
             * context rather than one, and in units of time rather than of calls. The two do not
             * add up, so where there are stacks the tree is built from them alone.
             */
            for (CrucibleProfile.Sample sample : profile.samples()) {
                add(sample, methodsById);
            }
            return;
        }
        for (CrucibleProfile.Method method : profile.methods()) {
            for (CrucibleProfile.VirtualInvoke invoke : method.virtualInvokes()) {
                add(invoke, typesByName, methodsById);
            }
        }
    }

    /** How far below a compilation root a calling context is followed. */
    private static final int MAX_DEPTH_BELOW_ROOT = 24;

    /**
     * Files a sampled stack under every method on it. Any of them may be what the compiler is
     * compiling when it asks, and what it then wants is what happened below that method: its
     * callees, theirs, and so on down, each in the context of the ones above it up to the root.
     */
    private void add(CrucibleProfile.Sample sample, Map<String, AnalysisMethod> methodsById) {
        List<String> stack = sample.stack();
        AnalysisMethod[] methods = new AnalysisMethod[stack.size()];
        int[] bcis = new int[stack.size()];
        for (int i = 0; i < methods.length; i++) {
            methods[i] = methodsById.get(methodIdOf(stack.get(i)));
            bcis[i] = bciOf(stack.get(i));
            if (methods[i] == null) {
                unresolvedTarget++;
            }
        }
        totalCount += sample.count();
        sampledStacks++;
        stacks.add(new Stack(methods, bcis, sample.count()));
        for (int start = 0; start < methods.length; start++) {
            if (methods[start] == null) {
                continue;
            }
            Node node = roots.computeIfAbsent(methods[start], Node::new);
            for (int i = start; i + 1 < methods.length && i - start < MAX_DEPTH_BELOW_ROOT && methods[i + 1] != null; i++) {
                node = node.childFor(bcis[i], methods[i + 1]);
                resolved++;
            }
            node.count += sample.count();
            if (node.parent == null) {
                node.selfCount += sample.count();
            }
        }
    }

    /** A sampled stack with its methods looked up, outermost first; {@code null} where one is not in the image. */
    private record Stack(AnalysisMethod[] methods, int[] bcis, long count) {
    }

    private final List<Stack> stacks = new ArrayList<>();

    /** How many calls down a copy is compared with the method it was copied from. */
    private static final int NARROWING_HORIZON = 8;

    /**
     * The tree below {@code reached} as it would be had the samples been filed under the whole
     * path to it, from the outermost method the compiler got there from.
     * <p>
     * The tree under a method pools every way the method was reached, which is right for compiling
     * it once. {@code reached} is that method on one path, but it sits in a tree that is cut off a
     * fixed number of calls below its root, and a hot path through shared code is a chain of
     * copies, each made from inside the one before and so ever nearer the cut. Filing the samples
     * again under the path gives the copy the full depth below itself.
     */
    public Node contextFor(Node reached) {
        List<Node> down = new ArrayList<>();
        Node top = reached;
        for (; top.parent != null; top = top.parent) {
            down.add(0, top);
        }
        int prefix = top.contextMethods == null ? 1 : top.contextMethods.length;
        AnalysisMethod[] methods = new AnalysisMethod[prefix + down.size()];
        int[] bcis = new int[methods.length - 1];
        if (top.contextMethods == null) {
            methods[0] = top.method;
        } else {
            System.arraycopy(top.contextMethods, 0, methods, 0, prefix);
            System.arraycopy(top.contextBcis, 0, bcis, 0, prefix - 1);
        }
        for (int i = 0; i < down.size(); i++) {
            methods[prefix + i] = down.get(i).method;
            bcis[prefix + i - 1] = down.get(i).bci;
        }
        Node context = new Node(reached.method);
        context.contextMethods = methods;
        context.contextBcis = bcis;
        for (Stack stack : stacks) {
            for (int start = 0; start + methods.length <= stack.methods.length; start++) {
                if (matches(stack, start, methods, bcis)) {
                    Node node = context;
                    int from = start + methods.length - 1;
                    for (int i = from; i + 1 < stack.methods.length && i - from < MAX_DEPTH_BELOW_ROOT && stack.methods[i + 1] != null; i++) {
                        node = node.childFor(stack.bcis[i], stack.methods[i + 1]);
                    }
                    node.count += stack.count;
                    if (node == context) {
                        node.selfCount += stack.count;
                    }
                }
            }
        }
        return context;
    }

    private static boolean matches(Stack stack, int start, AnalysisMethod[] methods, int[] bcis) {
        for (int i = 0; i < methods.length; i++) {
            if (!methods[i].equals(stack.methods[start + i]) || (i < bcis.length && bcis[i] != stack.bcis[start + i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether some call within reach of inlining goes to fewer places in {@code context} than it
     * does in the method compiled for everyone. Where none does the copy would come out the same.
     */
    public boolean narrows(Node context) {
        Node pooled = roots.get(context.method);
        return pooled != null && narrows(context, pooled, NARROWING_HORIZON);
    }

    private static boolean narrows(Node context, Node pooled, int horizon) {
        if (horizon == 0) {
            return false;
        }
        for (Map.Entry<Integer, List<Node>> calls : context.children.entrySet()) {
            List<Node> everywhere = pooled.children.get(calls.getKey());
            if (everywhere == null) {
                continue;
            }
            if (calls.getValue().size() < everywhere.size()) {
                return true;
            }
            for (Node callee : calls.getValue()) {
                Node same = Node.match(everywhere, callee.method);
                if (same != null && narrows(callee, same, horizon - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the tree was built from sampled stacks rather than from receiver counters. */
    public boolean isSampled() {
        return sampled;
    }

    /** Share of the samples that landed in this method itself, or -1 if the tree knows nothing of it. */
    public double selfShare(HostedMethod method) {
        Node node = roots.get(method.wrapped);
        return node == null || totalCount == 0 ? -1 : (double) node.selfCount / totalCount;
    }

    /** Share of the samples that landed in this method or anything it called. */
    public double inclusiveShare(HostedMethod method) {
        Node node = roots.get(method.wrapped);
        return node == null || totalCount == 0 ? 0 : (double) node.subtreeCount() / totalCount;
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
        Node context = CrucibleContextClonePhase.contextOf(compilationRoot);
        return context != null ? context : roots.get(compilationRoot.wrapped);
    }

    public String summary() {
        return "Crucible: call tree" + (sampled ? " from " + sampledStacks + " sampled stacks" : "") + " has " + roots.size() + " roots and " + resolved + " resolved call edges (" +
                        totalCount + " observations); " + unresolvedType + " receiver types and " +
                        unresolvedTarget + " call targets could not be resolved.";
    }

    /** One method in the tree. Children are the methods its calls reached, grouped by call bci. */
    public final class Node implements PrefixTree.Cursor {

        private final AnalysisMethod method;
        private final Node parent;
        /** Where in the parent the call to this method is; -1 for a root. */
        private final int bci;
        private final Map<Integer, List<Node>> children = new HashMap<>();
        /** Filled in on first use. The tree does not change once the compiler starts asking. */
        private long subtreeTotal = -1;
        /** For the root of a tree made by {@link #contextFor}: the path it was made for, ending in this method. */
        private AnalysisMethod[] contextMethods;
        private int[] contextBcis;
        private long count;
        /** Samples that ended in a root method itself; a root's own count also holds truncated chains. */
        private long selfCount;

        Node(AnalysisMethod method) {
            this(method, null, -1);
        }

        Node(AnalysisMethod method, Node parent, int bci) {
            this.method = method;
            this.parent = parent;
            this.bci = bci;
        }

        /** Share of all samples that were in this method, or below it, when reached this way. */
        public double share() {
            return totalCount == 0 ? 0 : (double) subtreeCount() / totalCount;
        }

        /** Whether the method is already one of its own callers on this path. */
        public boolean isRecursive() {
            Node top = this;
            for (Node node = parent; node != null; node = node.parent) {
                if (node.method.equals(method)) {
                    return true;
                }
                top = node;
            }
            if (top.contextMethods != null) {
                /* The last of them is the top itself, which the loop above has looked at unless it is this. */
                for (int i = 0; i < top.contextMethods.length - (top == this ? 1 : 0); i++) {
                    if (top.contextMethods[i].equals(method)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Names the path a tree made by {@link #contextFor} was made for, the same from one build to the next. */
        public String pathName() {
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < contextMethods.length; i++) {
                path.append(contextMethods[i].getQualifiedName()).append('@').append(i < contextBcis.length ? contextBcis[i] : -1).append('>');
            }
            return path.toString();
        }

        Node childFor(int bci, AnalysisMethod callee) {
            List<Node> atBci = children.computeIfAbsent(bci, _ -> new ArrayList<>());
            for (Node child : atBci) {
                if (child.method.equals(callee)) {
                    return child;
                }
            }
            Node child = new Node(callee, this, bci);
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

        static Node match(List<Node> nodes, ResolvedJavaMethod target) {
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
            TARGET_LOOKUPS.incrementAndGet();
            List<Node> candidates = find(position);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (sampled) {
                /*
                 * A handful of samples says that time was spent here and little about where else
                 * the call goes. Two receivers seen three times and once may be two of five, and
                 * the counted receivers, which the compiler turns to next, know that.
                 */
                long seen = 0;
                for (Node candidate : candidates) {
                    seen += candidate.subtreeCount();
                }
                if (seen < CrucibleOptions.CrucibleMinimumSamplesAtCall.getValue()) {
                    TARGET_TOO_FEW.incrementAndGet();
                    return null;
                }
            }
            TARGET_HITS.incrementAndGet();
            if (candidates.size() == 1) {
                TARGET_HITS_SINGLE.incrementAndGet();
            }
            Map<HostedMethod, Long> occurrences = new HashMap<>();
            for (Node candidate : candidates) {
                occurrences.put(hostedUniverse.lookup(candidate.method), candidate.subtreeCount());
            }
            return PGOUtils.createJavaMethodProfile(occurrences);
        }

        @Override
        public PrefixTree.Cursor findForMethod(BytecodePosition position, ResolvedJavaMethod target) {
            CONTEXT_LOOKUPS.incrementAndGet();
            List<Node> nodes = find(position);
            Node found = nodes == null ? null : match(nodes, target);
            if (found != null) {
                CONTEXT_HITS.incrementAndGet();
            }
            return found;
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
            if (subtreeTotal >= 0) {
                return subtreeTotal;
            }
            long total = count;
            for (List<Node> atBci : children.values()) {
                for (Node child : atBci) {
                    total += child.subtreeCount();
                }
            }
            subtreeTotal = total;
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
