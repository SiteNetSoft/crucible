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
package com.oracle.svm.hosted.crucible.instrument;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.graal.pointsto.meta.AnalysisMethod;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.CrucibleProfileWriter;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;

import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;

/** Wires the instrumentation pass into the image build when {@code -H:+CrucibleInstrument}. */
@AutomaticallyRegisteredFeature
public final class CrucibleInstrumentFeature implements InternalFeature {

    private final CounterSlotAllocator allocator = new CounterSlotAllocator();

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CrucibleOptions.CrucibleInstrument.getValue();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(CrucibleProfileRuntime.class, new CrucibleProfileRuntime());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        access.getBigBang().addRootMethod((AnalysisMethod) CrucibleProfileRuntime.INCREMENT.findMethod(access.getMetaAccess()), true,
                        "Counter increment foreign call, registered in " + CrucibleInstrumentFeature.class);
        RuntimeSupport.getRuntimeSupport().addTearDownHook(CrucibleProfileWriter.teardownHook());
    }

    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(CrucibleProfileRuntime.INCREMENT);
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (hosted && !fallback) {
            suites.getHighTier().appendPhase(new CrucibleInstrumentationPhase(allocator));
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        String[] keys = allocator.freeze();
        CrucibleProfileRuntime.singleton().install(new long[keys.length], keys, SubstrateOptions.ImageBuildID.getValue());
    }
}
