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

import java.util.function.Function;

import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.SubstrateInliningProvider;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.PolicyFactory;

import com.oracle.svm.core.crucible.CrucibleOptions;

/**
 * Supplies the priority inliner with calling contexts from a CrucibleVM profile.
 * <p>
 * The community edition builds this provider with a context function of {@code _ -> null}, so
 * {@code samplingMethodProfiles} returns before reading any profile and no call is ever
 * devirtualised. This subclass provides real cursors and turns on profile application during
 * expansion.
 */
public final class CrucibleInliningProvider extends SubstrateInliningProvider {

    private final HostedUniverse hostedUniverse;

    public CrucibleInliningProvider(HostedUniverse universe, Function<HostedMethod, PrefixTree.Cursor> methodContextProvider) {
        super(universe, methodContextProvider);
        this.hostedUniverse = universe;
    }

    /**
     * The inherited version looks the callee up in a tree of sampled calling contexts, which is
     * built from a sampling profile and which a counter-based profile does not have. The phase
     * itself gets by without one, as it must for recursive calls, and then applies the profiles
     * recorded for the inlining context alone.
     */
    @Override
    public PGOApplyProfilesPhase createPGOApplyProfilesPhase(ResolvedJavaMethod compilationRoot, NodeSourcePosition nodeSourcePosition, ResolvedJavaMethod callee,
                    NodeSourcePosition methodContext) {
        return PGOApplyProfilesPhase.createForExpandingHotCutoffs(methodContext, hostedUniverse, null, PGOProfilesLookup.singleton());
    }

    @Override
    protected boolean shouldApplyProfilesWhileExpanding(OptionValues options) {
        return true;
    }

    /**
     * An image that records a profile keeps its polymorphic calls as calls. Left alone the inliner
     * turns a call with a few possible receivers into a chain of type tests with the bodies
     * inlined, and then there is no call left at which to see which receiver actually turns up,
     * which for a call that is hot is the one thing the profile is wanted for.
     */
    @Override
    public int getMaxPolymorphicDispatches(OptionValues options) {
        return CrucibleOptions.CrucibleInstrument.getValue() ? 0 : super.getMaxPolymorphicDispatches(options);
    }

    /**
     * One receiver in ten is the least the inliner will give a type test and an inlined body,
     * which is caution about a list of receivers that static analysis could only guess the
     * weights of. Counted receivers deserve more trust: a receiver seen three times in a hundred
     * is left as an indirect call otherwise, and an indirect call in a loop costs every
     * iteration, because everything live has to survive it.
     */
    @Override
    public double getMinPolymorphicDispatchProbability(OptionValues options) {
        if (CrucibleOptions.CrucibleInstrument.getValue() || PGOProfilesLookup.singletonOrNull() == null) {
            return super.getMinPolymorphicDispatchProbability(options);
        }
        return Math.min(super.getMinPolymorphicDispatchProbability(options), CrucibleOptions.CrucibleMinimumReceiverShare.getValue());
    }

    @Override
    public PolicyFactory policy(OptionValues options) {
        if (CrucibleOptions.CrucibleColdCodeSize.getValue() && !CrucibleOptions.CrucibleInstrument.getValue()) {
            return new CruciblePolicyFactory();
        }
        return super.policy(options);
    }
}
