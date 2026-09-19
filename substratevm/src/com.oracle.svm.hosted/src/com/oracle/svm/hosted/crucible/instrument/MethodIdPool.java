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
package com.oracle.svm.hosted.crucible.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared pool of the method ids that profile keys are built from, so each id is stored once rather
 * than once per key. Safe for parallel compilation; indices are dense and assigned on first sight.
 */
public final class MethodIdPool {

    private final ConcurrentHashMap<String, Integer> indices = new ConcurrentHashMap<>();
    private final List<String> byIndex = new ArrayList<>();

    public int intern(String methodId) {
        Integer existing = indices.get(methodId);
        if (existing != null) {
            return existing;
        }
        synchronized (byIndex) {
            return indices.computeIfAbsent(methodId, k -> {
                byIndex.add(k);
                return byIndex.size() - 1;
            });
        }
    }

    public String[] freeze() {
        synchronized (byIndex) {
            return byIndex.toArray(new String[0]);
        }
    }
}
