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

import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Samples the receiver type of every indirect call, which is what upstream needs to build a
 * {@code JavaTypeProfile} and devirtualise a biased call site in pass 2.
 * <p>
 * Prepended to the high tier rather than appended, for two reasons. Call targets are lowered before
 * the tier ends, so a phase that runs last sees no {@link MethodCallTargetNode} at all. And
 * {@code CrucibleApplyProfilesPhase} reads profiles at the head of the high tier too, so sampling
 * here gives pass 2 the same calling contexts that pass 1 recorded.
 */
public final class CrucibleTypeSamplingPhase extends BasePhase<HighTierContext> {

    public static final AtomicLong CALL_TARGETS_SEEN = new AtomicLong();
    public static final AtomicLong CALL_TARGETS_INDIRECT = new AtomicLong();
    public static final AtomicLong SITES_INSTRUMENTED = new AtomicLong();

    private final CounterSlotAllocator typeSiteAllocator;

    public CrucibleTypeSamplingPhase(CounterSlotAllocator typeSiteAllocator) {
        this.typeSiteAllocator = typeSiteAllocator;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null || SubstrateCompilationDirectives.isDeoptTarget(graph.method())) {
            return;
        }
        /* Hosted compilation uses a MethodCallTargetNode subclass, so filter by class. */
        for (MethodCallTargetNode call : graph.getNodes().filter(MethodCallTargetNode.class).snapshot()) {
            CALL_TARGETS_SEEN.incrementAndGet();
            if (!call.invokeKind().isIndirect() || call.invoke() == null) {
                continue;
            }
            CALL_TARGETS_INDIRECT.incrementAndGet();
            NodeSourcePosition pos = call.getNodeSourcePosition();
            if (pos == null || call.arguments().isEmpty() || call.targetMethod() == null) {
                continue;
            }
            ValueNode receiver = call.arguments().get(0);
            int site = typeSiteAllocator.allocate(ProfileKey.virtualInvokeForPosition(pos, call.targetMethod()));
            ForeignCallNode record = graph.add(new ForeignCallNode(CrucibleProfileRuntime.RECORD_TYPE, ConstantNode.forInt(site, graph), receiver));
            graph.addBeforeFixed(call.invoke().asFixedNode(), record);
            SITES_INSTRUMENTED.incrementAndGet();
        }
    }
}
