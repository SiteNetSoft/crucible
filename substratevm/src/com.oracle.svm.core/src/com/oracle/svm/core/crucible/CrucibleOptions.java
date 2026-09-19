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
package com.oracle.svm.core.crucible;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;

/** Build-time and run-time options of the CrucibleVM profiling pipeline. */
public final class CrucibleOptions {

    @Option(help = "Instrument the image to collect an execution profile that is written at exit.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleInstrument = new HostedOptionKey<>(false) {
        @Override
        protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, Boolean oldValue, Boolean newValue) {
            if (newValue) {
                /* Profile keys are derived from node source positions, so they must be tracked. */
                GraalOptions.TrackNodeSourcePosition.update(values, true);
            }
        }
    };

    @Option(help = "Path of the profile written by an instrumented image at exit.", type = OptionType.User)//
    public static final RuntimeOptionKey<String> CrucibleProfileOutput = new RuntimeOptionKey<>("crucible-profile.json");

    @Option(help = "Path of a CrucibleVM profile to apply while building this image.", type = OptionType.User)//
    public static final HostedOptionKey<String> CrucibleProfile = new HostedOptionKey<>("") {
        @Override
        protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, String oldValue, String newValue) {
            if (newValue != null && !newValue.isEmpty()) {
                /* Profiles are keyed by node source position, so they must be tracked to apply. */
                GraalOptions.TrackNodeSourcePosition.update(values, true);
            }
        }
    };

    @Option(help = "Print sample context keys that failed to match when applying a CrucibleVM profile.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> CrucibleProfileDiagnostics = new HostedOptionKey<>(false);

    @Option(help = "Report every profile lookup whose context contains this substring, hit or miss.", type = OptionType.Debug)//
    public static final HostedOptionKey<String> CrucibleProfileTrace = new HostedOptionKey<>("");

    @Option(help = "Share of recorded calls a method needs before it counts as a hot caller, which is what lets the inliner devirtualise its call sites.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleHotCallerRatio = new HostedOptionKey<>(0.001);

    private CrucibleOptions() {
    }
}
