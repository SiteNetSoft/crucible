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

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.ProfileKey;

public class ProfileKeyTest {

    @Test
    public void methodEntryRoundTrips() {
        ProfileKey key = new ProfileKey.MethodEntry("LFoo;.bar(I)V");
        Assert.assertEquals("M|LFoo;.bar(I)V", key.encode());
        Assert.assertEquals(key, ProfileKey.decode(key.encode()));
    }

    @Test
    public void conditionalRoundTrips() {
        ProfileKey key = new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LBaz;.q()V:3", "LFoo;.bar(I)V:17"), 3, 1, 42);
        Assert.assertEquals("C|LFoo;.bar(I)V|LBaz;.q()V:3#LFoo;.bar(I)V:17|3|1|42", key.encode());
        Assert.assertEquals(key, ProfileKey.decode(key.encode()));
    }

    @Test
    public void conditionalWithoutInliningHasSingleContextElement() {
        ProfileKey.Conditional key = new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:17"), 17, 0, 20);
        Assert.assertEquals("C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|0|20", key.encode());
    }

    @Test
    public void virtualInvokeRoundTrips() {
        ProfileKey key = new ProfileKey.VirtualInvoke("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:9"), 9, "LOp;.apply()I");
        Assert.assertEquals("V|LFoo;.bar(I)V|LFoo;.bar(I)V:9|9|LOp;.apply()I", key.encode());
        Assert.assertEquals(key, ProfileKey.decode(key.encode()));
    }

    /**
     * The pooled encoding is what actually lives in an instrumented image; it has to reconstitute
     * the readable key exactly, including a context frame whose method differs from the owner's.
     */
    @Test
    public void pooledEncodingRoundTrips() {
        List<ProfileKey> keys = List.of(
                        new ProfileKey.MethodEntry("LFoo;.bar(I)V"),
                        new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LBaz;.q()V:3", "LFoo;.bar(I)V:17"), 3, 1, 42),
                        new ProfileKey.VirtualInvoke("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:9"), 9, "LOp;.apply()I"),
                        new ProfileKey.InstanceOf("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:5"), 5));
        List<String> pool = new ArrayList<>();
        ToIntFunction<String> intern = id -> {
            int index = pool.indexOf(id);
            if (index < 0) {
                pool.add(id);
                index = pool.size() - 1;
            }
            return index;
        };
        List<String> encoded = keys.stream().map(k -> k.encode(intern)).toList();
        Assert.assertEquals(List.of("M|0", "C|0|1:3#0:17|3|1|42", "V|0|0:9|9|2", "I|0|0:5|5"), encoded);
        String[] ids = pool.toArray(new String[0]);
        for (int i = 0; i < keys.size(); i++) {
            Assert.assertEquals(keys.get(i), ProfileKey.decode(encoded.get(i), ids));
        }
    }

    /** An empty pool means the keys carry their method ids inline. */
    @Test
    public void emptyPoolFallsBackToTheReadableForm() {
        ProfileKey key = new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:17"), 17, 0, 20);
        Assert.assertEquals(key, ProfileKey.decode(key.encode(), new String[0]));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownKind() {
        ProfileKey.decode("X|foo");
    }
}
