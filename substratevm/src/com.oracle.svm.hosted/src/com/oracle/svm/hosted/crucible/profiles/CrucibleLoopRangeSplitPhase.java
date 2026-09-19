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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.crucible.CrucibleOptions;

import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.loop.phases.LoopTransformations;
import jdk.graal.compiler.loop.phases.LoopTransformations.PreMainPostResult;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ProfileData.ProfileSource;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerLessThanNode;
import jdk.graal.compiler.nodes.loop.CountedLoopInfo;
import jdk.graal.compiler.nodes.loop.InductionVariable;
import jdk.graal.compiler.nodes.loop.Loop;
import jdk.graal.compiler.nodes.loop.LoopsData;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.util.EconomicSetNodeEventListener;
import jdk.graal.compiler.graph.Graph;

/**
 * Splits the iteration range of a hot counted loop so that its middle part runs without the checks
 * the profile says almost never fail.
 * <p>
 * A loop over an array with a stencil -- {@code a[i - 1] + a[i] + a[i + 1]} guarded by
 * {@code i - 1 >= 0 && i + 1 < n} -- pays for the guards on every iteration although they only ever
 * fail on the first and the last. They depend on the induction variable, so unswitching cannot
 * move them out. What can be done is to run the loop three times over: up to the first value at
 * which every guard holds, then over the range where they all hold, then over the rest. In the
 * middle loop the guards are true by construction and are folded away; the outer two keep them and
 * run a handful of iterations.
 * <p>
 * Without a profile this is a poor trade, three copies of every loop in the program for checks
 * that may well fail half the time. The profile is what says which loops are hot and which of
 * their checks are lopsided enough for the middle loop to be where the time goes.
 * <p>
 * The three-loop structure itself is the compiler's own, {@link LoopTransformations#insertPrePostLoops},
 * built for partial unrolling. This phase only chooses the two limits and folds the checks.
 */
public final class CrucibleLoopRangeSplitPhase extends BasePhase<CoreProviders> {

    public static final AtomicLong LOOPS_CONSIDERED = new AtomicLong();
    public static final AtomicLong LOOPS_SPLIT = new AtomicLong();
    public static final AtomicLong CHECKS_FOLDED = new AtomicLong();
    /** Why counted loops were passed over, so that a build can say what stood in the way. */
    public static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> REJECTED = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean reject(String why) {
        REJECTED.computeIfAbsent(why, k -> new AtomicLong()).incrementAndGet();
        return false;
    }

    private final CanonicalizerPhase canonicalizer = CanonicalizerPhase.create();

    /** One check inside the loop and the direction it takes across the whole middle range. */
    private record FoldableCheck(IfNode check, boolean outcome) {
    }

    /** The half-open range of induction variable values over which every chosen check is decided. */
    private static final class Range {
        long low = Long.MIN_VALUE;
        long high = Long.MAX_VALUE;
        /** Extremes of the constants added to the induction variable in the chosen checks. */
        long smallestOffset;
        long largestOffset;

        void sawOffset(long offset) {
            smallestOffset = Math.min(smallestOffset, offset);
            largestOffset = Math.max(largestOffset, offset);
        }
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        if (!graph.hasLoops()) {
            return;
        }
        EconomicSetNodeEventListener listener = new EconomicSetNodeEventListener();
        boolean changed = true;
        /* Every split invalidates the loop data, so look again after each one. */
        for (int round = 0; changed && round < 16; round++) {
            changed = false;
            try (Graph.NodeEventScope scope = graph.trackNodeEvents(listener)) {
                LoopsData loops = context.getLoopsDataProvider().getLoopsData(graph);
                loops.detectCountedLoops();
                for (Loop loop : loops.countedLoops()) {
                    if (trySplit(loop)) {
                        changed = true;
                        break;
                    }
                }
                loops.deleteUnusedNodes();
            }
            if (!listener.getNodes().isEmpty()) {
                canonicalizer.applyIncremental(graph, context, listener.getNodes());
                listener.getNodes().clear();
            }
        }
    }

    private static boolean trySplit(Loop loop) {
        if (!isCandidate(loop)) {
            return false;
        }
        LOOPS_CONSIDERED.incrementAndGet();
        CountedLoopInfo counted = loop.counted();
        ValueNode inductionVariable = counted.getLimitCheckedIV().valueNode();

        Range range = new Range();
        List<FoldableCheck> checks = new ArrayList<>();
        double minimumBias = CrucibleOptions.CrucibleLoopRangeSplitMinimumBias.getValue();
        for (Node node : loop.inside().nodes()) {
            if (node instanceof IfNode check && check != counted.getLimitTest()) {
                collect(check, inductionVariable, minimumBias, range, checks);
            }
        }
        if (checks.size() < CrucibleOptions.CrucibleLoopRangeSplitMinimumChecks.getValue() || range.low >= range.high) {
            return reject("too few lopsided checks on the induction variable (" + Math.min(checks.size(), 9) + ")");
        }
        if (range.low != Long.MIN_VALUE && range.low != (int) range.low || range.high != Long.MAX_VALUE && range.high != (int) range.high) {
            return reject("bounds do not fit the counter");
        }
        /*
         * The bounds were worked out in exact arithmetic, the checks run in 32 bits. They agree
         * only if iv + c cannot wrap anywhere in the middle range.
         */
        IntegerStamp counterStamp = (IntegerStamp) inductionVariable.stamp(NodeView.DEFAULT);
        long smallestValue = Math.max(range.low, counterStamp.lowerBound());
        long largestValue = Math.min(range.high - 1, counterStamp.upperBound());
        if (smallestValue + range.smallestOffset < Integer.MIN_VALUE || largestValue + range.largestOffset > Integer.MAX_VALUE) {
            return reject("a check could wrap around");
        }

        StructuredGraph graph = loop.loopBegin().graph();
        IfNode originalLimitTest = counted.getLimitTest();
        ValueNode originalLimit = counted.getLimit();

        PreMainPostResult split = LoopTransformations.insertPrePostLoops(loop);

        /*
         * The original loop is now the first of the three and already runs to a rewritten limit,
         * one iteration past its start. Run it to the first value at which the checks hold instead.
         */
        if (range.low != Long.MIN_VALUE) {
            CompareNode firstLoopTest = (CompareNode) loop.counted().getLimitTest().condition();
            ValueNode firstLoopLimit = loop.counted().getLimit();
            firstLoopTest.replaceFirstInput(firstLoopLimit, graph.addOrUniqueWithInputs(smaller(constant(range.low, originalLimit), originalLimit)));
        }
        if (range.high != Long.MAX_VALUE) {
            IfNode middleLimitTest = split.getMainLoopFragment().getDuplicatedNode(originalLimitTest);
            CompareNode middleTest = (CompareNode) middleLimitTest.condition();
            middleTest.replaceFirstInput(originalLimit, graph.addOrUniqueWithInputs(smaller(constant(range.high, originalLimit), originalLimit)));
        }
        for (FoldableCheck foldable : checks) {
            IfNode middleCheck = split.getMainLoopFragment().getDuplicatedNode(foldable.check());
            if (middleCheck != null && middleCheck.isAlive()) {
                middleCheck.setCondition(LogicConstantNode.forBoolean(foldable.outcome(), graph));
                CHECKS_FOLDED.incrementAndGet();
            }
        }
        LOOPS_SPLIT.incrementAndGet();
        return true;
    }

    private static ValueNode constant(long value, ValueNode like) {
        return ConstantNode.forIntegerStamp(like.stamp(NodeView.DEFAULT), value);
    }

    private static ValueNode smaller(ValueNode a, ValueNode b) {
        return ConditionalNode.create(IntegerLessThanNode.create(a, b, NodeView.DEFAULT), a, b, NodeView.DEFAULT);
    }

    /**
     * Only the plainest counted loop: counting up by one to a signed exclusive limit, with a single
     * exit and no loops inside it (any number of back edges: a head-counted loop hands its phis on at
     * the exit, so the machinery never looks at them), not already one of a pre, main and post trio, and hot according
     * to a profile rather than to a guess.
     */
    private static boolean isCandidate(Loop loop) {
        if (!loop.isCounted()) {
            return false;
        }
        if (!ProfileSource.isTrusted(loop.localFrequencySource()) || loop.localLoopFrequency() < CrucibleOptions.CrucibleLoopRangeSplitMinimumFrequency.getValue()) {
            return false;
        }
        if (!loop.loopBegin().isSimpleLoop()) {
            return reject("already pre, main or post");
        }
        if (!loop.getCFGLoop().getChildren().isEmpty()) {
            return reject("has inner loops");
        }
        CountedLoopInfo counted = loop.counted();
        InductionVariable iv = counted.getLimitCheckedIV();
        if (!(iv.valueNode() instanceof ValuePhiNode phi) || phi.merge() != loop.loopBegin()) {
            return reject("induction variable is not the loop phi");
        }
        if (!iv.isConstantStride() || iv.constantStride() != 1 || counted.getDirection() != InductionVariable.Direction.Up) {
            return reject("stride is not +1");
        }
        if (counted.isInverted() || counted.isLimitIncluded() || counted.isUnsignedCheck() || !(counted.getLimitTest().condition() instanceof IntegerLessThanNode)) {
            return reject("limit test is not a plain signed <");
        }
        if (!(iv.valueNode().stamp(NodeView.DEFAULT) instanceof IntegerStamp stamp) || stamp.getBits() != 32) {
            return reject("not a 32-bit counter");
        }
        if (loop.loopBegin().loopExits().count() != 1) {
            return reject("more than one exit");
        }
        if (LoopTransformations.countedLoopExitConditionHasMultipleUsages(loop)) {
            return reject("exit condition is shared");
        }
        if (loop.inside().nodes().count() > CrucibleOptions.CrucibleLoopRangeSplitMaximumSize.getValue()) {
            return reject("too large");
        }
        if (!loop.canDuplicateLoop()) {
            return reject("cannot be duplicated");
        }
        return true;
    }

    /**
     * Records {@code check} if it compares the induction variable, plus a constant, against a
     * constant, and the profile saw it go one way nearly always. Narrows {@code range} to the
     * values for which it does go that way.
     */
    private static void collect(IfNode check, ValueNode inductionVariable, double minimumBias, Range range, List<FoldableCheck> checks) {
        if (!ProfileSource.isTrusted(check.getProfileData().getProfileSource())) {
            return;
        }
        double taken = check.getTrueSuccessorProbability();
        boolean outcome;
        if (taken >= minimumBias) {
            outcome = true;
        } else if (taken <= 1.0 - minimumBias) {
            outcome = false;
        } else {
            return;
        }
        LogicNode condition = check.condition();
        if (condition instanceof IntegerLessThanNode lessThan) {
            Long leftOffset = offsetFrom(lessThan.getX(), inductionVariable);
            Long rightOffset = offsetFrom(lessThan.getY(), inductionVariable);
            if (leftOffset != null && lessThan.getY().isJavaConstant()) {
                /* iv + c < K holds exactly below K - c. */
                long bound = lessThan.getY().asJavaConstant().asLong() - leftOffset;
                range.sawOffset(leftOffset);
                if (outcome) {
                    range.high = Math.min(range.high, bound);
                } else {
                    range.low = Math.max(range.low, bound);
                }
                checks.add(new FoldableCheck(check, outcome));
            } else if (rightOffset != null && lessThan.getX().isJavaConstant()) {
                /* K < iv + c holds exactly from K - c + 1. */
                long bound = lessThan.getX().asJavaConstant().asLong() - rightOffset + 1;
                range.sawOffset(rightOffset);
                if (outcome) {
                    range.low = Math.max(range.low, bound);
                } else {
                    range.high = Math.min(range.high, bound);
                }
                checks.add(new FoldableCheck(check, outcome));
            }
        } else if (condition instanceof IntegerBelowNode below && outcome) {
            Long offset = offsetFrom(below.getX(), inductionVariable);
            if (offset != null && below.getY().isJavaConstant() && below.getY().asJavaConstant().asLong() >= 0) {
                /* iv + c |<| K, with K not negative, holds exactly for 0 <= iv + c < K. */
                range.sawOffset(offset);
                range.low = Math.max(range.low, -offset);
                range.high = Math.min(range.high, below.getY().asJavaConstant().asLong() - offset);
                checks.add(new FoldableCheck(check, true));
            }
        }
    }

    /** The constant {@code c} if {@code value} is {@code iv + c}, zero for {@code iv} itself. */
    private static Long offsetFrom(ValueNode value, ValueNode inductionVariable) {
        if (value == inductionVariable) {
            return 0L;
        }
        if (value instanceof AddNode add) {
            if (add.getX() == inductionVariable && add.getY().isJavaConstant()) {
                return add.getY().asJavaConstant().asLong();
            }
            if (add.getY() == inductionVariable && add.getX().isJavaConstant()) {
                return add.getX().asJavaConstant().asLong();
            }
        }
        return null;
    }
}
