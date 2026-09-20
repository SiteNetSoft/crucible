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

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.CrucibleProfile;
import com.oracle.svm.core.crucible.CrucibleProfileParser;
import com.oracle.svm.core.crucible.CrucibleProfileWriter;

public class CrucibleProfileParserTest {

    private static CrucibleProfile roundTrip(String[] keys, long[] counts) throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, keys, counts, "build-7");
        return CrucibleProfileParser.parse(new StringReader(sb.toString()));
    }

    @Test
    public void readsWhatTheWriterWrote() throws IOException {
        String[] keys = {
                        "M|LFoo;.bar(I)V",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|0|20",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|1|31",
        };
        CrucibleProfile profile = roundTrip(keys, new long[]{12, 10, 2});

        Assert.assertEquals(CrucibleProfile.SCHEMA_VERSION, profile.schemaVersion());
        Assert.assertEquals("CrucibleVM", profile.producer().tool());
        Assert.assertEquals("build-7", profile.producer().imageBuildId());
        Assert.assertEquals(List.of("methodCounts", "conditionalProfiles", "virtualInvokeProfiles", "instanceOfProfiles"), profile.categories());

        Assert.assertEquals(1, profile.methods().size());
        CrucibleProfile.Method method = profile.methods().get(0);
        Assert.assertEquals("LFoo;.bar(I)V", method.id());
        Assert.assertEquals(12, method.calls());

        Assert.assertEquals(1, method.conditionals().size());
        CrucibleProfile.Conditional conditional = method.conditionals().get(0);
        Assert.assertEquals(List.of("LFoo;.bar(I)V:17"), conditional.ctx());
        Assert.assertEquals(17, conditional.bci());
        Assert.assertEquals(List.of(new CrucibleProfile.Successor(0, 20, 10), new CrucibleProfile.Successor(1, 31, 2)), conditional.successors());
    }

    @Test
    public void readsReceiverTypesTheWriterWrote() throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, new String[]{"M|LFoo;.bar(I)V"}, new long[]{3}, "build-7",
                        new String[]{"V|LFoo;.bar(I)V|LFoo;.bar(I)V:9|9|LOp;.apply()I"},
                        new int[]{7, 8, -1, -1}, new long[]{20, 5, 0, 0}, new long[]{2},
                        id -> id == 7 ? "LA;" : id == 8 ? "LB;" : null);
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(sb.toString()));

        CrucibleProfile.Method method = profile.methods().get(0);
        Assert.assertEquals(1, method.virtualInvokes().size());
        CrucibleProfile.VirtualInvoke invoke = method.virtualInvokes().get(0);
        Assert.assertEquals(List.of("LFoo;.bar(I)V:9"), invoke.ctx());
        Assert.assertEquals(9, invoke.bci());
        Assert.assertEquals("LOp;.apply()I", invoke.target());
        Assert.assertEquals(2, invoke.overflow());
        Assert.assertEquals(List.of(new CrucibleProfile.ObservedType("LA;", 20), new CrucibleProfile.ObservedType("LB;", 5)), invoke.types());
    }

    @Test
    public void dropsTypesTheImageCannotName() throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, new String[]{"M|LFoo;.bar(I)V"}, new long[]{3}, "build-7",
                        new String[]{"V|LFoo;.bar(I)V|LFoo;.bar(I)V:9|9|LOp;.apply()I"},
                        new int[]{7, -1, -1, -1}, new long[]{20, 0, 0, 0}, new long[]{0},
                        id -> null);
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(sb.toString()));
        Assert.assertTrue(profile.methods().get(0).virtualInvokes().isEmpty());
    }

    @Test
    public void readsInstanceOfSites() throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, new String[]{"M|LFoo;.bar(I)V"}, new long[]{3}, "build-7",
                        new String[]{"I|LFoo;.bar(I)V|LFoo;.bar(I)V:11|11"},
                        new int[]{5, 6, -1, -1}, new long[]{40, 8, 0, 0}, new long[]{1},
                        id -> id == 5 ? "LA;" : id == 6 ? "LB;" : null);
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(sb.toString()));

        CrucibleProfile.Method method = profile.methods().get(0);
        Assert.assertTrue(method.virtualInvokes().isEmpty());
        Assert.assertEquals(1, method.instanceOfs().size());
        CrucibleProfile.InstanceOfSite test = method.instanceOfs().get(0);
        Assert.assertEquals(11, test.bci());
        Assert.assertEquals(1, test.overflow());
        Assert.assertEquals(List.of(new CrucibleProfile.ObservedType("LA;", 40), new CrucibleProfile.ObservedType("LB;", 8)), test.types());
    }

    @Test
    public void readsAnEmptyProfile() throws IOException {
        CrucibleProfile profile = roundTrip(new String[0], new long[0]);
        Assert.assertTrue(profile.methods().isEmpty());
    }

    @Test
    public void ignoresUnknownMembers() throws IOException {
        String json = "{ \"schemaVersion\": 3, \"somethingNew\": { \"a\": [1, 2] }, " +
                        "\"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"vm-25.3.4.1\", \"imageBuildId\": \"x\" }, " +
                        "\"categories\": [\"methodCounts\"], \"methods\": [] }";
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(json));
        Assert.assertEquals(List.of("methodCounts"), profile.categories());
    }

    @Test
    public void unescapesStrings() throws IOException {
        String json = "{ \"schemaVersion\": 3, \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"b\", \"imageBuildId\": \"x\" }, " +
                        "\"categories\": [], \"methods\": [ { \"id\": \"LA\\\\B;.q(\\\"x\\\")V\", \"calls\": 1 } ] }";
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(json));
        Assert.assertEquals("LA\\B;.q(\"x\")V", profile.methods().get(0).id());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnUnsupportedSchemaVersion() throws IOException {
        String json = "{ \"schemaVersion\": 99, \"producer\": { \"tool\": \"CrucibleVM\" }, \"categories\": [], \"methods\": [] }";
        CrucibleProfileParser.parse(new StringReader(json));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedInput() throws IOException {
        CrucibleProfileParser.parse(new StringReader("{ \"schemaVersion\": 3, \"producer\": {"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTrailingContent() throws IOException {
        String json = "{ \"schemaVersion\": 3, \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"b\", \"imageBuildId\": \"x\" }, " +
                        "\"categories\": [], \"methods\": [] } trailing";
        CrucibleProfileParser.parse(new StringReader(json));
    }

    /** Sampled stacks are optional, keep their order, and a profile without any has none. */
    @Test
    public void readsSampledStacks() throws IOException {
        String json = "{ \"schemaVersion\": 3, \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"x\", \"imageBuildId\": \"b\" }, " +
                        "\"categories\": [\"sampledStacks\"], \"methods\": [], " +
                        "\"samples\": [ { \"stack\": [\"LA;.main()V:3\", \"LB;.run()V:17\"], \"count\": 41 }, { \"stack\": [\"LA;.main()V:9\"], \"count\": 2 } ] }";
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(json));
        Assert.assertEquals(2, profile.samples().size());
        Assert.assertEquals(List.of("LA;.main()V:3", "LB;.run()V:17"), profile.samples().get(0).stack());
        Assert.assertEquals(41, profile.samples().get(0).count());
        CrucibleProfile without = CrucibleProfileParser.parse(new StringReader(json.substring(0, json.indexOf(", \"samples\"")) + " }"));
        Assert.assertTrue(without.samples().isEmpty());
    }
}
