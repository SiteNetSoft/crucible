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

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.shared.Uninterruptible;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect;
import jdk.graal.compiler.nodes.NamedLocationIdentity;

/**
 * Run-time storage for instrumentation counters. The arrays are created at build time after all
 * compilations are done ({@code afterCompilation} runs before image-heap layout) and are therefore
 * part of the image heap.
 */
public final class CrucibleProfileRuntime {

    public static final String GRAAL_BASE = "vm-25.3.4.1";

    public static final LocationIdentity COUNTERS_LOCATION = NamedLocationIdentity.mutable("CrucibleCounters");

    public static final SubstrateForeignCallDescriptor INCREMENT = SnippetRuntime.findForeignCall(CrucibleProfileRuntime.class, "increment", CallSideEffect.HAS_SIDE_EFFECT, COUNTERS_LOCATION);

    private long[] counters = new long[0];
    private String[] keys = new String[0];
    private String imageBuildId = "";

    @Platforms(Platform.HOSTED_ONLY.class)
    public CrucibleProfileRuntime() {
    }

    @Uninterruptible(reason = "Called from the increment foreign call.", mayBeInlined = true)
    public static CrucibleProfileRuntime singleton() {
        return ImageSingletons.lookup(CrucibleProfileRuntime.class);
    }

    public static boolean isPresent() {
        return ImageSingletons.contains(CrucibleProfileRuntime.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void install(long[] newCounters, String[] newKeys, String newImageBuildId) {
        assert newCounters.length == newKeys.length;
        this.counters = newCounters;
        this.keys = newKeys;
        this.imageBuildId = newImageBuildId;
    }

    public long[] counters() {
        return counters;
    }

    public String[] keys() {
        return keys;
    }

    public String imageBuildId() {
        return imageBuildId;
    }

    /** Target of the foreign call injected by the instrumentation phase. Racy by design. */
    @Uninterruptible(reason = "Called from compiled code without a frame state; must not safepoint.")
    @SubstrateForeignCallTarget(fullyUninterruptible = true, stubCallingConvention = false)
    private static void increment(int slot) {
        long[] c = singleton().counters;
        if (slot >= 0 && slot < c.length) {
            c[slot]++;
        }
    }
}
