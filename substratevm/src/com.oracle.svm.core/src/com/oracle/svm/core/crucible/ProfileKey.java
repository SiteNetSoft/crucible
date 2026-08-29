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

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Identity of one profile counter. Encoded as a single string so the slot table can live in the
 * image heap as a {@code String[]} and be emitted without further lookups at tear-down.
 */
public sealed interface ProfileKey permits ProfileKey.MethodEntry, ProfileKey.Conditional {

    String SEP = "|";
    String CTX_SEP = "#";

    String methodId();

    String encode();

    record MethodEntry(String methodId) implements ProfileKey {
        @Override
        public String encode() {
            return "M" + SEP + methodId;
        }
    }

    record Conditional(String methodId, List<String> context, int bci, int successor) implements ProfileKey {
        @Override
        public String encode() {
            return "C" + SEP + methodId + SEP + String.join(CTX_SEP, context) + SEP + bci + SEP + successor;
        }
    }

    static ProfileKey decode(String s) {
        String[] parts = s.split("\\" + SEP, -1);
        switch (parts[0]) {
            case "M":
                return new MethodEntry(parts[1]);
            case "C":
                List<String> ctx = Arrays.asList(parts[2].split(CTX_SEP, -1));
                return new Conditional(parts[1], ctx, Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
            default:
                throw new IllegalArgumentException("Unknown profile key: " + s);
        }
    }

    /** JVM-style descriptor {@code L<class>;.<name><signature>} of a method. */
    static String methodId(ResolvedJavaMethod method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + method.getSignature().toMethodDescriptor();
    }

    /** Builds the key for successor {@code successor} of the control split at {@code pos}. */
    static Conditional forPosition(NodeSourcePosition pos, int successor) {
        List<String> ctx = new ArrayList<>();
        NodeSourcePosition p = pos;
        while (p != null) {
            ctx.add(methodId(p.getMethod()) + ":" + p.getBCI());
            p = p.getCaller();
        }
        return new Conditional(methodId(pos.getRootMethod()), ctx, pos.getBCI(), successor);
    }
}
