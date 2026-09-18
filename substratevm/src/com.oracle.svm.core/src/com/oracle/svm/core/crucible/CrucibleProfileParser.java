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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written reader for the profile emitted by {@link CrucibleProfileWriter}. It is deliberately
 * dependency-free: the image builder must be able to read a profile without pulling in a JSON
 * library. Unknown members are ignored so that a profile written by a newer writer still loads.
 */
public final class CrucibleProfileParser {

    private final Reader in;
    private int current;

    private CrucibleProfileParser(Reader in) throws IOException {
        this.in = in;
        this.current = in.read();
    }

    public static CrucibleProfile parse(Reader reader) throws IOException {
        CrucibleProfileParser parser = new CrucibleProfileParser(reader);
        Object root = parser.readValue();
        parser.skipWhitespace();
        if (parser.current != -1) {
            throw new IllegalArgumentException("Trailing content after the top-level profile object");
        }
        return toProfile(root);
    }

    private static CrucibleProfile toProfile(Object root) {
        Map<String, Object> map = asObject(root, "profile");
        int version = (int) asLong(map.get("schemaVersion"), "schemaVersion");
        if (version != CrucibleProfile.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported profile schemaVersion " + version + ", expected " + CrucibleProfile.SCHEMA_VERSION);
        }
        Map<String, Object> producer = asObject(map.get("producer"), "producer");
        CrucibleProfile.Producer p = new CrucibleProfile.Producer(
                        asString(producer.get("tool"), "producer.tool"),
                        asString(producer.getOrDefault("graalBase", ""), "producer.graalBase"),
                        asString(producer.getOrDefault("imageBuildId", ""), "producer.imageBuildId"));

        List<String> categories = new ArrayList<>();
        for (Object c : asArray(map.getOrDefault("categories", List.of()), "categories")) {
            categories.add(asString(c, "categories[]"));
        }

        List<CrucibleProfile.Method> methods = new ArrayList<>();
        for (Object m : asArray(map.getOrDefault("methods", List.of()), "methods")) {
            Map<String, Object> mo = asObject(m, "methods[]");
            List<CrucibleProfile.Conditional> conditionals = new ArrayList<>();
            for (Object c : asArray(mo.getOrDefault("conditionals", List.of()), "conditionals")) {
                Map<String, Object> co = asObject(c, "conditionals[]");
                List<String> ctx = new ArrayList<>();
                for (Object e : asArray(co.getOrDefault("ctx", List.of()), "ctx")) {
                    ctx.add(asString(e, "ctx[]"));
                }
                List<CrucibleProfile.Successor> successors = new ArrayList<>();
                for (Object sObj : asArray(co.getOrDefault("successors", List.of()), "successors")) {
                    Map<String, Object> so = asObject(sObj, "successors[]");
                    successors.add(new CrucibleProfile.Successor(
                                    (int) asLong(so.get("key"), "successors[].key"),
                                    (int) asLong(so.get("bci"), "successors[].bci"),
                                    asLong(so.get("count"), "successors[].count")));
                }
                conditionals.add(new CrucibleProfile.Conditional(List.copyOf(ctx), (int) asLong(co.get("bci"), "conditionals[].bci"), List.copyOf(successors)));
            }
            List<CrucibleProfile.VirtualInvoke> invokes = new ArrayList<>();
            for (Object v : asArray(mo.getOrDefault("virtualInvokes", List.of()), "virtualInvokes")) {
                Map<String, Object> vo = asObject(v, "virtualInvokes[]");
                List<String> ctx = new ArrayList<>();
                for (Object e : asArray(vo.getOrDefault("ctx", List.of()), "virtualInvokes[].ctx")) {
                    ctx.add(asString(e, "virtualInvokes[].ctx[]"));
                }
                List<CrucibleProfile.ObservedType> types = new ArrayList<>();
                for (Object t : asArray(vo.getOrDefault("types", List.of()), "virtualInvokes[].types")) {
                    Map<String, Object> to = asObject(t, "virtualInvokes[].types[]");
                    types.add(new CrucibleProfile.ObservedType(asString(to.get("name"), "types[].name"), asLong(to.get("count"), "types[].count")));
                }
                invokes.add(new CrucibleProfile.VirtualInvoke(List.copyOf(ctx), (int) asLong(vo.get("bci"), "virtualInvokes[].bci"),
                                asLong(vo.getOrDefault("overflow", Long.valueOf(0)), "virtualInvokes[].overflow"), List.copyOf(types)));
            }
            methods.add(new CrucibleProfile.Method(asString(mo.get("id"), "methods[].id"), asLong(mo.getOrDefault("calls", Long.valueOf(0)), "methods[].calls"),
                            List.copyOf(conditionals), List.copyOf(invokes)));
        }
        return new CrucibleProfile(version, p, List.copyOf(categories), List.copyOf(methods));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("Expected a JSON object for " + what + ", got " + describe(o));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object o, String what) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException("Expected a JSON array for " + what + ", got " + describe(o));
    }

    private static String asString(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Expected a string for " + what + ", got " + describe(o));
    }

    private static long asLong(Object o, String what) {
        if (o instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException("Expected an integer for " + what + ", got " + describe(o));
    }

    private static String describe(Object o) {
        return o == null ? "nothing" : o.getClass().getSimpleName();
    }

    private Object readValue() throws IOException {
        skipWhitespace();
        switch (current) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            case -1:
                throw new IllegalArgumentException("Unexpected end of profile");
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        read();
        skipWhitespace();
        if (current == '}') {
            read();
            return result;
        }
        while (true) {
            skipWhitespace();
            String name = readString();
            skipWhitespace();
            require(':');
            result.put(name, readValue());
            skipWhitespace();
            if (current == ',') {
                read();
                continue;
            }
            require('}');
            return result;
        }
    }

    private List<Object> readArray() throws IOException {
        List<Object> result = new ArrayList<>();
        read();
        skipWhitespace();
        if (current == ']') {
            read();
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (current == ',') {
                read();
                continue;
            }
            require(']');
            return result;
        }
    }

    private String readString() throws IOException {
        require('"');
        StringBuilder sb = new StringBuilder();
        while (current != '"') {
            if (current == -1) {
                throw new IllegalArgumentException("Unterminated string in profile");
            }
            if (current == '\\') {
                read();
                switch (current) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int value = 0;
                        for (int i = 0; i < 4; i++) {
                            read();
                            value = value * 16 + Character.digit(current, 16);
                        }
                        sb.append((char) value);
                    }
                    default -> throw new IllegalArgumentException("Invalid escape '\\" + (char) current + "' in profile");
                }
            } else {
                sb.append((char) current);
            }
            read();
        }
        read();
        return sb.toString();
    }

    private Object readNumber() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (current != -1 && (Character.isDigit(current) || current == '-' || current == '+' || current == '.' || current == 'e' || current == 'E')) {
            sb.append((char) current);
            read();
        }
        String text = sb.toString();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Expected a value in profile, got '" + (char) current + "'");
        }
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            return Long.parseLong(text);
        }
        return Double.parseDouble(text);
    }

    private void expect(String literal) throws IOException {
        for (int i = 0; i < literal.length(); i++) {
            if (current != literal.charAt(i)) {
                throw new IllegalArgumentException("Invalid literal in profile, expected '" + literal + "'");
            }
            read();
        }
    }

    private void require(char c) throws IOException {
        if (current != c) {
            throw new IllegalArgumentException("Expected '" + c + "' in profile, got " + (current == -1 ? "end of input" : "'" + (char) current + "'"));
        }
        read();
    }

    private void skipWhitespace() throws IOException {
        while (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
            read();
        }
    }

    private void read() throws IOException {
        current = in.read();
    }
}
