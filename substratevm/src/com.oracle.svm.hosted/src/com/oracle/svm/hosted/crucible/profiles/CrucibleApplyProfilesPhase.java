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
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import java.util.concurrent.atomic.AtomicLong;

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
        double share = lookup.selfTimeShare(method);
        boolean hot = lookup.isHotCaller(method, CrucibleOptions.CrucibleHotCallerRatio.getValue());
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

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null) {
            return;
        }
        GRAPHS.incrementAndGet();
        installGlobalProfile(graph);
        PGOApplyProfilesPhase.createContextInsensitive(universe, profiles).apply(graph, context);
    }
}
