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
package com.oracle.svm.crucible.test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.crucible.instrument.CounterSlotAllocator;
import com.oracle.svm.hosted.crucible.instrument.MethodIdPool;

public class CounterSlotAllocatorTest {

    @Test
    public void slotsAreDenseAndStable() {
        MethodIdPool pool = new MethodIdPool();
        CounterSlotAllocator a = new CounterSlotAllocator(pool);
        int s0 = a.allocate(new ProfileKey.MethodEntry("LA;.m()V"));
        int s1 = a.allocate(new ProfileKey.Conditional("LA;.m()V", List.of("LA;.m()V:3"), 3, 0, 7));
        Assert.assertEquals(0, s0);
        Assert.assertEquals(1, s1);
        Assert.assertEquals(s0, a.allocate(new ProfileKey.MethodEntry("LA;.m()V")));
        String[] keys = a.freeze();
        Assert.assertArrayEquals(new String[]{"M|0", "C|0|0:3|3|0|7"}, keys);
        Assert.assertArrayEquals(new String[]{"LA;.m()V"}, pool.freeze());
        Assert.assertEquals(new ProfileKey.MethodEntry("LA;.m()V"), ProfileKey.decode(keys[0], pool.freeze()));
        Assert.assertEquals(new ProfileKey.Conditional("LA;.m()V", List.of("LA;.m()V:3"), 3, 0, 7), ProfileKey.decode(keys[1], pool.freeze()));
    }

    @Test
    public void allocatorsSharingAPoolAgreeOnIndices() {
        MethodIdPool pool = new MethodIdPool();
        CounterSlotAllocator counters = new CounterSlotAllocator(pool);
        CounterSlotAllocator typeSites = new CounterSlotAllocator(pool);
        counters.allocate(new ProfileKey.MethodEntry("LA;.m()V"));
        typeSites.allocate(new ProfileKey.VirtualInvoke("LB;.n()V", List.of("LB;.n()V:1"), 1, "LA;.m()V"));
        String[] ids = pool.freeze();
        Assert.assertArrayEquals(new String[]{"LA;.m()V", "LB;.n()V"}, ids);
        Assert.assertEquals(new ProfileKey.VirtualInvoke("LB;.n()V", List.of("LB;.n()V:1"), 1, "LA;.m()V"),
                        ProfileKey.decode(typeSites.freeze()[0], ids));
    }

    @Test(expected = IllegalStateException.class)
    public void frozenAllocatorRejectsAllocation() {
        CounterSlotAllocator a = new CounterSlotAllocator();
        a.freeze();
        a.allocate(new ProfileKey.MethodEntry("LA;.m()V"));
    }

    @Test
    public void concurrentAllocationYieldsUniqueSlots() throws Exception {
        CounterSlotAllocator a = new CounterSlotAllocator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<int[]>> futures = IntStream.range(0, 8).mapToObj(t -> pool.submit(() -> IntStream.range(0, 1000)
                            .map(i -> a.allocate(new ProfileKey.MethodEntry("LT" + t + ";.m" + i + "()V"))).toArray())).toList();
            boolean[] seen = new boolean[8000];
            for (Future<int[]> f : futures) {
                for (int slot : f.get()) {
                    Assert.assertFalse("duplicate slot " + slot, seen[slot]);
                    seen[slot] = true;
                }
            }
            Assert.assertEquals(8000, a.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
