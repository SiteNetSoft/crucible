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

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.CrucibleProfileWriter;

public class CrucibleProfileWriterTest {

    private static String write(String[] keys, long[] counts) throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, keys, counts, "build-1");
        return sb.toString();
    }

    @Test
    public void emitsHeaderAndCategories() throws IOException {
        String json = write(new String[0], new long[0]);
        Assert.assertTrue(json, json.contains("\"schemaVersion\": 2"));
        Assert.assertTrue(json, json.contains("\"tool\": \"CrucibleVM\""));
        Assert.assertTrue(json, json.contains("\"graalBase\": \"vm-25.3.4.1\""));
        Assert.assertTrue(json, json.contains("\"imageBuildId\": \"build-1\""));
        Assert.assertTrue(json, json.contains("\"categories\": [\"methodCounts\", \"conditionalProfiles\", \"virtualInvokeProfiles\"]"));
        Assert.assertTrue(json, json.contains("\"methods\": []"));
    }

    @Test
    public void groupsCountersByMethodAndConditional() throws IOException {
        String[] keys = {
                        "M|LFoo;.bar(I)V",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|0|20",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|1|31",
                        "M|LZzz;.never()V",
        };
        long[] counts = {12, 10, 2, 0};
        String json = write(keys, counts);
        String expected = String.join("\n",
                        "{",
                        "  \"schemaVersion\": 2,",
                        "  \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"vm-25.3.4.1\", \"imageBuildId\": \"build-1\" },",
                        "  \"categories\": [\"methodCounts\", \"conditionalProfiles\", \"virtualInvokeProfiles\"],",
                        "  \"methods\": [",
                        "    {",
                        "      \"id\": \"LFoo;.bar(I)V\",",
                        "      \"calls\": 12,",
                        "      \"conditionals\": [",
                        "        { \"ctx\": [\"LFoo;.bar(I)V:17\"], \"bci\": 17, \"successors\": [ { \"key\": 0, \"bci\": 20, \"count\": 10 }, { \"key\": 1, \"bci\": 31, \"count\": 2 } ] }",
                        "      ]",
                        "    }",
                        "  ]",
                        "}",
                        "");
        Assert.assertEquals(expected, json);
    }

    @Test
    public void escapesQuotesAndBackslashesInIds() throws IOException {
        String json = write(new String[]{"M|La\"b\\c;.m()V"}, new long[]{1});
        Assert.assertTrue(json, json.contains("\"id\": \"La\\\"b\\\\c;.m()V\""));
    }
}
