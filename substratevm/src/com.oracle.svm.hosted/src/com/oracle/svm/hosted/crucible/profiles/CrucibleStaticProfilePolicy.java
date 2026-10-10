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

import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Gives the branches of a build without a recording the probabilities a model predicts for them.
 * <p>
 * Without a recording every branch nobody annotated is taken to go either way equally often, and
 * everything that weighs code by how often it runs, from the inliner's choice of calls to the
 * order blocks are laid out in, works from that. The model has seen, in recorded runs of other
 * programs, how branches that look a certain way went.
 * <p>
 * It runs as each graph leaves parsing, so that a method carries the predictions wherever it ends
 * up, compiled on its own or inlined into a caller.
 */
public final class CrucibleStaticProfilePolicy extends CompileQueue.Policy {

    /** The probability of a side is kept this far from never and from always. */
    private static final double MIN_PROBABILITY = 0.001;

    public static final AtomicLong PREDICTED = new AtomicLong();
    public static final AtomicLong LEFT_ALONE = new AtomicLong();

    public CrucibleStaticProfilePolicy(HostedUniverse universe) {
        super(universe);
    }

    @Override
    public void beforeEncode(HostedMethod method, StructuredGraph graph, HighTierContext context) {
        double[] graphFeatures = null;
        for (IfNode branch : graph.getNodes(IfNode.TYPE).snapshot()) {
            if (!branch.getProfileData().getProfileSource().isUnknown()) {
                /* Annotated, or known to lead to an exception: better than a prediction. */
                LEFT_ALONE.incrementAndGet();
                continue;
            }
            if (graphFeatures == null) {
                graphFeatures = CrucibleBranchFeatures.graphFeatures(graph);
            }
            double probability = CrucibleBranchModel.predict(CrucibleBranchFeatures.extract(branch, graphFeatures));
            if (Double.isNaN(probability)) {
                LEFT_ALONE.incrementAndGet();
                continue;
            }
            probability = Math.max(MIN_PROBABILITY, Math.min(1 - MIN_PROBABILITY, probability));
            branch.setTrueSuccessorProbability(BranchProbabilityData.inferred(probability));
            PREDICTED.incrementAndGet();
        }
    }
}
