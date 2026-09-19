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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.StandaloneAddressBasedDevirtualization;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.LoweredCallTargetNode;
import jdk.graal.compiler.nodes.IndirectCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.Devirtualization;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.DevirtualizationUtil;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * Rewrites an indirect call whose receiver is strongly biased into a type-guarded direct call.
 * <p>
 * The community edition has the machinery for this and never reaches it: its own
 * {@code devirtualizeIndirectCallTargetInvokes} runs inside the priority inliner, near the head of
 * the high tier, and looks for {@link IndirectCallTargetNode}s that do not exist until lowering at
 * the end of the tier. Rather than widen the upstream patch to reorganise that, CrucibleVM drives
 * the same public devirtualisation utility from its own phase, appended after lowering, where the
 * nodes it needs are present.
 * <p>
 * A cascade is only worth emitting when one receiver dominates: each guard costs a comparison and a
 * branch, so a site spread evenly across its types would be made slower, not faster.
 */
public final class CrucibleDevirtualizationPhase extends BasePhase<HighTierContext> {

    public static final AtomicLong SITES_SEEN = new AtomicLong();
    public static final AtomicLong SITES_UNSUPPORTED = new AtomicLong();
    public static final AtomicLong SITES_PROFILED = new AtomicLong();
    public static final AtomicLong SITES_DEVIRTUALIZED = new AtomicLong();

    private final HostedUniverse universe;
    private final PGOProfilesLookup profiles;
    private final CrucibleInliningProvider inliningProvider;
    private final double minimumBias;
    private final int maximumTargets;

    public CrucibleDevirtualizationPhase(HostedUniverse universe, PGOProfilesLookup profiles, double minimumBias, int maximumTargets) {
        this.universe = universe;
        this.profiles = profiles;
        this.minimumBias = minimumBias;
        this.maximumTargets = maximumTargets;
        this.inliningProvider = new CrucibleInliningProvider(universe, _ -> null);
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null) {
            return;
        }
        for (IndirectCallTargetNode callTarget : graph.getNodes().filter(IndirectCallTargetNode.class).snapshot()) {
            if (!callTarget.isAlive()) {
                continue;
            }
            Invoke invoke = callTarget.invoke();
            if (invoke == null || invoke.stateAfter() == null) {
                /* The cascade copies the invoke's state; without one it cannot be built. */
                continue;
            }
            SITES_SEEN.incrementAndGet();
            if (!isSupported(callTarget)) {
                SITES_UNSUPPORTED.incrementAndGet();
                continue;
            }
            List<Devirtualization> cascade = cascadeFor(callTarget, invoke);
            if (cascade.isEmpty()) {
                continue;
            }
            SITES_DEVIRTUALIZED.incrementAndGet();
            DevirtualizationUtil.createDevirtualizationCascade(context.getProviders(), inliningProvider, invoke, cascade, false, true, SpeculationLog.NO_SPECULATION, null);
        }
    }

    /**
     * Only a dispatch whose target address is an ordinary word can be guarded.
     * <p>
     * {@code AddressBasedDevirtualization} compares the call target's computed address against a
     * method address cast to a word. Where the computed address instead carries a method-reference
     * pointer stamp -- a call through a method pointer rather than a vtable -- joining the two
     * stamps throws, and the comparison the guard needs cannot be built at all.
     */
    private static boolean isSupported(IndirectCallTargetNode callTarget) {
        if (!callTarget.invokeKind().isIndirect()) {
            return false;
        }
        return callTarget.computedAddress().stamp(NodeView.DEFAULT) instanceof IntegerStamp;
    }

    private List<Devirtualization> cascadeFor(LoweredCallTargetNode callTarget, Invoke invoke) {
        NodeSourcePosition position = invoke.asNode().getNodeSourcePosition();
        ResolvedJavaMethod target = callTarget.targetMethod();
        if (position == null || target == null) {
            return List.of();
        }
        Optional<Map<AnalysisType, Long>> observed = profiles.getVirtualInvokeProfile(position);
        if (observed.isEmpty()) {
            return List.of();
        }
        SITES_PROFILED.incrementAndGet();

        long total = 0;
        for (long count : observed.get().values()) {
            total += count;
        }
        if (total == 0) {
            return List.of();
        }

        List<Map.Entry<AnalysisType, Long>> byFrequency = new ArrayList<>(observed.get().entrySet());
        byFrequency.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Devirtualization> cascade = new ArrayList<>();
        long covered = 0;
        for (Map.Entry<AnalysisType, Long> entry : byFrequency) {
            if (cascade.size() >= maximumTargets) {
                break;
            }
            double share = (double) entry.getValue() / total;
            if (share < minimumBias) {
                break;
            }
            AnalysisMethod callee = entry.getKey().resolveConcreteMethod(target, null);
            if (callee == null) {
                continue;
            }
            HostedMethod dispatched = universe.lookup(callee);
            if (dispatched == null) {
                continue;
            }
            cascade.add(new StandaloneAddressBasedDevirtualization(invoke, dispatched, share));
            covered += entry.getValue();
        }
        if (cascade.isEmpty() || (double) covered / total < minimumBias) {
            return List.of();
        }
        return cascade;
    }
}
