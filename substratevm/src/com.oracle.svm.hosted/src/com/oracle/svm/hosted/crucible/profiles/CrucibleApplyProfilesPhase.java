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

import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
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

    private final HostedUniverse universe;
    private final PGOProfilesLookup profiles;

    public CrucibleApplyProfilesPhase(HostedUniverse universe, PGOProfilesLookup profiles) {
        this.universe = universe;
        this.profiles = profiles;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null) {
            return;
        }
        PGOApplyProfilesPhase.createContextInsensitive(universe, profiles).apply(graph, context);
    }
}
