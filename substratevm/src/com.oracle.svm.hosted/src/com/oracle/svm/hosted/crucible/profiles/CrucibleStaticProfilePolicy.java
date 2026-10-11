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

import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.ProfileData.ProfileSource;
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

    /**
     * A predicted probability is kept this far from never and from always. A recorded probability
     * of one in a million is a fact; a predicted one is a guess, and what the compiler does with a
     * side it takes to be that cold, down to not looking at the calls on it, is too much to stake
     * on a guess.
     */
    private final double minProbability = CrucibleOptions.CrucibleStaticProfileMinProbability.getValue();
    /** How far from even odds a prediction has to be for the branch to be given it. */
    private final double minimumBias = CrucibleOptions.CrucibleStaticProfileMinimumBias.getValue();
    /** Whether a prediction is marked as a profile to rely on. */
    private final boolean trusted = CrucibleOptions.CrucibleStaticProfileTrusted.getValue();
    /** Whether the branch that leaves a loop is predicted too. */
    private final boolean loopExits = CrucibleOptions.CrucibleStaticProfileLoopExits.getValue();
    /** How much of the distance from even odds a prediction keeps. */
    private final double confidence = CrucibleOptions.CrucibleStaticProfileConfidence.getValue();

    public static final AtomicLong PREDICTED = new AtomicLong();
    public static final AtomicLong LEFT_ALONE = new AtomicLong();

    public CrucibleStaticProfilePolicy(HostedUniverse universe) {
        super(universe);
    }

    @Override
    public void beforeEncode(HostedMethod method, StructuredGraph graph, HighTierContext context) {
        CrucibleBranchFeatures.GraphInfo info = null;
        for (IfNode branch : graph.getNodes(IfNode.TYPE).snapshot()) {
            if (!branch.getProfileData().getProfileSource().isUnknown()) {
                /* Annotated, or known to lead to an exception: better than a prediction. */
                LEFT_ALONE.incrementAndGet();
                continue;
            }
            if (!loopExits && (branch.trueSuccessor() instanceof LoopExitNode || branch.falseSuccessor() instanceof LoopExitNode)) {
                /*
                 * How often a loop goes round is worked out from the probability of leaving it, and
                 * a loop nobody knows anything about is taken to go round a hundred times. A
                 * predicted probability of leaving, even a low one, mostly says fewer.
                 */
                LEFT_ALONE.incrementAndGet();
                continue;
            }
            if (info == null) {
                info = CrucibleBranchFeatures.graphInfo(graph);
            }
            double probability = CrucibleBranchModel.predict(CrucibleBranchFeatures.extract(branch, info));
            if (Double.isNaN(probability) || Math.abs(probability - 0.5) < minimumBias) {
                /*
                 * A prediction near even odds says the model does not know, and a branch given it
                 * would still count as one with a profile, which is enough to change what the
                 * compiler does with the code around it.
                 */
                LEFT_ALONE.incrementAndGet();
                continue;
            }
            probability = 0.5 + confidence * (probability - 0.5);
            probability = Math.max(minProbability, Math.min(1 - minProbability, probability));
            /*
             * Not trusted, a prediction still weighs the blocks of the method, but what asks whether
             * a profile is to be relied on, as the loop policies do, goes on as without one.
             */
            branch.setTrueSuccessorProbability(trusted ? BranchProbabilityData.inferred(probability) : BranchProbabilityData.create(probability, ProfileSource.UNKNOWN));
            PREDICTED.incrementAndGet();
        }
    }
}
