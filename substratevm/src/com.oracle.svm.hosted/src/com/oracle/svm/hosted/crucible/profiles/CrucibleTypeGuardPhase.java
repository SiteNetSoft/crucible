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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.Devirtualization;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.DevirtualizationUtil;
import jdk.graal.compiler.phases.tiers.HighTierContext;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * Turns a strongly biased virtual call into a type-guarded direct call, before inlining.
 * <p>
 * An earlier attempt did this after lowering and measured as a regression: the direct call it
 * produced could no longer be inlined, so the transformation only added a guard. Running ahead of
 * the inliner is the whole point — a direct call to a small method is one the inliner removes
 * entirely, which is where the benefit of devirtualising comes from.
 */
public final class CrucibleTypeGuardPhase extends BasePhase<HighTierContext> {

    public static final AtomicLong SITES_SEEN = new AtomicLong();
    public static final AtomicLong SITES_PROFILED = new AtomicLong();
    public static final AtomicLong SITES_GUARDED = new AtomicLong();
    public static final AtomicLong TARGETS_NOT_REACHABLE = new AtomicLong();

    private final HostedUniverse universe;
    private final PGOProfilesLookup profiles;
    private final CrucibleInliningProvider inliningProvider;
    private final double minimumBias;
    private final int maximumTargets;

    public CrucibleTypeGuardPhase(HostedUniverse universe, PGOProfilesLookup profiles, double minimumBias, int maximumTargets) {
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
        for (MethodCallTargetNode callTarget : graph.getNodes().filter(MethodCallTargetNode.class).snapshot()) {
            if (!callTarget.isAlive() || !callTarget.invokeKind().isIndirect()) {
                continue;
            }
            Invoke invoke = callTarget.invoke();
            if (invoke == null || invoke.stateAfter() == null || callTarget.arguments().isEmpty()) {
                continue;
            }
            SITES_SEEN.incrementAndGet();
            List<Devirtualization> cascade = cascadeFor(callTarget, invoke);
            if (cascade.isEmpty()) {
                continue;
            }
            SITES_GUARDED.incrementAndGet();
            DevirtualizationUtil.createDevirtualizationCascade(context.getProviders(), inliningProvider, invoke, cascade, false, true, SpeculationLog.NO_SPECULATION, null);
        }
    }

    /**
     * The call target of a hosted compilation is a {@link HostedMethod}; resolution against an
     * {@link AnalysisType} needs the analysis method it wraps.
     */
    private static AnalysisMethod analysisTarget(ResolvedJavaMethod target) {
        return target instanceof HostedMethod hosted ? hosted.wrapped : (AnalysisMethod) target;
    }

    /**
     * The implementations the analysis proved this call site can reach.
     * <p>
     * A closed-world image only contains code for methods the points-to analysis saw invoked, so a
     * guard may only name one of those. A profile can legitimately name others -- it observed a
     * receiver in a run of a differently built image -- and calling one aborts the build with
     * "reachable during compilation, but was not seen during Bytecode parsing".
     */
    private static Set<ResolvedJavaMethod> reachableImplementations(MethodCallTargetNode callTarget) {
        if (!(callTarget.targetMethod() instanceof HostedMethod hostedTarget)) {
            return Set.of();
        }
        Set<ResolvedJavaMethod> implementations = new HashSet<>(Arrays.asList(hostedTarget.getImplementations()));
        implementations.add(hostedTarget);
        return implementations;
    }

    private List<Devirtualization> cascadeFor(MethodCallTargetNode callTarget, Invoke invoke) {
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

        Set<ResolvedJavaMethod> reachable = reachableImplementations(callTarget);
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
            AnalysisMethod callee = entry.getKey().resolveConcreteMethod(analysisTarget(target), null);
            HostedType dispatchedType = universe.optionalLookup(entry.getKey());
            if (callee == null || dispatchedType == null) {
                continue;
            }
            HostedMethod dispatched = universe.optionalLookup(callee);
            if (dispatched == null) {
                continue;
            }
            if (!reachable.contains(dispatched) || !callee.isImplementationInvoked()) {
                /* Naming a method the analysis never saw invoked would abort the build. */
                TARGETS_NOT_REACHABLE.incrementAndGet();
                continue;
            }
            cascade.add(new CrucibleReceiverDevirtualization(dispatchedType, dispatched, share, position));
            covered += entry.getValue();
        }
        if (cascade.isEmpty() || (double) covered / total < minimumBias) {
            return List.of();
        }
        return cascade;
    }
}
