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

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.hub.DynamicHubIntrinsics;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.PartiallyLayerAware;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.Duplicable;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect;
import jdk.graal.compiler.nodes.NamedLocationIdentity;

/**
 * Run-time storage for instrumentation counters. The arrays are created at build time after all
 * compilations are done ({@code afterCompilation} runs before image-heap layout) and are therefore
 * part of the image heap.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, layeredInstallationKind = Duplicable.class, other = PartiallyLayerAware.class)
public final class CrucibleProfileRuntime {

    public static final String GRAAL_BASE = "vm-25.3.4.1";

    public static final LocationIdentity COUNTERS_LOCATION = NamedLocationIdentity.mutable("CrucibleCounters");

    public static final SubstrateForeignCallDescriptor INCREMENT = SnippetRuntime.findForeignCall(CrucibleProfileRuntime.class, "increment", CallSideEffect.NO_SIDE_EFFECT, COUNTERS_LOCATION);

    public static final SubstrateForeignCallDescriptor RECORD_TYPE = SnippetRuntime.findForeignCall(CrucibleProfileRuntime.class, "recordType", CallSideEffect.NO_SIDE_EFFECT, COUNTERS_LOCATION);

    /** Receiver types remembered per call site before the row overflows. */
    public static final int TYPE_ROW_WIDTH = 4;

    /** Marks an unused entry; 0 is a valid type id. */
    public static final int NO_TYPE = -1;

    /*
     * All three fields are only populated by install() in the feature's afterCompilation hook,
     * which runs after compilation. Without @UnknownObjectField the analysis folds a read through
     * the constant singleton to the value the field holds while compiling: counters.length folds
     * to 0, the bounds check in increment() is proven to always fail and the counter update is
     * deleted, and imageBuildId folds to the empty string.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private long[] counters = new long[0];
    @UnknownObjectField(availability = AfterCompilation.class) private String[] keys = new String[0];
    /** Method ids the pooled keys index into; see {@link ProfileKey#encode}. */
    @UnknownObjectField(availability = AfterCompilation.class) private String[] keyPool = new String[0];
    @UnknownObjectField(availability = AfterCompilation.class) private String imageBuildId = "";

    /* Receiver-type sampling: TYPE_ROW_WIDTH (typeId, count) pairs per site, flattened, one copy per stripe. */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] typeIds = new int[0];
    @UnknownObjectField(availability = AfterCompilation.class) private long[] typeCounts = new long[0];
    /** Times a site saw a receiver type that no longer fit in its row. */
    @UnknownObjectField(availability = AfterCompilation.class) private long[] typeOverflow = new long[0];
    /**
     * Order in which counters first fired, so the image can be laid out in the sequence a run
     * actually touches it. Zero means never; the first slot to fire gets 1.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] firstCallOrder = new int[0];
    private int firstCallClock;
    @UnknownObjectField(availability = AfterCompilation.class) private String[] typeKeys = new String[0];
    /** Type ids the image can observe, ascending, parallel to {@link #typeNames}. */
    @UnknownObjectField(availability = AfterCompilation.class) private int[] typeIdTable = new int[0];
    @UnknownObjectField(availability = AfterCompilation.class) private String[] typeNames = new String[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    public CrucibleProfileRuntime() {
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static CrucibleProfileRuntime singleton() {
        return ImageSingletons.lookup(CrucibleProfileRuntime.class);
    }

    public static boolean isPresent() {
        return ImageSingletons.contains(CrucibleProfileRuntime.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void install(long[] newCounters, String[] newKeys, String[] newKeyPool, String newImageBuildId) {
        assert newCounters.length == newKeys.length;
        this.firstCallOrder = new int[newCounters.length];
        this.counters = newCounters;
        this.keys = newKeys;
        this.keyPool = newKeyPool;
        this.imageBuildId = newImageBuildId;
    }

    /**
     * Installs the receiver-type sampling tables. {@code newTypeIds} must be filled with
     * {@link #NO_TYPE}, and {@code newIdTable} must be ascending so that lookups can bisect it.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void installTypeTables(int[] newTypeIds, long[] newTypeCounts, long[] newOverflow, String[] newTypeKeys, int[] newIdTable, String[] newTypeNames) {
        assert newTypeIds.length == newTypeKeys.length * TYPE_ROW_WIDTH * CrucibleBranchCounters.STRIPES;
        assert newIdTable.length == newTypeNames.length;
        this.typeIds = newTypeIds;
        this.typeCounts = newTypeCounts;
        this.typeOverflow = newOverflow;
        this.typeKeys = newTypeKeys;
        this.typeIdTable = newIdTable;
        this.typeNames = newTypeNames;
    }

    public int[] typeIds() {
        return typeIds;
    }

    public long[] typeCounts() {
        return typeCounts;
    }

    public long[] typeOverflow() {
        return typeOverflow;
    }

    public int[] firstCallOrder() {
        return firstCallOrder;
    }

    public String[] typeKeys() {
        return typeKeys;
    }

    /** Name of {@code typeId}, or {@code null} if the image does not know it. */
    public String typeName(int typeId) {
        int lo = 0;
        int hi = typeIdTable.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int id = typeIdTable[mid];
            if (id < typeId) {
                lo = mid + 1;
            } else if (id > typeId) {
                hi = mid - 1;
            } else {
                return typeNames[mid];
            }
        }
        return null;
    }

    public long[] counters() {
        return counters;
    }

    public String[] keys() {
        return keys;
    }

    public String[] keyPool() {
        return keyPool;
    }

    public String imageBuildId() {
        return imageBuildId;
    }

    /** Target of the foreign call injected by the instrumentation phase. Racy by design. */
    @Uninterruptible(reason = "Called from compiled code without a frame state; must not safepoint.")
    @SubstrateForeignCallTarget(fullyUninterruptible = true, stubCallingConvention = false)
    private static void increment(int slot) {
        CrucibleProfileRuntime runtime = singleton();
        long[] c = runtime.counters;
        if (slot >= 0 && slot < c.length) {
            if (c[slot] == 0) {
                runtime.noteFirstCall(slot);
            }
            c[slot]++;
        }
    }

    /**
     * Stamps a slot with its position in the order counters first fired. Racy like the counters:
     * two threads starting at once may take the same ordinal, which blurs the order slightly and
     * cannot corrupt it.
     */
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private void noteFirstCall(int slot) {
        int[] order = firstCallOrder;
        if (slot < order.length && order[slot] == 0) {
            order[slot] = ++firstCallClock;
        }
    }

    /**
     * Records the receiver's type at one call site. Scans the site's row for the type, claims a
     * free entry if the type is new, and counts an overflow when the row is full. Racy by design:
     * a lost update costs precision, not correctness.
     */
    @Uninterruptible(reason = "Called from compiled code without a frame state; must not safepoint.")
    @SubstrateForeignCallTarget(fullyUninterruptible = true, stubCallingConvention = false)
    private static void recordType(int site, Object receiver) {
        if (receiver == null) {
            return;
        }
        CrucibleProfileRuntime runtime = singleton();
        int[] ids = runtime.typeIds;
        long[] counts = runtime.typeCounts;
        int sites = runtime.typeKeys.length;
        if (site < 0 || site >= sites) {
            return;
        }
        /*
         * Each thread writes to one of several copies of the table, picked by its own address, for
         * the reason the counters are striped: every thread writing the same row at every virtual
         * call keeps that row's cache line travelling between cores. Stripes are laid out one after
         * another, not interleaved, so that two of them never share a line.
         */
        long thread = CurrentIsolate.getCurrentThread().rawValue();
        int stripe = (int) (((thread >>> 7) ^ (thread >>> 15)) & (CrucibleBranchCounters.STRIPES - 1));
        int base = (stripe * sites + site) * TYPE_ROW_WIDTH;
        if (base + TYPE_ROW_WIDTH > ids.length) {
            return;
        }
        int typeId = DynamicHubIntrinsics.readHub(receiver).getTypeID();
        for (int i = 0; i < TYPE_ROW_WIDTH; i++) {
            int seen = ids[base + i];
            if (seen == typeId) {
                counts[base + i]++;
                return;
            }
            if (seen == NO_TYPE) {
                ids[base + i] = typeId;
                counts[base + i]++;
                return;
            }
        }
        long[] overflow = runtime.typeOverflow;
        if (site < overflow.length) {
            overflow[site]++;
        }
    }
}
