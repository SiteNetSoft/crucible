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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.svm.core.crucible.ProfileKey;

/**
 * Build-time registry mapping profile keys to dense counter slots. Safe for parallel compilation.
 * <p>
 * Keys are stored in the pooled encoding: the method ids they are mostly made of go into a shared
 * string pool and the key keeps only indices. Both arrays end up in the image heap, so this is what
 * keeps an instrumented image from being dominated by repeated copies of the same method names.
 */
public final class CounterSlotAllocator {

    private final ConcurrentHashMap<String, Integer> slots = new ConcurrentHashMap<>();
    private final List<String> keysBySlot = new ArrayList<>();
    private final MethodIdPool pool;
    private volatile boolean frozen;

    public CounterSlotAllocator() {
        this(new MethodIdPool());
    }

    /** Uses {@code sharedPool} so several allocators can index into one set of method ids. */
    public CounterSlotAllocator(MethodIdPool sharedPool) {
        this.pool = sharedPool;
    }

    public int allocate(ProfileKey key) {
        if (frozen) {
            throw new IllegalStateException("Counter slots are frozen; compilation is complete");
        }
        String encoded = key.encode(pool::intern);
        Integer existing = slots.get(encoded);
        if (existing != null) {
            return existing;
        }
        synchronized (keysBySlot) {
            return slots.computeIfAbsent(encoded, k -> {
                keysBySlot.add(k);
                return keysBySlot.size() - 1;
            });
        }
    }

    public int size() {
        synchronized (keysBySlot) {
            return keysBySlot.size();
        }
    }

    public String[] freeze() {
        frozen = true;
        synchronized (keysBySlot) {
            return keysBySlot.toArray(new String[0]);
        }
    }
}
