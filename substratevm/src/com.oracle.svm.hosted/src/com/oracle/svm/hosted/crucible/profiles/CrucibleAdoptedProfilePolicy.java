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
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;

import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Gives the branches of the class library, in a build without a recording, the probabilities
 * recorded for them in other programs.
 * <p>
 * Much of what a program runs is the library, and a branch of {@code HashMap.getNode} or of a
 * stream's pipeline goes much the same way whoever calls it. A profile of the library, added up
 * over the recordings of several programs by {@code crucible/ml/adopt-profile.py}, has those
 * probabilities as they were measured, where a model has to guess them back from what the branch
 * looks like.
 * <p>
 * It is looked up through a lookup of its own that is not the build's profile, so that nothing
 * else a profile changes, from which methods are compiled for size to which types keep a monitor
 * slot, takes the library's behaviour in other programs for this program's.
 */
public final class CrucibleAdoptedProfilePolicy extends CompileQueue.Policy {

    public static final AtomicLong ADOPTED = new AtomicLong();
    public static final AtomicLong LOOP_EXITS_LEFT = new AtomicLong();

    private final CrucibleProfilesLookup library;

    public CrucibleAdoptedProfilePolicy(HostedUniverse universe, CrucibleProfilesLookup library) {
        super(universe);
        this.library = library;
        library.indexTypes(universe);
    }

    @Override
    public void beforeEncode(HostedMethod method, StructuredGraph graph, HighTierContext context) {
        if (graph.getNodes(IfNode.TYPE).isEmpty()) {
            return;
        }
        /*
         * A loop nobody knows anything about is taken to go round a hundred times; how often the
         * same loop went round in other programs says less about this one than that does, and
         * mostly says fewer. The branches that leave a loop keep what they had.
         */
        List<IfNode> exits = new ArrayList<>();
        List<BranchProbabilityData> before = new ArrayList<>();
        int unknown = 0;
        for (IfNode branch : graph.getNodes(IfNode.TYPE)) {
            if (branch.getProfileData().getProfileSource().isUnknown()) {
                unknown++;
            }
            if (branch.trueSuccessor() instanceof LoopExitNode || branch.falseSuccessor() instanceof LoopExitNode) {
                exits.add(branch);
                before.add(branch.getProfileData());
            }
        }
        PGOApplyProfilesPhase.createContextInsensitive(universe, library).apply(graph, context);
        for (int i = 0; i < exits.size(); i++) {
            IfNode branch = exits.get(i);
            if (branch.isAlive() && !branch.getProfileData().equals(before.get(i))) {
                branch.setTrueSuccessorProbability(before.get(i));
                LOOP_EXITS_LEFT.incrementAndGet();
            }
        }
        int stillUnknown = 0;
        for (IfNode branch : graph.getNodes(IfNode.TYPE)) {
            if (branch.getProfileData().getProfileSource().isUnknown()) {
                stillUnknown++;
            }
        }
        ADOPTED.addAndGet(Math.max(0, unknown - stillUnknown));
    }
}
