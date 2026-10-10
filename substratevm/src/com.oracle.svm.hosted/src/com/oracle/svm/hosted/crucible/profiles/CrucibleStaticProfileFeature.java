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

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
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

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CrucibleOptions.CrucibleStaticProfile.getValue() && CrucibleOptions.CrucibleProfile.getValue().isEmpty() && !CrucibleOptions.CrucibleInstrument.getValue();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (!CrucibleBranchModel.isAvailable()) {
            LogUtils.warning("-H:+CrucibleStaticProfile has no model for the branch features of this build; branches keep their static estimates.");
            return;
        }
        /* The first hosted configuration registered wins; this one puts the policy in the queue. */
        HostedConfiguration.setInstanceIfEmpty(new CrucibleHostedConfiguration());
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        if (isEnabled()) {
            System.out.println("Crucible: " + CrucibleStaticProfilePolicy.PREDICTED.get() + " branch probabilities predicted, " + CrucibleStaticProfilePolicy.LEFT_ALONE.get() +
                            " branches left with what they were annotated with.");
        }
    }
}
