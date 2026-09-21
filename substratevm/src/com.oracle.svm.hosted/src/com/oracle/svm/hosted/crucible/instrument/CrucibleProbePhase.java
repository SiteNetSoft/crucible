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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.UninterruptibleAnnotationUtils;
import com.oracle.svm.core.graal.code.CGlobalDataInfo;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Turns the probes that came through inlining into counters, each under the calling context it
 * ended up in. See {@link CrucibleProbeNode}.
 */
public final class CrucibleProbePhase extends BasePhase<HighTierContext> {

    public static final AtomicLong RECEIVER_PROBES = new AtomicLong();
    public static final AtomicLong ENTRY_PROBES = new AtomicLong();
    public static final AtomicLong ENTRY_PROBES_INLINED = new AtomicLong();

    /** Methods whose own entry a probe has counted, so that the phase that used to does not count it again. */
    private static final Set<ResolvedJavaMethod> ENTRY_COUNTED = ConcurrentHashMap.newKeySet();

    private final CounterSlotAllocator allocator;
    private final CounterSlotAllocator typeSiteAllocator;
    private final CGlobalDataInfo branchCounters;

    public CrucibleProbePhase(CounterSlotAllocator allocator, CounterSlotAllocator typeSiteAllocator, CGlobalDataInfo branchCounters) {
        this.allocator = allocator;
        this.typeSiteAllocator = typeSiteAllocator;
        this.branchCounters = branchCounters;
    }

    static boolean entryCounted(ResolvedJavaMethod method) {
        return ENTRY_COUNTED.contains(method);
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        boolean count = graph.method() != null && !SubstrateCompilationDirectives.isDeoptTarget(graph.method()) && !UninterruptibleAnnotationUtils.isUninterruptible(graph.method());
        for (CrucibleProbeNode probe : graph.getNodes(CrucibleProbeNode.TYPE).snapshot()) {
            NodeSourcePosition position = probe.getNodeSourcePosition();
            if (count && position != null) {
                if (probe.kind() == CrucibleProbeNode.Kind.ENTRY) {
                    countEntry(graph, probe, position);
                } else {
                    boolean test = probe.kind() == CrucibleProbeNode.Kind.TESTED_VALUE;
                    int site = typeSiteAllocator.allocate(test ? ProfileKey.instanceOfForPosition(position) : ProfileKey.virtualInvokeForPosition(position, probe.target()));
                    graph.addBeforeFixed(probe, graph.add(new ForeignCallNode(CrucibleProfileRuntime.RECORD_TYPE, ConstantNode.forInt(site, graph), probe.receiver())));
                    RECEIVER_PROBES.incrementAndGet();
                }
            }
            graph.removeFixed(probe);
        }
    }

    private void countEntry(StructuredGraph graph, CrucibleProbeNode probe, NodeSourcePosition position) {
        boolean ownEntry = position.getCaller() == null;
        int slot = allocator.allocate(new ProfileKey.MethodEntry(ProfileKey.methodId(position.getMethod())));
        if (branchCounters != null && !(ownEntry && CrucibleOptions.CrucibleRecordStartupOrder.getValue())) {
            graph.addBeforeFixed(probe, graph.add(new CrucibleCounterNode(branchCounters, slot)));
        } else {
            /* The call also notes the order in which methods were first entered; see CrucibleInstrumentationPhase. */
            graph.addBeforeFixed(probe, graph.add(new ForeignCallNode(CrucibleProfileRuntime.INCREMENT, ConstantNode.forInt(slot, graph))));
        }
        if (ownEntry) {
            ENTRY_COUNTED.add(graph.method());
        } else {
            ENTRY_PROBES_INLINED.incrementAndGet();
        }
        ENTRY_PROBES.incrementAndGet();
    }
}
