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

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.hosted.FeatureHandler;
import com.oracle.svm.hosted.HostedConfiguration;
import com.oracle.svm.hosted.NativeImageGenerator;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.loop.phases.LoopPartialUnrollPhase;
import jdk.graal.compiler.loop.phases.LoopPeelingPhase;
import jdk.graal.compiler.loop.phases.LoopUnswitchingPhase;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.vector.phases.LoopVectorizationPhase;

/**
 * Gives the methods the run spent its time in the inliner settings of the highest optimization
 * level, whatever level the image is built at.
 * <p>
 * Below {@code -O3} the compile queue narrows the inliner's search, turns off its escape
 * analysis, and takes partial unrolling and loop vectorization out of the suites, for every
 * method alike, to keep build time and image size down. That is a fair trade
 * over thirteen thousand methods. It is a poor one for the hundred the program lives in, and a
 * profile says which those are.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class CrucibleHostedConfiguration extends HostedConfiguration {

    /** Methods compiled with the full inliner settings at an optimization level that reduces them. */
    public static final AtomicLong HOT_METHODS_AT_FULL_SETTINGS = new AtomicLong();
    /** Cold methods compiled with the loop optimizations that copy code taken out. */
    public static final AtomicLong COLD_METHODS_WITHOUT_LOOP_OPTIMIZATIONS = new AtomicLong();

    @Override
    public CompileQueue createCompileQueue(DebugContext debug, FeatureHandler featureHandler, HostedUniverse hostedUniverse, RuntimeConfiguration runtimeConfiguration, boolean deoptimizeAll) {
        return new CompileQueue(debug, featureHandler, hostedUniverse, runtimeConfiguration, deoptimizeAll, Collections.emptyList()) {
            private volatile Suites fullSuites;

            /**
             * Below the highest level the regular suites come without partial unrolling and loop
             * vectorization. A hot method is compiled with the suites that still have them.
             */
            private volatile Suites coldSuites;

            @Override
            protected Suites createSuitesForRegularCompile(StructuredGraph graph, Suites originalSuites) {
                if (!(graph.method() instanceof HostedMethod method)) {
                    return originalSuites;
                }
                if (CrucibleOptions.CrucibleColdCodeSize.getValue() && CruciblePolicyFactory.isCold(method)) {
                    COLD_METHODS_WITHOUT_LOOP_OPTIMIZATIONS.incrementAndGet();
                    return coldSuites(originalSuites);
                }
                if (SubstrateOptions.isMaximumOptimizationLevel() || !isHot(method)) {
                    return originalSuites;
                }
                Suites suites = fullSuites;
                if (suites == null) {
                    synchronized (this) {
                        suites = fullSuites;
                        if (suites == null) {
                            suites = NativeImageGenerator.createSuites(featureHandler, runtimeConfig, true);
                            fullSuites = suites;
                        }
                    }
                }
                return suites;
            }

            /**
             * Loop optimizations trade size for speed, and in a method the run never reached there
             * is no speed to be had: peeling, unswitching, unrolling and vectorization all copy the
             * loop they work on.
             */
            private Suites coldSuites(Suites originalSuites) {
                Suites suites = coldSuites;
                if (suites == null) {
                    synchronized (this) {
                        suites = coldSuites;
                        if (suites == null) {
                            suites = originalSuites.copy();
                            suites.getHighTier().removeSubTypePhases(LoopPeelingPhase.class);
                            suites.getHighTier().removeSubTypePhases(LoopUnswitchingPhase.class);
                            suites.getHighTier().removeSubTypePhases(CrucibleLoopRangeSplitPhase.class);
                            suites.getMidTier().removeSubTypePhases(CrucibleLoopRangeSplitPhase.class);
                            suites.getMidTier().removeSubTypePhases(LoopPartialUnrollPhase.class);
                            suites.getMidTier().removeSubTypePhases(LoopVectorizationPhase.class);
                            coldSuites = suites;
                        }
                    }
                }
                return suites;
            }

            @Override
            protected OptionValues getCustomizedOptions(HostedMethod method, DebugContext methodDebug) {
                if (!omitPriorityInliningTuning() && isHot(method)) {
                    HOT_METHODS_AT_FULL_SETTINGS.incrementAndGet();
                    return methodDebug.getOptions();
                }
                return super.getCustomizedOptions(method, methodDebug);
            }
        };
    }

    private static boolean isHot(HostedMethod method) {
        return PGOProfilesLookup.singletonOrNull() instanceof CrucibleProfilesLookup profiles && profiles.workShare(method) >= CrucibleOptions.CrucibleHotRootShare.getValue();
    }
}
