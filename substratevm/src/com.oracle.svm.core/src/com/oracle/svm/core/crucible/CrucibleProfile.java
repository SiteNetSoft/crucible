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
 * In-memory form of a {@code schemaVersion} 1 profile, as produced by {@link CrucibleProfileWriter}
 * and consumed by the pass 2 profile lookup.
 */
public record CrucibleProfile(int schemaVersion, Producer producer, List<String> categories, List<Method> methods) {

    public static final int SCHEMA_VERSION = 1;

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

    public record Method(String id, long calls, List<Conditional> conditionals) {
    }
}
