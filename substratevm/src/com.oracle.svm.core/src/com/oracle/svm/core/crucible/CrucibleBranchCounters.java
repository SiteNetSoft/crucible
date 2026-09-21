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

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.type.CLongPointer;

import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;

/**
 * The counters a recording image bumps inline, one 64-bit word per counter slot, in a block of the
 * image's data section.
 * <p>
 * A counter that is a call costs a call at every branch of the program, and a recording image ran
 * two to four times slower than Oracle's for it, fifteen times on a parallel benchmark. A block in
 * the data section can be addressed directly from code, so that a bump is one add to memory, and
 * unlike an array in the image heap its size can be left open until compilation has finished and
 * the number of counters is known.
 */
public final class CrucibleBranchCounters {

    /**
     * Copies of the block, one of which a thread picks by its own address. Threads bumping the same
     * word make its cache line travel between cores on every bump: a recording of a four-thread
     * benchmark ran slower on four cores than on one, eighteen times slower than the program
     * itself, where on one core it was less than twice. Must be a power of two.
     */
    public static final int STRIPES = 8;

    /** Bytes ahead of the first stripe: one word, holding the size of a stripe in bytes. */
    public static final int HEADER_BYTES = Long.BYTES;

    private static int slots;

    /**
     * How large a stripe is cannot be known while code is being compiled, since compiling is what
     * hands the counters out. So the block says it itself, in its first word, which is filled in
     * when the image is written; a bump reads it from there. That costs a load that is always in
     * cache, and in exchange the block is exactly as large as the program needs and there is no
     * limit to build against.
     */
    public static final CGlobalData<CLongPointer> BLOCK = CGlobalDataFactory.createBytes(CrucibleBranchCounters::initialContents);

    @Platforms(Platform.HOSTED_ONLY.class)
    private static byte[] initialContents() {
        long stripeBytes = (long) Math.max(1, slots) * Long.BYTES;
        byte[] contents = new byte[Math.toIntExact(HEADER_BYTES + STRIPES * stripeBytes)];
        java.nio.ByteBuffer.wrap(contents).order(java.nio.ByteOrder.nativeOrder()).putLong(0, stripeBytes);
        return contents;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void setSlots(int count) {
        slots = count;
    }

    private CrucibleBranchCounters() {
    }

    /** What slot {@code slot} was bumped to, over all stripes. Racy like the bumps themselves. */
    public static long read(int slot) {
        CLongPointer block = BLOCK.get();
        int stripeWords = (int) (block.read(0) / Long.BYTES);
        long sum = 0;
        for (int stripe = 0; stripe < STRIPES; stripe++) {
            sum += block.read(1 + stripe * stripeWords + slot);
        }
        return sum;
    }
}
