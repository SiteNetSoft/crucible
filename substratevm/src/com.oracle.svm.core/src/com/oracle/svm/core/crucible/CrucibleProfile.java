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

import java.util.List;

/**
 * In-memory form of a {@code schemaVersion} 3 profile, as produced by {@link CrucibleProfileWriter}
 * and consumed by the pass 2 profile lookup.
 */
public record CrucibleProfile(int schemaVersion, Producer producer, List<String> categories, List<Method> methods, List<Sample> samples) {

    /** A profile with counters only, as the recording image writes it. */
    public CrucibleProfile(int schemaVersion, Producer producer, List<String> categories, List<Method> methods) {
        this(schemaVersion, producer, categories, methods, List.of());
    }

    /**
     * A call stack a time sampler caught the program in, and how often it caught it there.
     * <p>
     * Counters say how often each thing happened but not under whom. A stack does: the frame
     * above a call is the method that call reached on that occasion, so a set of stacks holds, for
     * every call site, which method it reached in which calling context. That is what resolves a
     * call deep inside shared library code that goes to a different place for every caller.
     *
     * @param stack outermost frame first, each element {@code <methodId>:<bci>}, where the bci is
     *            that of the call to the next frame, or for the last frame where the sample landed.
     */
    public record Sample(List<String> stack, long count) {
    }

    public static final int SCHEMA_VERSION = 3;

    public record Producer(String tool, String graalBase, String imageBuildId) {
    }

    /**
     * @param key index of the successor among the control split's successors.
     * @param bci bytecode index of the successor itself; pass 2 matches successors by this value.
     */
    public record Successor(int key, int bci, long count) {
    }

    /**
     * @param ctx inlining context, innermost frame first, each element {@code <methodId>:<bci>}.
     * @param bci bytecode index of the control split.
     */
    public record Conditional(List<String> ctx, int bci, List<Successor> successors) {
    }

    /** One observed receiver type at a call site. */
    public record ObservedType(String name, long count) {
    }

    /**
     * Receiver types seen at one indirect call site.
     *
     * @param overflow times the site saw a type that no longer fit in its row, so the type list is
     *            incomplete by that many observations.
     */
    public record VirtualInvoke(List<String> ctx, int bci, String target, long overflow, List<ObservedType> types) {
    }

    /** Types observed at one {@code instanceof} site. */
    public record InstanceOfSite(List<String> ctx, int bci, long overflow, List<ObservedType> types) {
    }

    /**
     * @param firstCall position of this method in the order the run first entered methods, or 0
     *            when the profile never saw it start. Used to lay the image out in the sequence a
     *            run touches it.
     */
    public record Method(String id, long calls, int firstCall, List<Conditional> conditionals, List<VirtualInvoke> virtualInvokes, List<InstanceOfSite> instanceOfs) {
    }
}
