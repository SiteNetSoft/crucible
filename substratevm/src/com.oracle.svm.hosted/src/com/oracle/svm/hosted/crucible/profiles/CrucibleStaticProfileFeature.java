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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileParser;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.HostedConfiguration;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.util.LogUtils;

/**
 * Turns on predicted branch probabilities, {@link CrucibleStaticProfilePolicy}, for a build that
 * has no recording to take them from and does not make one.
 */
@AutomaticallyRegisteredFeature
public final class CrucibleStaticProfileFeature implements InternalFeature {

    /** Whether this build predicts the branches it has no recording for. */
    static boolean isEnabled() {
        return CrucibleOptions.CrucibleStaticProfile.getValue() && CrucibleOptions.CrucibleProfile.getValue().isEmpty() && !CrucibleOptions.CrucibleInstrument.getValue() &&
                        !SubstrateOptions.useEconomyCompilerConfig() && CrucibleBranchModel.isAvailable();
    }

    /** The profile of the class library that comes with the builder, next to this class. */
    private static final String LIBRARY_PROFILE = "library-profile.json.gz";

    /** The profile of the class library recorded in other programs, or null. */
    private static volatile CrucibleProfilesLookup adopted;

    static CrucibleProfilesLookup adoptedLibraryProfile() {
        return adopted;
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return (CrucibleOptions.CrucibleStaticProfile.getValue() || CrucibleOptions.CrucibleLibraryProfile.getValue() || !CrucibleOptions.CrucibleAdoptedProfile.getValue().isEmpty()) &&
                        CrucibleOptions.CrucibleProfile.getValue().isEmpty() &&
                        !CrucibleOptions.CrucibleInstrument.getValue();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        String file = CrucibleOptions.CrucibleAdoptedProfile.getValue();
        if (SubstrateOptions.useEconomyCompilerConfig()) {
            /* The economy configuration does nothing a branch probability could change. */
        } else if (!file.isEmpty()) {
            try (Reader reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
                adopted = new CrucibleProfilesLookup(CrucibleProfileParser.parse(reader));
            } catch (IOException | IllegalArgumentException e) {
                throw UserError.abort("Cannot read the profile of the class library given by -H:CrucibleAdoptedProfile: %s: %s", file, e.getMessage());
            }
        } else if (CrucibleOptions.CrucibleLibraryProfile.getValue()) {
            /* The one that comes with the builder, made by crucible/ml/adopt-profile.py. */
            try (InputStream in = CrucibleStaticProfileFeature.class.getResourceAsStream(LIBRARY_PROFILE)) {
                if (in == null) {
                    throw UserError.abort("-H:+CrucibleLibraryProfile: this builder has no %s.", LIBRARY_PROFILE);
                }
                try (Reader reader = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)) {
                    adopted = new CrucibleProfilesLookup(CrucibleProfileParser.parse(reader));
                }
            } catch (IOException | IllegalArgumentException e) {
                throw UserError.abort("Cannot read the profile of the class library that comes with the builder: %s", e.getMessage());
            }
        }
        if (CrucibleOptions.CrucibleStaticProfile.getValue() && !CrucibleBranchModel.isAvailable()) {
            LogUtils.warning("-H:+CrucibleStaticProfile has no model for the branch features of this build; branches keep their static estimates.");
        }
        if (adopted != null || isEnabled()) {
            /* The first hosted configuration registered wins; this one puts the policies in the queue. */
            HostedConfiguration.setInstanceIfEmpty(new CrucibleHostedConfiguration());
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        if (adopted != null) {
            System.out.println("Crucible: " + CrucibleAdoptedProfilePolicy.ADOPTED.get() + " branches of the class library given the probabilities recorded for them in other programs; " +
                            CrucibleAdoptedProfilePolicy.LOOP_EXITS_LEFT.get() + " that leave a loop left as they were.");
        }
        if (isEnabled()) {
            System.out.println("Crucible: " + CrucibleStaticProfilePolicy.PREDICTED.get() + " branch probabilities predicted, " + CrucibleStaticProfilePolicy.LEFT_ALONE.get() +
                            " branches left with what they were annotated with.");
        }
    }
}
