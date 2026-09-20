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

import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;

import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Applies profiles to each compiled graph before inlining reshapes it.
 * <p>
 * Upstream only applies profiles from inside {@code SubstratePriorityInliningPhase}, which reaches
 * a method while expanding a hot caller. A method compiled as its own root -- which is most of the
 * application -- is therefore never asked about, and its branch probabilities stay at their static
 * estimates. {@link PGOApplyProfilesPhase#createContextInsensitive} exists for exactly this job but
 * has no caller in the community edition, so CrucibleVM registers it here.
 * <p>
 * {@link PGOApplyProfilesPhase} extends {@code SingleRunSubphase} and cannot be reused across
 * graphs, hence the fresh instance per run.
 */
public final class CrucibleApplyProfilesPhase extends BasePhase<HighTierContext> {

    /** How far the hot-caller marking actually gets, since the inliner may use its own graphs. */
    public static final AtomicLong GRAPHS = new AtomicLong();
    public static final AtomicLong MARKED = new AtomicLong();
    public static final AtomicLong MARKED_HOT = new AtomicLong();
    /** Call targets that came out of the apply phase carrying a profile-derived type profile. */
    public static final AtomicLong DYNAMIC_TYPE_PROFILES = new AtomicLong();
    public static final AtomicLong INDIRECT_TARGETS = new AtomicLong();

    private final HostedUniverse universe;
    private final PGOProfilesLookup profiles;

    public CrucibleApplyProfilesPhase(HostedUniverse universe, PGOProfilesLookup profiles) {
        this.universe = universe;
        this.profiles = profiles;
    }

    /**
     * Tells the compiler how much of the recorded run happened in this method.
     * <p>
     * Every graph otherwise keeps {@code GlobalProfileProvider.DEFAULT}, whose {@code hotCaller}
     * is false, and nothing in the community edition ever calls
     * {@code StructuredGraph.setGlobalProfileProvider}. The priority inliner checks exactly that
     * flag before devirtualising, so without this the profile can never reach a call site.
     */
    private void installGlobalProfile(StructuredGraph graph) {
        if (!(graph.method() instanceof HostedMethod method) || !(profiles instanceof CrucibleProfilesLookup lookup)) {
            return;
        }
        MARKED.incrementAndGet();
        CrucibleCallTree tree = CrucibleProfileFeature.callTree(universe);
        if (tree != null && tree.isSampled()) {
            /*
             * With sampled stacks both answers are measured rather than inferred: the time spent
             * in the method itself, and whether it and what it calls took enough of the run for
             * its calls to be worth resolving context by context.
             */
            double self = Math.max(0, tree.selfShare(method));
            boolean hotRoot = tree.inclusiveShare(method) >= CrucibleOptions.CrucibleHotContextShare.getValue();
            if (hotRoot) {
                MARKED_HOT.incrementAndGet();
            }
            graph.setGlobalProfileProvider(new StructuredGraph.GlobalProfileProvider() {
                @Override
                public double getGlobalSelfTimePercent() {
                    return self;
                }

                @Override
                public boolean hotCaller() {
                    return hotRoot;
                }
            });
            return;
        }
        double share = lookup.selfTimeShare(method);
        /*
         * hotCaller gates exactly one thing upstream: applying receiver-type profiles to invokes,
         * which leads to devirtualisation. Measured as a 1.8% regression on a dispatch-heavy
         * workload, because the direct call it produces is not then inlined. Branch probabilities,
         * which are what this project is actually worth, are applied regardless of this flag.
         */
        boolean hot = CrucibleOptions.CrucibleMarkHotCallers.getValue() && lookup.isHotCaller(method, CrucibleOptions.CrucibleHotCallerRatio.getValue());
        if (hot) {
            MARKED_HOT.incrementAndGet();
        }
        graph.setGlobalProfileProvider(new StructuredGraph.GlobalProfileProvider() {
            @Override
            public double getGlobalSelfTimePercent() {
                return share;
            }

            @Override
            public boolean hotCaller() {
                return hot;
            }
        });
    }

    /**
     * Counts what the apply phase actually left behind, since a dynamic type profile on an
     * indirect call target is what makes the priority inliner build an inline cache and inline
     * through it.
     */
    private static void countDynamicProfiles(StructuredGraph graph) {
        for (MethodCallTargetNode callTarget : graph.getNodes().filter(MethodCallTargetNode.class)) {
            if (!callTarget.invokeKind().isIndirect()) {
                continue;
            }
            INDIRECT_TARGETS.incrementAndGet();
            if (callTarget instanceof SubstrateMethodCallTargetNode substrate && substrate.hasDynamicTypeProfile()) {
                DYNAMIC_TYPE_PROFILES.incrementAndGet();
            }
        }
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null) {
            return;
        }
        GRAPHS.incrementAndGet();
        installGlobalProfile(graph);
        PrefixTree.Cursor cursor = graph.globalProfileProvider().hotCaller() && graph.method() instanceof HostedMethod root ? CrucibleProfileFeature.cursorFor(universe, root) : null;
        if (cursor != null) {
            /* Also gives the root's own calls the targets the samples saw them reach. */
            PGOApplyProfilesPhase.createForBeforeHotCompilationPhase(universe, cursor, profiles).apply(graph, context);
        } else {
            PGOApplyProfilesPhase.createContextInsensitive(universe, profiles).apply(graph, context);
        }
        countDynamicProfiles(graph);
    }
}
