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
import com.oracle.svm.core.UninterruptibleAnnotationUtils;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.SubstratePolicyFactory;

import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.common.priorityinline.Expander;
import jdk.graal.compiler.phases.common.priorityinline.InliningMath;
import jdk.graal.compiler.phases.common.priorityinline.Inliner;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CutoffNode;
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
    /** Inlines allowed because the method being compiled is where the run spent its time. */
    public static final AtomicLong HOT_ROOT_INLINES = new AtomicLong();

    /** Callees a cold method was not allowed to look into at all. */
    public static final AtomicLong COLD_EXPANSIONS_REFUSED = new AtomicLong();

    @Override
    public Expander.Policy createExpanderPolicy(OptionValues options, HighTierContext context) {
        return new ColdAwareExpanderPolicy();
    }

    /**
     * Keeps a cold method from exploring its callees in the first place.
     * <p>
     * Declining to inline into cold methods turned out not to be enough. The inliner's cost and
     * benefit analysis marks whole subtrees as inlined without asking the policy call by call, so
     * that a method the run never entered still came out at 26 KB with a collection library
     * inlined into it, where a compiler that treats it as cold emits a 40 byte call. What is never
     * expanded cannot be inlined. Callees of a few bytecodes are still looked at: a getter inlined
     * is smaller than the call to it.
     */
    private static final class ColdAwareExpanderPolicy extends SubstrateExpanderPolicy {
        @Override
        public boolean shouldExpand(CutoffNode node) {
            if (!node.isForceInlined() && isCold(node.callTree().root().getReadonlySubgraph().method()) &&
                            node.targetMethod().getCodeSize() > CrucibleOptions.CrucibleColdInlineMaximumBytecodes.getValue()) {
                COLD_EXPANSIONS_REFUSED.incrementAndGet();
                return false;
            }
            return super.shouldExpand(node);
        }
    }

    /**
     * Cold is not only never entered. What ran a few times and did next to nothing, which is most
     * of what runs while a program starts, gains nothing from being compiled for speed either.
     */
    static boolean isCold(ResolvedJavaMethod method) {
        if (!(PGOProfilesLookup.singletonOrNull() instanceof CrucibleProfilesLookup profiles) || !(method instanceof HostedMethod hosted)) {
            return false;
        }
        /*
         * How much the garbage collector runs depends on how long the program does, not on what the
         * program is. A short recording never compacts the heap, a long run does all the time, and
         * compiling the compaction cold cost 4% of a benchmark that spends half its time collecting.
         */
        if (hosted.getDeclaringClass().toJavaName().startsWith("com.oracle.svm.core.genscavenge.")) {
            return false;
        }
        long calls = profiles.getCallCountOrZero(hosted);
        if (calls == 0) {
            /*
             * Uninterruptible code is not counted, so for it no count means no information. It
             * includes the garbage collector and the memory copying, some of the hottest code
             * there is, and was 4% of a benchmark when this treated it as cold.
             */
            return !UninterruptibleAnnotationUtils.isUninterruptible(hosted) && !profiles.ranAnywhere(hosted);
        }
        return calls <= CrucibleOptions.CrucibleColdMaximumCalls.getValue() && profiles.workShare(hosted) < CrucibleOptions.CrucibleColdMaximumWorkShare.getValue();
    }

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
            if (isWorthMoreInHotRoot(node, expansionRound)) {
                HOT_ROOT_INLINES.incrementAndGet();
                return true;
            }
            if (isHotCallee(node)) {
                HOT_INLINES_ALLOWED.incrementAndGet();
                return true;
            }
            return false;
        }

        /**
         * Spends more on a method where the run spent its time. The threshold a call has to clear
         * rises with what has already been inlined into the root, the same for every root, and
         * that is the right caution for the thousands of methods a run barely touches. In the few
         * it lives in, a larger body is cheap next to what deeper inlining there returns.
         */
        private static boolean isWorthMoreInHotRoot(CallTreeNode node, int expansionRound) {
            double boost = CrucibleOptions.CrucibleHotRootInlineBoost.getValue();
            if (boost <= 1.0 || !(PGOProfilesLookup.singletonOrNull() instanceof CrucibleProfilesLookup profiles)) {
                return false;
            }
            if (!(node.callTree().root().getReadonlySubgraph().method() instanceof HostedMethod root) || profiles.workShare(root) < CrucibleOptions.CrucibleHotRootShare.getValue()) {
                return false;
            }
            return node.getCostBenefit().relativeBenefit() > InliningMath.defaultInliningThreshold(node, expansionRound) / boost;
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
            return isCold(hostedRoot);
        }
    }
}
