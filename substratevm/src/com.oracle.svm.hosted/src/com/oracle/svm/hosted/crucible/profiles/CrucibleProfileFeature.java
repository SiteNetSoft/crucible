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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfile;
import com.oracle.svm.core.crucible.CrucibleProfileParser;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.UserError;
import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;

/**
 * Applies a profile recorded by an instrumented image. Registering {@link CrucibleProfilesLookup}
 * as the {@link PGOProfilesLookup} singleton is the whole of pass 2: upstream already consumes that
 * singleton from {@code SubstratePriorityInliningPhase} and {@code PGOApplyProfilesPhase}.
 */
@AutomaticallyRegisteredFeature
public final class CrucibleProfileFeature implements InternalFeature {

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return !CrucibleOptions.CrucibleProfile.getValue().isEmpty();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (CrucibleOptions.CrucibleInstrument.getValue()) {
            throw UserError.abort("-H:+CrucibleInstrument and -H:CrucibleProfile are mutually exclusive: an image either records a profile or consumes one.");
        }
        Path path = Path.of(CrucibleOptions.CrucibleProfile.getValue());
        if (!Files.isReadable(path)) {
            throw UserError.abort("Cannot read the profile given by -H:CrucibleProfile: %s", path);
        }
        CrucibleProfile profile;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            profile = CrucibleProfileParser.parse(reader);
        } catch (IOException e) {
            throw UserError.abort("Failed to read the profile %s: %s", path, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw UserError.abort("Malformed profile %s: %s", path, e.getMessage());
        }
        String recordedBase = profile.producer().graalBase();
        if (!CrucibleProfileRuntime.GRAAL_BASE.equals(recordedBase)) {
            /* A drifted profile still applies; the positions it misses are reported as misses. */
            System.err.println("Warning: profile " + path + " was recorded against " + recordedBase + ", this toolchain is " + CrucibleProfileRuntime.GRAAL_BASE + ".");
        }
        ImageSingletons.add(PGOProfilesLookup.class, new CrucibleProfilesLookup(profile));
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (!hosted || fallback) {
            return;
        }
        PGOProfilesLookup lookup = PGOProfilesLookup.singletonOrNull();
        if (lookup == null || !(providers.getMetaAccess() instanceof UniverseMetaAccess metaAccess) || !(metaAccess.getUniverse() instanceof HostedUniverse universe)) {
            return;
        }
        if (lookup instanceof CrucibleProfilesLookup crucible) {
            crucible.indexTypes(universe);
        }
        /* Before inlining, so that a root method sees its own recorded probabilities. */
        suites.getHighTier().prependPhase(new CrucibleApplyProfilesPhase(universe, lookup));
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        PGOProfilesLookup lookup = PGOProfilesLookup.singletonOrNull();
        if (lookup instanceof CrucibleProfilesLookup crucible) {
            System.out.println(crucible.applicationSummary());
            if (CrucibleOptions.CrucibleProfileDiagnostics.getValue()) {
                System.out.println(crucible.diagnostics());
            }
            if (!CrucibleOptions.CrucibleProfileTrace.getValue().isEmpty()) {
                System.out.println(crucible.tracedLookups());
            }
        }
    }
}
