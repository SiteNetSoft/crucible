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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.java.StableMethodNameFormatter;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Identity of one profile counter. Encoded as a single string so the slot table can live in the
 * image heap as a {@code String[]} and be emitted without further lookups at tear-down.
 */
public sealed interface ProfileKey permits ProfileKey.MethodEntry, ProfileKey.Conditional, ProfileKey.VirtualInvoke, ProfileKey.InstanceOf {

    String SEP = "|";
    String CTX_SEP = "#";

    String methodId();

    String encode();

    /**
     * Encodes this key with every method id replaced by its index in {@code intern}'s pool.
     * <p>
     * A key names its method at least twice -- once as the owner and once inside each context
     * frame -- and a method id runs to sixty characters or more, so the readable encoding stores
     * the same few thousand strings hundreds of thousands of times. In a GameOfLife image that was
     * 29 MiB of key strings. Pooling leaves each key a handful of decimal digits and the pool one
     * copy of each distinct id. The profile written at tear-down is unaffected: {@link #decode} is
     * given the pool and reconstitutes the readable form.
     */
    String encode(ToIntFunction<String> intern);

    record MethodEntry(String methodId) implements ProfileKey {
        @Override
        public String encode() {
            return "M" + SEP + methodId;
        }

        @Override
        public String encode(ToIntFunction<String> intern) {
            return "M" + SEP + intern.applyAsInt(methodId);
        }
    }

    /**
     * @param bci bytecode index of the control split itself.
     * @param successor index of the successor among {@code ControlSplitNode.successors()}.
     * @param successorBci bytecode index reported by the successor's own node source position.
     *            Upstream's {@code PGOApplyProfilesPhase} matches profile records to successors by
     *            this value, not by {@link #successor()}, so it has to be recorded in pass 1.
     */
    record Conditional(String methodId, List<String> context, int bci, int successor, int successorBci) implements ProfileKey {
        @Override
        public String encode() {
            return "C" + SEP + methodId + SEP + String.join(CTX_SEP, context) + SEP + bci + SEP + successor + SEP + successorBci;
        }

        @Override
        public String encode(ToIntFunction<String> intern) {
            return "C" + SEP + intern.applyAsInt(methodId) + SEP + encodeContext(context, intern) + SEP + bci + SEP + successor + SEP + successorBci;
        }
    }

    /**
     * Identity of one receiver-type sampling site.
     *
     * @param bci bytecode index of the call site.
     */
    /**
     * @param targetMethodId the method declared at the call site. The receiver types observed at
     *            run time are resolved against it in pass 2 to name the method each call actually
     *            reached.
     */
    record VirtualInvoke(String methodId, List<String> context, int bci, String targetMethodId) implements ProfileKey {
        @Override
        public String encode() {
            return "V" + SEP + methodId + SEP + String.join(CTX_SEP, context) + SEP + bci + SEP + targetMethodId;
        }

        @Override
        public String encode(ToIntFunction<String> intern) {
            return "V" + SEP + intern.applyAsInt(methodId) + SEP + encodeContext(context, intern) + SEP + bci + SEP + intern.applyAsInt(targetMethodId);
        }
    }

    /** Identity of one {@code instanceof} site whose tested values are sampled. */
    record InstanceOf(String methodId, List<String> context, int bci) implements ProfileKey {
        @Override
        public String encode() {
            return "I" + SEP + methodId + SEP + String.join(CTX_SEP, context) + SEP + bci;
        }

        @Override
        public String encode(ToIntFunction<String> intern) {
            return "I" + SEP + intern.applyAsInt(methodId) + SEP + encodeContext(context, intern) + SEP + bci;
        }
    }

    /**
     * Replaces the method part of each {@code <methodId>:<bci>} frame with its pool index. A method
     * id is a JVM descriptor and never contains a colon, so the last one separates the two parts.
     */
    private static String encodeContext(List<String> context, ToIntFunction<String> intern) {
        StringBuilder sb = new StringBuilder();
        for (String frame : context) {
            if (sb.length() != 0) {
                sb.append(CTX_SEP);
            }
            int colon = frame.lastIndexOf(':');
            if (colon < 0) {
                /* Not a frame this encoder produced; carry it through verbatim. */
                sb.append(frame);
            } else {
                sb.append(intern.applyAsInt(frame.substring(0, colon))).append(':').append(frame, colon + 1, frame.length());
            }
        }
        return sb.toString();
    }

    private static List<String> decodeContext(String encoded, String[] pool) {
        String[] frames = encoded.split(CTX_SEP, -1);
        List<String> context = new ArrayList<>(frames.length);
        for (String frame : frames) {
            int colon = frame.lastIndexOf(':');
            if (colon < 0) {
                context.add(frame);
            } else {
                context.add(pool[Integer.parseInt(frame.substring(0, colon))] + ':' + frame.substring(colon + 1));
            }
        }
        return context;
    }

    /** Decodes a key encoded by {@link #encode(ToIntFunction)} against the same pool. */
    static ProfileKey decode(String s, String[] pool) {
        if (pool.length == 0) {
            return decode(s);
        }
        String[] parts = s.split("\\" + SEP, -1);
        switch (parts[0]) {
            case "M":
                return new MethodEntry(pool[Integer.parseInt(parts[1])]);
            case "C":
                return new Conditional(pool[Integer.parseInt(parts[1])], decodeContext(parts[2], pool), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
            case "V":
                return new VirtualInvoke(pool[Integer.parseInt(parts[1])], decodeContext(parts[2], pool), Integer.parseInt(parts[3]), pool[Integer.parseInt(parts[4])]);
            case "I":
                return new InstanceOf(pool[Integer.parseInt(parts[1])], decodeContext(parts[2], pool), Integer.parseInt(parts[3]));
            default:
                throw new IllegalArgumentException("Unknown profile key: " + s);
        }
    }

    static ProfileKey decode(String s) {
        String[] parts = s.split("\\" + SEP, -1);
        switch (parts[0]) {
            case "M":
                return new MethodEntry(parts[1]);
            case "C":
                List<String> ctx = Arrays.asList(parts[2].split(CTX_SEP, -1));
                return new Conditional(parts[1], ctx, Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
            case "V":
                return new VirtualInvoke(parts[1], Arrays.asList(parts[2].split(CTX_SEP, -1)), Integer.parseInt(parts[3]), parts[4]);
            case "I":
                return new InstanceOf(parts[1], Arrays.asList(parts[2].split(CTX_SEP, -1)), Integer.parseInt(parts[3]));
            default:
                throw new IllegalArgumentException("Unknown profile key: " + s);
        }
    }

    /** JVM-style descriptor {@code L<class>;.<name><signature>} of a method. */
    static String methodId(ResolvedJavaMethod method) {
        return method.getDeclaringClass().getName() + "." + nameWithoutVariant(method.getName()) + method.getSignature().toMethodDescriptor();
    }

    /**
     * A variant of a method, a copy compiled for one of its callers say, carries a suffix on its
     * name. What was recorded was recorded for the method, whichever copy is asking.
     */
    static String nameWithoutVariant(String name) {
        int variant = name.indexOf(StableMethodNameFormatter.METHOD_VARIANT_KEY_SEPARATOR);
        return variant > 0 ? name.substring(0, variant) : name;
    }

    /** Builds the key for the {@code instanceof} sampling site at {@code pos}. */
    static InstanceOf instanceOfForPosition(NodeSourcePosition pos) {
        return new InstanceOf(methodId(pos.getRootMethod()), contextOf(pos), pos.getBCI());
    }

    /** Builds the key for the receiver-type sampling site at {@code pos}. */
    static VirtualInvoke virtualInvokeForPosition(NodeSourcePosition pos, ResolvedJavaMethod targetMethod) {
        return new VirtualInvoke(methodId(pos.getRootMethod()), contextOf(pos), pos.getBCI(), methodId(targetMethod));
    }

    /**
     * Inlining context of {@code pos}, innermost frame first, each element {@code <methodId>:<bci>}.
     * <p>
     * Truncated to {@link CrucibleOptions#CrucibleMaxContextDepth} frames. Carrying the whole
     * context is what makes an instrumented image large -- it was 65 MiB of key strings in a
     * GameOfLife image -- while about nine in ten applied profiles are matched by the innermost
     * frame alone, through the context-insensitive fallback.
     */
    static List<String> contextOf(NodeSourcePosition pos) {
        int limit = CrucibleOptions.CrucibleMaxContextDepth.getValue();
        List<String> ctx = new ArrayList<>();
        for (NodeSourcePosition p = pos; p != null; p = p.getCaller()) {
            ctx.add(methodId(p.getMethod()) + ":" + p.getBCI());
            if (limit > 0 && ctx.size() >= limit) {
                break;
            }
        }
        return ctx;
    }

    /** Builds the key for successor {@code successor} of the control split at {@code pos}. */
    static Conditional forPosition(NodeSourcePosition pos, int successor, int successorBci) {
        return new Conditional(methodId(pos.getRootMethod()), contextOf(pos), pos.getBCI(), successor, successorBci);
    }
}
