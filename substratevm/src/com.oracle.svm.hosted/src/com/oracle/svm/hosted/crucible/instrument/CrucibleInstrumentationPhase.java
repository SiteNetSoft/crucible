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
package com.oracle.svm.hosted.crucible.instrument;

import com.oracle.svm.core.UninterruptibleAnnotationUtils;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.pgo.ProfilingUtilities;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Appended to the end of the high tier. Injects a counter increment after the start node (method
 * entry) and after every successor of every profiled control split.
 */
public final class CrucibleInstrumentationPhase extends BasePhase<HighTierContext> {

    private final CounterSlotAllocator allocator;

    public CrucibleInstrumentationPhase(CounterSlotAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null || SubstrateCompilationDirectives.isDeoptTarget(graph.method())) {
            return;
        }
        if (isExcluded(graph.method(), context)) {
            return;
        }
        String rootId = ProfileKey.methodId(graph.method());
        insertIncrement(graph, graph.start(), allocator.allocate(new ProfileKey.MethodEntry(rootId)));

        for (ControlSplitNode split : graph.getNodes().filter(ControlSplitNode.class).snapshot()) {
            NodeSourcePosition pos = split.getNodeSourcePosition();
            if (pos == null || !ProfilingUtilities.isNotForImplicitException(split)) {
                continue;
            }
            int index = 0;
            for (AbstractBeginNode successor : split.successors().filter(AbstractBeginNode.class).snapshot()) {
                insertIncrement(graph, successor, allocator.allocate(ProfileKey.forPosition(pos, index)));
                index++;
            }
        }
    }

    /**
     * The profiling runtime must never be instrumented: an entry counter injected into
     * {@link CrucibleProfileRuntime#increment} compiles to an unconditional self-call, which
     * recurses until the stack guard page is hit. Uninterruptible methods are excluded for the same
     * reason -- they are reachable from the increment path -- and because a counter bump there
     * would run outside the parts of the VM a foreign call may touch.
     */
    private static boolean isExcluded(ResolvedJavaMethod method, HighTierContext context) {
        if (method.getDeclaringClass().equals(context.getMetaAccess().lookupJavaType(CrucibleProfileRuntime.class))) {
            return true;
        }
        return UninterruptibleAnnotationUtils.isUninterruptible(method);
    }

    private static void insertIncrement(StructuredGraph graph, FixedWithNextNode after, int slot) {
        ForeignCallNode call = graph.add(new ForeignCallNode(CrucibleProfileRuntime.INCREMENT, ConstantNode.forInt(slot, graph)));
        graph.addAfterFixed(after, call);
    }
}
