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
        Assert.assertEquals(List.of("methodCounts", "conditionalProfiles"), profile.categories());

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
    public void readsAnEmptyProfile() throws IOException {
        CrucibleProfile profile = roundTrip(new String[0], new long[0]);
        Assert.assertTrue(profile.methods().isEmpty());
    }

    @Test
    public void ignoresUnknownMembers() throws IOException {
        String json = "{ \"schemaVersion\": 1, \"somethingNew\": { \"a\": [1, 2] }, " +
                        "\"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"vm-25.3.4.1\", \"imageBuildId\": \"x\" }, " +
                        "\"categories\": [\"methodCounts\"], \"methods\": [] }";
        CrucibleProfile profile = CrucibleProfileParser.parse(new StringReader(json));
        Assert.assertEquals(List.of("methodCounts"), profile.categories());
    }

    @Test
    public void unescapesStrings() throws IOException {
        String json = "{ \"schemaVersion\": 1, \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"b\", \"imageBuildId\": \"x\" }, " +
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
        CrucibleProfileParser.parse(new StringReader("{ \"schemaVersion\": 1, \"producer\": {"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTrailingContent() throws IOException {
        String json = "{ \"schemaVersion\": 1, \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"b\", \"imageBuildId\": \"x\" }, " +
                        "\"categories\": [], \"methods\": [] } trailing";
        CrucibleProfileParser.parse(new StringReader(json));
    }
}
