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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.common.meta.MethodVariant;
import com.oracle.svm.common.meta.MethodVariant.MethodVariantKey;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.code.CompilationGraph;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Compiles a method again for a caller it spends time under.
 * <p>
 * A method compiled once has to serve everyone who calls it. The loop that drives a stream is the
 * plainest case: every pipeline in the program goes through the same few methods, so the calls
 * they make reach dozens of different lambdas and none of them can be inlined. Seen from one
 * caller the same calls reach one lambda each. Inlining is what normally gives a method its
 * caller's view, and it stops where the budget does, which in a chain of a dozen library frames
 * is well short of the loop.
 * <p>
 * So where a call is left standing after inlining, and the sampled stacks show the callee taking
 * a real share of the run when reached this way, the call is pointed at a copy of the callee. The
 * copy is compiled like any other method except that its place in the tree of sampled stacks is
 * the path that led to it and not the method on its own, and what it calls is resolved from
 * there. Calls left standing in the copy are treated the same way, so a chain of copies follows
 * a hot path down through code that is shared.
 */
public final class CrucibleContextClonePhase extends BasePhase<HighTierContext> {

    public static final AtomicLong CALLS_SEEN = new AtomicLong();
    public static final AtomicLong CALLS_IN_CONTEXT = new AtomicLong();
    public static final AtomicLong CALLS_REDIRECTED = new AtomicLong();
    /** Hot paths passed over because nothing the method calls goes to fewer places on them. */
    public static final AtomicLong SAME_AS_ORIGINAL = new AtomicLong();

    /** The copies made, each with the place in the tree it was made for. */
    private static final Map<HostedMethod, CrucibleCallTree.Node> CONTEXTS = new ConcurrentHashMap<>();
    private static final AtomicInteger COPIES = new AtomicInteger();

    /** Distinguishes the copies of one method. Equal for equal paths, so that a path gets one copy. */
    private record ContextKey(String path) implements MethodVariantKey {
        @Override
        public String toString() {
            return "C" + Integer.toHexString(path.hashCode());
        }
    }

    private final HostedUniverse universe;

    public CrucibleContextClonePhase(HostedUniverse universe) {
        this.universe = universe;
    }

    /** The place in the tree {@code method} was copied for, or {@code null} if it is not a copy. */
    static CrucibleCallTree.Node contextOf(HostedMethod method) {
        return CONTEXTS.get(method);
    }

    public static int copies() {
        return CONTEXTS.size();
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(jdk.graal.compiler.nodes.GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (!(graph.method() instanceof HostedMethod root) || root.isDeoptTarget()) {
            return;
        }
        CrucibleCallTree tree = CrucibleProfileFeature.callTree(universe);
        if (tree == null || !tree.isSampled()) {
            /* Counted receivers say how often a path was taken, not how long was spent down it. */
            return;
        }
        PrefixTree.Cursor cursor = tree.cursorFor(root);
        if (cursor == null) {
            return;
        }
        for (MethodCallTargetNode callTarget : graph.getNodes(MethodCallTargetNode.TYPE).snapshot()) {
            Invoke invoke = callTarget.invoke();
            if (invoke == null || !callTarget.invokeKind().isDirect() || !(callTarget.targetMethod() instanceof HostedMethod callee)) {
                continue;
            }
            CALLS_SEEN.incrementAndGet();
            NodeSourcePosition position = invoke.asNode().getNodeSourcePosition();
            if (position == null || !isWorthACopy(callee)) {
                continue;
            }
            if (!(cursor.findForMethod(position, callee) instanceof CrucibleCallTree.Node reached)) {
                continue;
            }
            CALLS_IN_CONTEXT.incrementAndGet();
            if (reached.share() < CrucibleOptions.CrucibleContextCloneMinimumShare.getValue() || reached.isRecursive()) {
                continue;
            }
            HostedMethod copy = copyFor(tree, callee, reached);
            if (copy != null) {
                callTarget.setTargetMethod(copy);
                CALLS_REDIRECTED.incrementAndGet();
            }
        }
    }

    /** Only what is compiled the ordinary way, from a graph that the copy can be compiled from too. */
    private static boolean isWorthACopy(HostedMethod callee) {
        if (callee.isDeoptTarget() || callee.isUninterruptible() || callee.isNative() || callee.compilationInfo.getCustomCompileFunction() != null) {
            return false;
        }
        return callee.compilationInfo.getCompilationGraph() != null;
    }

    private static HostedMethod copyFor(CrucibleCallTree tree, HostedMethod callee, CrucibleCallTree.Node reached) {
        HostedMethod original = callee.isOriginalMethod() ? callee : callee.getMethodVariant(MethodVariant.ORIGINAL_METHOD);
        if (original == null) {
            return null;
        }
        CrucibleCallTree.Node context = tree.contextFor(reached);
        ContextKey key = new ContextKey(context.pathName());
        HostedMethod existing = original.getMethodVariant(key);
        if (existing != null) {
            return CONTEXTS.containsKey(existing) ? existing : null;
        }
        if (!tree.narrows(context)) {
            SAME_AS_ORIGINAL.incrementAndGet();
            return null;
        }
        if (COPIES.incrementAndGet() > CrucibleOptions.CrucibleContextCloneLimit.getValue()) {
            return null;
        }
        CompilationGraph compilationGraph = original.compilationInfo.getCompilationGraph();
        HostedMethod copy = original.getOrCreateMethodVariant(key);
        /* Before anyone can see the copy as a call target, which is when it is queued to be compiled. */
        if (copy.compilationInfo.getCompilationGraph() == null) {
            copy.compilationInfo.setCompilationGraph(compilationGraph);
        }
        CONTEXTS.putIfAbsent(copy, context);
        return copy;
    }
}
