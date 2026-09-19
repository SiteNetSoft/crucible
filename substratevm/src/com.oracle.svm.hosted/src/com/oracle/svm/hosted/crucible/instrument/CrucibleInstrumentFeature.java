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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.CrucibleProfileWriter;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;

import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;

/** Wires the instrumentation pass into the image build when {@code -H:+CrucibleInstrument}. */
@AutomaticallyRegisteredFeature
public final class CrucibleInstrumentFeature implements InternalFeature {

    private final CounterSlotAllocator allocator = new CounterSlotAllocator();
    private final CounterSlotAllocator typeSiteAllocator = new CounterSlotAllocator();
    /**
     * Captured while phases are registered, which is the only hook that hands out something the
     * hosted universe can be reached from; afterCompilation needs it to name type ids.
     */
    private HostedUniverse universe;

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
        access.getBigBang().addRootMethod((AnalysisMethod) CrucibleProfileRuntime.RECORD_TYPE.findMethod(access.getMetaAccess()), true,
                        "Receiver-type sampling foreign call, registered in " + CrucibleInstrumentFeature.class);
        RuntimeSupport.getRuntimeSupport().addTearDownHook(CrucibleProfileWriter.teardownHook());
        RuntimeSupport.getRuntimeSupport().addStartupHook(CrucibleProfileWriter.periodicDumpHook());
    }

    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(CrucibleProfileRuntime.INCREMENT);
        foreignCalls.register(CrucibleProfileRuntime.RECORD_TYPE);
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (hosted && !fallback) {
            if (providers.getMetaAccess() instanceof UniverseMetaAccess metaAccess && metaAccess.getUniverse() instanceof HostedUniverse hUniverse) {
                universe = hUniverse;
            }
            suites.getHighTier().prependPhase(new CrucibleTypeSamplingPhase(typeSiteAllocator));
            suites.getHighTier().appendPhase(new CrucibleInstrumentationPhase(allocator));
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        String[] keys = allocator.freeze();
        CrucibleProfileRuntime runtime = CrucibleProfileRuntime.singleton();
        runtime.install(new long[keys.length], keys, SubstrateOptions.ImageBuildID.getValue());

        String[] typeKeys = typeSiteAllocator.freeze();
        int[] typeIds = new int[typeKeys.length * CrucibleProfileRuntime.TYPE_ROW_WIDTH];
        Arrays.fill(typeIds, CrucibleProfileRuntime.NO_TYPE);
        int[] idTable = typeIdTable();
        runtime.installTypeTables(typeIds, new long[typeIds.length], new long[typeKeys.length], typeKeys, idTable, typeNameTable());
        System.out.println("Crucible: instrumented " + keys.length + " counters and " + typeKeys.length +
                        " receiver-type sites; the image can name " + idTable.length + " types.");
        System.out.println("Crucible: saw " + CrucibleTypeSamplingPhase.CALL_TARGETS_SEEN.get() + " call targets, " +
                        CrucibleTypeSamplingPhase.CALL_TARGETS_INDIRECT.get() + " indirect, " +
                        CrucibleTypeSamplingPhase.SITES_INSTRUMENTED.get() + " sampled.");
    }

    /*
     * A type id only means something inside the image that produced it, so the image carries the
     * ids it can actually observe. Only instantiated types can ever be a receiver, which keeps the
     * table to the types that can appear rather than every type the universe knows.
     */
    private List<HostedType> observableTypes() {
        List<HostedType> types = new ArrayList<>();
        if (universe != null) {
            for (HostedType type : universe.getTypes()) {
                if (type.isInstantiated()) {
                    types.add(type);
                }
            }
            types.sort((a, b) -> Integer.compare(a.getTypeID(), b.getTypeID()));
        }
        return types;
    }

    private int[] typeIdTable() {
        List<HostedType> types = observableTypes();
        int[] ids = new int[types.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = types.get(i).getTypeID();
        }
        return ids;
    }

    private String[] typeNameTable() {
        List<HostedType> types = observableTypes();
        String[] names = new String[types.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = types.get(i).getName();
        }
        return names;
    }
}
