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

import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.SubstratePolicyFactory;

import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.Inliner;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.DontInlineCause;

import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Stops inlining into methods the profile never saw run.
 * <p>
 * A profiled image executes a small fraction of what it contains -- a few hundred methods out of
 * several thousand -- and inlining into the rest buys nothing at run time while costing code size
 * in every one of them. This is the mechanism behind a profile-guided build producing a smaller
 * binary, and the community edition has no such policy: it inlines by the same rules everywhere.
 * <p>
 * Methods the profile does say ran keep the community edition's behaviour exactly, so this can
 * only remove work from code the recorded workload did not reach.
 */
public final class CruciblePolicyFactory extends SubstratePolicyFactory {

    /** Decisions we changed: the inliner wanted to inline, and the method is cold. */
    public static final AtomicLong COLD_INLINES_SUPPRESSED = new AtomicLong();
    /** Cold-method decisions the inliner was going to decline anyway. */
    public static final AtomicLong COLD_INLINES_ALREADY_DECLINED = new AtomicLong();
    /** Inlines allowed because the profile shows the callee running often, that the budget refused. */
    public static final AtomicLong HOT_INLINES_ALLOWED = new AtomicLong();

    @Override
    public SubstrateInlinerPolicy createInlinerPolicy(OptionValues options) {
        return new ColdAwarePolicy();
    }

    /** Priority must differ from the factory it replaces, which service loading distinguishes by. */
    @Override
    public int priority() {
        return super.priority() + 1;
    }

    private static final class ColdAwarePolicy extends SubstrateInlinerPolicy {

        @Override
        public boolean shouldInline(CallTreeNode node, int expansionRound) {
            boolean wouldInline = super.shouldInline(node, expansionRound);
            if (!isIntoColdMethod(node)) {
                return wouldInline;
            }
            if (!wouldInline) {
                /*
                 * Counted separately: the inliner already declines most work inside cold methods,
                 * because the profile has already told it they are cold. Only the other branch
                 * represents code this policy actually removes.
                 */
                COLD_INLINES_ALREADY_DECLINED.incrementAndGet();
                return false;
            }
            node.setDontInlineCause(DontInlineCause.CostBenefit);
            COLD_INLINES_SUPPRESSED.incrementAndGet();
            return false;
        }

        /**
         * Lets a measurably hot callee through a budget that would otherwise refuse it.
         * <p>
         * Nothing in the community edition consults profiled call counts when deciding what to
         * inline: the budget is the same whether a callee runs once or ten million times. A
         * profile is exactly the information that distinguishes those cases, and inlining a hot
         * callee is where most of what profile-guided optimisation is worth comes from.
         */
        @Override
        protected boolean isWithinBudget(CallTreeNode node, int expansionRound) {
            if (super.isWithinBudget(node, expansionRound)) {
                return true;
            }
            if (isHotCallee(node)) {
                HOT_INLINES_ALLOWED.incrementAndGet();
                return true;
            }
            return false;
        }

        /** Whether the profile saw this callee take a meaningful share of the recorded run. */
        private static boolean isHotCallee(CallTreeNode node) {
            if (!(PGOProfilesLookup.singletonOrNull() instanceof CrucibleProfilesLookup profiles)) {
                return false;
            }
            if (!(node.targetMethod() instanceof HostedMethod callee)) {
                return false;
            }
            double share = profiles.selfTimeShare(callee);
            return share > 0 && share >= CrucibleOptions.CrucibleHotInlineShare.getValue();
        }

        /** Whether this decision is about code inside a method the profile never saw execute. */
        private static boolean isIntoColdMethod(CallTreeNode node) {
            if (!(PGOProfilesLookup.singletonOrNull() instanceof CrucibleProfilesLookup profiles)) {
                return false;
            }
            ResolvedJavaMethod root = node.callTree().root().getReadonlySubgraph().method();
            if (!(root instanceof HostedMethod hostedRoot)) {
                return false;
            }
            /*
             * Only suppress where the profile is informative. A method absent from the profile
             * because the workload never reached it is cold; one that ran is left alone.
             */
            return profiles.getCallCountOrZero(hostedRoot) == 0;
        }
    }
}
