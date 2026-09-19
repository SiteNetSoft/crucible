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

import java.util.ListIterator;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.hosted.HostedGraalConfiguration;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase;
import com.oracle.svm.shared.option.HostedOptionValues;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.common.BoxNodeIdentityPhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Installs the priority inliner with a {@link CrucibleInliningProvider} instead of the community
 * edition's context-free one.
 * <p>
 * This mirrors {@link HostedGraalConfiguration#createHostedInliners} exactly, differing only in the
 * provider handed to the phase. It is registered before the image generator installs its own
 * configuration, which is possible because features run {@code afterRegistration} first and
 * {@code setHostedInstanceIfEmpty} keeps whichever configuration arrives first.
 */
public final class CrucibleGraalConfiguration extends HostedGraalConfiguration {

    @Override
    public ListIterator<BasePhase<? super HighTierContext>> createHostedInliners(PhaseSuite<HighTierContext> highTier) {
        if (!SubstrateOptions.AOTPriorityInline.getValue()) {
            VMError.guarantee(highTier.findPhase(SubstratePriorityInliningPhase.class) == null, "Priority inlining is disabled but the phase is already installed");
            return null;
        }
        VMError.guarantee(runtimeConfiguration != null && hUniverse != null, "Hosted compiler configuration must be initialized before creating the priority inliner");
        highTier.removePhase(BoxNodeIdentityPhase.class);
        CanonicalizerPhase canonicalizer = CanonicalizerPhase.create();
        highTier.prependPhase(canonicalizer);
        var position = highTier.findPhase(CanonicalizerPhase.class);
        position.add(new BoxNodeIdentityPhase());
        position.add(new SubstratePriorityInliningPhase(canonicalizer, HostedOptionValues.singleton().get(), runtimeConfiguration, CompileQueue.getOptimisticOpts(), hUniverse, highTier,
                        new CrucibleInliningProvider(hUniverse, root -> CrucibleProfileFeature.cursorFor(hUniverse, root)), PGOProfilesLookup.singletonOrNull()));
        return position;
    }
}
