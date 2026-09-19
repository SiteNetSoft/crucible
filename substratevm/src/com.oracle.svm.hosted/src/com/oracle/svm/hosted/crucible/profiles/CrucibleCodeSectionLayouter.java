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
package com.oracle.svm.hosted.crucible.profiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.oracle.svm.hosted.code.CodeSectionLayouter;
import com.oracle.svm.hosted.meta.HostedMethod;

import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.code.CompilationResult;

/**
 * Orders the code section by how often the profile saw each method run.
 * <p>
 * The community edition lays methods out alphabetically by name, which scatters the code a run
 * actually executes across the whole section. A profiled image typically executes a small fraction
 * of what it contains -- a few hundred methods out of several thousand -- so ordering by recorded
 * call count puts that working set together and pushes everything never observed to the end, which
 * is the classic profile-guided layout win for instruction locality and startup paging.
 * <p>
 * Methods the profile says nothing about keep the alphabetical order the community edition would
 * have given them, so the layout stays deterministic between builds of the same profile.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class CrucibleCodeSectionLayouter implements CodeSectionLayouter {

    private final CrucibleProfilesLookup profiles;
    private final boolean byStartupOrder;
    private int hot;
    private int cold;
    private int ordered;

    public CrucibleCodeSectionLayouter(CrucibleProfilesLookup profiles, boolean byStartupOrder) {
        this.profiles = profiles;
        this.byStartupOrder = byStartupOrder;
    }

    @Override
    public List<HostedMethod> layout(Map<HostedMethod, CompilationResult> compilations) {
        List<HostedMethod> executed = new ArrayList<>();
        List<HostedMethod> unobserved = new ArrayList<>();
        for (HostedMethod method : compilations.keySet()) {
            if (profiles.getCallCountOrZero(method) > 0) {
                executed.add(method);
            } else {
                unobserved.add(method);
            }
        }
        if (byStartupOrder) {
            /*
             * Startup order: lay methods out in the sequence the run first entered them, so the
             * pages touched while starting up are contiguous and in the order they are needed.
             * Methods that ran but whose first entry was never recorded follow, hottest first.
             */
            List<HostedMethod> started = new ArrayList<>();
            List<HostedMethod> rest = new ArrayList<>();
            for (HostedMethod method : executed) {
                (profiles.firstCallOrder(method) > 0 ? started : rest).add(method);
            }
            started.sort(Comparator.comparingInt(profiles::firstCallOrder).thenComparing(HostedMethod::getQualifiedName));
            rest.sort(Comparator.comparingLong(profiles::getCallCountOrZero).reversed().thenComparing(HostedMethod::getQualifiedName));
            ordered = started.size();
            executed = new ArrayList<>(started);
            executed.addAll(rest);
        } else {
            /* Hottest first, ties and unobserved methods by name so the layout is reproducible. */
            executed.sort(Comparator.comparingLong(profiles::getCallCountOrZero).reversed().thenComparing(HostedMethod::getQualifiedName));
        }
        unobserved.sort(Comparator.comparing(HostedMethod::getQualifiedName));
        hot = executed.size();
        cold = unobserved.size();

        List<HostedMethod> ordered = new ArrayList<>(executed.size() + unobserved.size());
        ordered.addAll(executed);
        ordered.addAll(unobserved);
        return ordered;
    }

    public String summary() {
        return "Crucible: code section ordered by " + (byStartupOrder ? "startup sequence" : "call count") + ", " +
                        hot + " observed methods first" + (byStartupOrder ? " (" + ordered + " in recorded startup order)" : "") +
                        ", " + cold + " never observed after them.";
    }
}
