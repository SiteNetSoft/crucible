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
import com.oracle.svm.hosted.phases.priorityinline.SubstrateInliningProvider;

import jdk.graal.compiler.options.OptionValues;

/**
 * Supplies the priority inliner with calling contexts from a CrucibleVM profile.
 * <p>
 * The community edition builds this provider with a context function of {@code _ -> null}, so
 * {@code samplingMethodProfiles} returns before reading any profile and no call is ever
 * devirtualised. This subclass provides real cursors and turns on profile application during
 * expansion.
 */
public final class CrucibleInliningProvider extends SubstrateInliningProvider {

    public CrucibleInliningProvider(HostedUniverse universe, Function<HostedMethod, PrefixTree.Cursor> methodContextProvider) {
        super(universe, methodContextProvider);
    }

    @Override
    protected boolean shouldApplyProfilesWhileExpanding(OptionValues options) {
        return true;
    }
}
