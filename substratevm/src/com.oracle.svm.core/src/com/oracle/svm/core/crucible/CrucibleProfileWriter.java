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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;

/** Streams the collected counters as a schema-v2 CrucibleVM profile. */
public final class CrucibleProfileWriter {

    private CrucibleProfileWriter() {
    }

    public static RuntimeSupport.Hook teardownHook() {
        return isFirstIsolate -> writeAtExit();
    }

    private static void writeAtExit() {
        if (!CrucibleProfileRuntime.isPresent()) {
            return;
        }
        CrucibleProfileRuntime rt = CrucibleProfileRuntime.singleton();
        Path path = Path.of(CrucibleOptions.CrucibleProfileOutput.getValue());
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            write(w, rt.keys(), rt.counters(), rt.imageBuildId(), rt.typeKeys(), rt.typeIds(), rt.typeCounts(), rt.typeOverflow(), rt::typeName);
        } catch (IOException e) {
            Log.log().string("CrucibleVM: could not write profile to ").string(path.toString()).string(": ").string(e.toString()).newline();
        }
    }

    /** One successor of a control split: its own bci and the accumulated execution count. */
    private static final class SuccessorData {
        final int bci;
        long count;

        SuccessorData(int bci, long count) {
            this.bci = bci;
            this.count = count;
        }
    }

    /** One receiver-type sampling site: observed type names and how often the row overflowed. */
    private static final class TypeSiteData {
        final TreeMap<String, Long> types = new TreeMap<>();
        long overflow;
    }

    /** Per-method accumulation used only while writing. */
    private static final class MethodData {
        long calls;
        /* (bci, ctx) -> (successor index -> accumulated successor record) */
        final TreeMap<String, TreeMap<Integer, SuccessorData>> conditionals = new TreeMap<>();
        final Map<String, ProfileKey.Conditional> exemplars = new TreeMap<>();
        final TreeMap<String, TypeSiteData> virtualInvokes = new TreeMap<>();
        final Map<String, ProfileKey.VirtualInvoke> invokeExemplars = new TreeMap<>();
        final TreeMap<String, TypeSiteData> instanceOfs = new TreeMap<>();
        final Map<String, ProfileKey.InstanceOf> instanceOfExemplars = new TreeMap<>();
    }

    /** Kept so the existing counter-only tests and callers stay valid. */
    public static void write(Appendable out, String[] keys, long[] counters, String imageBuildId) throws IOException {
        write(out, keys, counters, imageBuildId, new String[0], new int[0], new long[0], new long[0], id -> null);
    }

    public static void write(Appendable out, String[] keys, long[] counters, String imageBuildId,
                    String[] typeKeys, int[] typeIds, long[] typeCounts, long[] typeOverflow, IntFunction<String> typeName) throws IOException {
        TreeMap<String, MethodData> methods = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            long count = counters[i];
            ProfileKey key = ProfileKey.decode(keys[i]);
            MethodData md = methods.computeIfAbsent(key.methodId(), k -> new MethodData());
            if (key instanceof ProfileKey.MethodEntry) {
                md.calls += count;
            } else if (key instanceof ProfileKey.Conditional c && count != 0) {
                String group = String.format("%010d|%s", c.bci(), String.join(ProfileKey.CTX_SEP, c.context()));
                md.conditionals.computeIfAbsent(group, g -> new TreeMap<>())
                                .merge(c.successor(), new SuccessorData(c.successorBci(), count), (a, b) -> {
                                    a.count += b.count;
                                    return a;
                                });
                md.exemplars.putIfAbsent(group, c);
            }
        }

        for (int site = 0; site < typeKeys.length; site++) {
            ProfileKey decoded = ProfileKey.decode(typeKeys[site]);
            TypeSiteData data = null;
            for (int i = 0; i < CrucibleProfileRuntime.TYPE_ROW_WIDTH; i++) {
                int entry = site * CrucibleProfileRuntime.TYPE_ROW_WIDTH + i;
                if (entry >= typeIds.length || typeIds[entry] == CrucibleProfileRuntime.NO_TYPE || typeCounts[entry] == 0) {
                    continue;
                }
                String name = typeName.apply(typeIds[entry]);
                if (name == null) {
                    /* A type the image cannot name is not useful to pass 2. */
                    continue;
                }
                if (data == null) {
                    MethodData md = methods.computeIfAbsent(decoded.methodId(), k -> new MethodData());
                    if (decoded instanceof ProfileKey.VirtualInvoke invoke) {
                        String group = String.format("%010d|%s", invoke.bci(), String.join(ProfileKey.CTX_SEP, invoke.context()));
                        data = md.virtualInvokes.computeIfAbsent(group, g -> new TypeSiteData());
                        md.invokeExemplars.putIfAbsent(group, invoke);
                    } else if (decoded instanceof ProfileKey.InstanceOf test) {
                        String group = String.format("%010d|%s", test.bci(), String.join(ProfileKey.CTX_SEP, test.context()));
                        data = md.instanceOfs.computeIfAbsent(group, g -> new TypeSiteData());
                        md.instanceOfExemplars.putIfAbsent(group, test);
                    } else {
                        break;
                    }
                }
                data.types.merge(name, typeCounts[entry], Long::sum);
            }
            if (data != null && site < typeOverflow.length) {
                data.overflow += typeOverflow[site];
            }
        }

        out.append("{\n");
        out.append("  \"schemaVersion\": 3,\n");
        out.append("  \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"").append(CrucibleProfileRuntime.GRAAL_BASE)
                        .append("\", \"imageBuildId\": \"").append(escape(imageBuildId)).append("\" },\n");
        out.append("  \"categories\": [\"methodCounts\", \"conditionalProfiles\", \"virtualInvokeProfiles\", \"instanceOfProfiles\"],\n");

        List<Map.Entry<String, MethodData>> live = new ArrayList<>();
        for (Map.Entry<String, MethodData> e : methods.entrySet()) {
            if (e.getValue().calls != 0 || !e.getValue().conditionals.isEmpty() || !e.getValue().virtualInvokes.isEmpty() || !e.getValue().instanceOfs.isEmpty()) {
                live.add(e);
            }
        }
        if (live.isEmpty()) {
            out.append("  \"methods\": []\n}\n");
            return;
        }
        out.append("  \"methods\": [\n");
        for (int m = 0; m < live.size(); m++) {
            String id = live.get(m).getKey();
            MethodData md = live.get(m).getValue();
            out.append("    {\n");
            out.append("      \"id\": \"").append(escape(id)).append("\",\n");
            out.append("      \"calls\": ").append(Long.toString(md.calls));
            if (!md.conditionals.isEmpty()) {
                out.append(",\n      \"conditionals\": [\n");
                int c = 0;
                for (Map.Entry<String, TreeMap<Integer, SuccessorData>> ce : md.conditionals.entrySet()) {
                    ProfileKey.Conditional ex = md.exemplars.get(ce.getKey());
                    out.append("        { \"ctx\": [");
                    for (int i = 0; i < ex.context().size(); i++) {
                        out.append(i == 0 ? "" : ", ").append('"').append(escape(ex.context().get(i))).append('"');
                    }
                    out.append("], \"bci\": ").append(Integer.toString(ex.bci())).append(", \"successors\": [ ");
                    int s = 0;
                    for (Map.Entry<Integer, SuccessorData> se : ce.getValue().entrySet()) {
                        out.append(s++ == 0 ? "" : ", ").append("{ \"key\": ").append(se.getKey().toString())
                                        .append(", \"bci\": ").append(Integer.toString(se.getValue().bci))
                                        .append(", \"count\": ").append(Long.toString(se.getValue().count)).append(" }");
                    }
                    out.append(" ] }").append(++c < md.conditionals.size() ? ",\n" : "\n");
                }
                out.append("      ]");
            }
            if (!md.virtualInvokes.isEmpty()) {
                out.append(",\n      \"virtualInvokes\": [\n");
                int v = 0;
                for (Map.Entry<String, TypeSiteData> ve : md.virtualInvokes.entrySet()) {
                    ProfileKey.VirtualInvoke ex = md.invokeExemplars.get(ve.getKey());
                    out.append("        { \"ctx\": [");
                    for (int i = 0; i < ex.context().size(); i++) {
                        out.append(i == 0 ? "" : ", ").append('"').append(escape(ex.context().get(i))).append('"');
                    }
                    out.append("], \"bci\": ").append(Integer.toString(ex.bci()));
                    out.append(", \"target\": \"").append(escape(ex.targetMethodId())).append('"');
                    out.append(", \"overflow\": ").append(Long.toString(ve.getValue().overflow));
                    out.append(", \"types\": [ ");
                    int t = 0;
                    for (Map.Entry<String, Long> te : ve.getValue().types.entrySet()) {
                        out.append(t++ == 0 ? "" : ", ").append("{ \"name\": \"").append(escape(te.getKey()))
                                        .append("\", \"count\": ").append(Long.toString(te.getValue())).append(" }");
                    }
                    out.append(" ] }").append(++v < md.virtualInvokes.size() ? ",\n" : "\n");
                }
                out.append("      ]");
            }
            if (!md.instanceOfs.isEmpty()) {
                out.append(",\n      \"instanceOfs\": [\n");
                int i = 0;
                for (Map.Entry<String, TypeSiteData> ie : md.instanceOfs.entrySet()) {
                    ProfileKey.InstanceOf ex = md.instanceOfExemplars.get(ie.getKey());
                    out.append("        { \"ctx\": [");
                    for (int c2 = 0; c2 < ex.context().size(); c2++) {
                        out.append(c2 == 0 ? "" : ", ").append('"').append(escape(ex.context().get(c2))).append('"');
                    }
                    out.append("], \"bci\": ").append(Integer.toString(ex.bci()));
                    out.append(", \"overflow\": ").append(Long.toString(ie.getValue().overflow));
                    out.append(", \"types\": [ ");
                    int t = 0;
                    for (Map.Entry<String, Long> te : ie.getValue().types.entrySet()) {
                        out.append(t++ == 0 ? "" : ", ").append("{ \"name\": \"").append(escape(te.getKey()))
                                        .append("\", \"count\": ").append(Long.toString(te.getValue())).append(" }");
                    }
                    out.append(" ] }").append(++i < md.instanceOfs.size() ? ",\n" : "\n");
                }
                out.append("      ]");
            }
            out.append("\n");
            out.append("    }").append(m + 1 < live.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
