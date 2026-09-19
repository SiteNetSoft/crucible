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
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.vm.ci.meta.JavaKind;
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
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.loop.CountedLoopInfo;
import jdk.graal.compiler.nodes.type.StampTool;
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
 * The bounds may be constants or anything computed outside the loop, an array length above all. Run
 * a second time after lowering, when the bounds checks Java puts on every array access have become
 * tests of their own, {@code i + c |<| a.length}, the phase takes those as well.
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

    /** Which loops were split, for the build to report. */
    public static final java.util.concurrent.ConcurrentLinkedQueue<String> SPLITS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final ThreadLocal<String> TIER = ThreadLocal.withInitial(() -> "");

    private static boolean reject(String why) {
        if (why.startsWith("note:")) {
            return reject0(TIER.get() + why);
        }
        return reject0(why);
    }

    private static boolean reject0(String why) {
        REJECTED.computeIfAbsent(why, k -> new AtomicLong()).incrementAndGet();
        return false;
    }

    private final CanonicalizerPhase canonicalizer = CanonicalizerPhase.create();
    /**
     * Whether this instance runs after lowering. There it may split the middle loop an earlier
     * instance left behind, because the bounds checks it is after did not exist as tests before.
     */
    private final boolean afterLowering;

    public CrucibleLoopRangeSplitPhase(boolean afterLowering) {
        this.afterLowering = afterLowering;
    }

    /** One check inside the loop and the direction it takes across the whole middle range. */
    private record FoldableCheck(IfNode check, boolean outcome) {
    }

    /** A bound on the induction variable: {@code base + offset}, or just {@code offset} if there is no base. */
    private record Bound(ValueNode base, long offset) {
    }

    /**
     * The half-open range of induction variable values over which every chosen check is decided:
     * at least every lower bound, below every upper bound.
     */
    private static final class Range {
        final List<Bound> lower = new ArrayList<>();
        final List<Bound> upper = new ArrayList<>();
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

    private boolean trySplit(Loop loop) {
        TIER.set(afterLowering ? "[mid] " : "[high] ");
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
                collect(loop, check, inductionVariable, minimumBias, range, checks);
            } else if (node instanceof jdk.graal.compiler.nodes.memory.FixedAccessNode access && access.getGuard() != null) {
                reject("note: memory access guarded by " + access.getGuard().getClass().getSimpleName());
            } else if (node instanceof jdk.graal.compiler.nodes.GuardNode guard && guard.getCondition() instanceof IntegerBelowNode) {
                reject("note: bounds check is still a floating guard");
            } else if (node instanceof jdk.graal.compiler.nodes.FixedGuardNode guard && guard.getCondition() instanceof IntegerBelowNode) {
                reject("note: bounds check is still a fixed guard");
            } else if (node instanceof jdk.graal.compiler.nodes.java.AccessIndexedNode) {
                reject("note: array access not lowered yet");
            }
        }
        if (checks.size() < CrucibleOptions.CrucibleLoopRangeSplitMinimumChecks.getValue()) {
            return reject("too few lopsided checks on the induction variable (" + Math.min(checks.size(), 9) + ")");
        }
        /*
         * The bounds are worked out in exact arithmetic, the checks run in 32 bits. They agree
         * only while iv + c does not wrap, so keep the middle range to where it cannot.
         */
        if (range.smallestOffset < 0) {
            range.lower.add(new Bound(null, Integer.MIN_VALUE - range.smallestOffset));
        }
        if (range.largestOffset > 0) {
            range.upper.add(new Bound(null, Integer.MAX_VALUE - range.largestOffset + 1));
        }
        if (constantOnly(range.lower) && constantOnly(range.upper) && !range.lower.isEmpty() && !range.upper.isEmpty() &&
                        range.lower.stream().mapToLong(Bound::offset).max().getAsLong() >= range.upper.stream().mapToLong(Bound::offset).min().getAsLong()) {
            return reject("the checks never all hold at once");
        }

        StructuredGraph graph = loop.loopBegin().graph();
        List<Bound> lower = hoisted(loop, range.lower);
        List<Bound> upper = hoisted(loop, range.upper);
        IfNode originalLimitTest = counted.getLimitTest();
        ValueNode originalLimit = counted.getLimit();

        PreMainPostResult split = LoopTransformations.insertPrePostLoops(loop);

        /*
         * The original loop is now the first of the three and already runs to a rewritten limit,
         * one iteration past its start. Run it to the first value at which the checks hold instead.
         */
        if (!lower.isEmpty()) {
            CompareNode firstLoopTest = (CompareNode) loop.counted().getLimitTest().condition();
            ValueNode firstLoopLimit = loop.counted().getLimit();
            firstLoopTest.replaceFirstInput(firstLoopLimit, graph.addOrUniqueWithInputs(limit(lower, true, originalLimit)));
        }
        if (!range.upper.isEmpty()) {
            IfNode middleLimitTest = split.getMainLoopFragment().getDuplicatedNode(originalLimitTest);
            CompareNode middleTest = (CompareNode) middleLimitTest.condition();
            middleTest.replaceFirstInput(originalLimit, graph.addOrUniqueWithInputs(limit(upper, false, originalLimit)));
        }
        for (FoldableCheck foldable : checks) {
            IfNode middleCheck = split.getMainLoopFragment().getDuplicatedNode(foldable.check());
            if (middleCheck != null && middleCheck.isAlive()) {
                middleCheck.setCondition(LogicConstantNode.forBoolean(foldable.outcome(), graph));
                CHECKS_FOLDED.incrementAndGet();
            }
        }
        /*
         * The three loops come marked as the product of partial unrolling, and the vectorizer for
         * one leaves such loops alone. The middle loop has not been unrolled, and with its checks
         * gone it is the loop most worth vectorizing, so say so.
         */
        split.getMainLoop().setSimpleLoop();
        LOOPS_SPLIT.incrementAndGet();
        if (SPLITS.size() < 64) {
            SPLITS.add((graph.method() == null ? "?" : graph.method().format("%h.%n")) + (afterLowering ? " after lowering, " : " before lowering, ") + checks.size() + " checks, f=" +
                            (long) loop.loopBegin().loopOrigFrequency());
        }
        return true;
    }

    private static boolean constantOnly(List<Bound> bounds) {
        return bounds.stream().allMatch(b -> b.base() == null);
    }

    /**
     * The limit a loop should run to: the largest of {@code bounds} if they are lower bounds, the
     * smallest if they are upper bounds, and in either case no further than the loop went before.
     * <p>
     * Worked out in 64 bits, where a length plus a small constant cannot wrap, then brought back
     * into 32. Nothing is lost at the top, the result being no larger than the original limit.
     * At the bottom a value below the smallest int becomes the smallest int, and a counter is
     * below neither, so the loop it limits does not run in either case.
     */
    private static ValueNode limit(List<Bound> bounds, boolean largest, ValueNode originalLimit) {
        ValueNode chosen = null;
        for (Bound bound : bounds) {
            ValueNode value = ConstantNode.forLong(bound.offset());
            if (bound.base() != null) {
                value = AddNode.add(SignExtendNode.create(bound.base(), 64, NodeView.DEFAULT), value, NodeView.DEFAULT);
            }
            chosen = chosen == null ? value : largest ? larger(chosen, value) : smaller(chosen, value);
        }
        ValueNode capped = smaller(chosen, SignExtendNode.create(originalLimit, 64, NodeView.DEFAULT));
        return NarrowNode.create(larger(capped, ConstantNode.forLong(Integer.MIN_VALUE)), 32, NodeView.DEFAULT);
    }

    private static ValueNode smaller(ValueNode a, ValueNode b) {
        return ConditionalNode.create(IntegerLessThanNode.create(a, b, NodeView.DEFAULT), a, b, NodeView.DEFAULT);
    }

    private static ValueNode larger(ValueNode a, ValueNode b) {
        return ConditionalNode.create(IntegerLessThanNode.create(a, b, NodeView.DEFAULT), b, a, NodeView.DEFAULT);
    }

    /**
     * Only the plainest counted loop: counting up by one to a signed exclusive limit, with no loops
     * inside it, and hot according to a profile rather than to a guess. Any number of back edges
     * will do, since a head-counted loop hands its phis on at the exit and the machinery never
     * looks at them, and so will further exits, which leave whichever of the three loops they
     * are taken from.
     */
    private boolean isCandidate(Loop loop) {
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
        if (!(counted.getCountedExit() instanceof jdk.graal.compiler.nodes.LoopExitNode)) {
            return reject("counted exit is not a loop exit");
        }
        if (loop.loopBegin().loopExits().count() != 1 && !CrucibleOptions.CrucibleLoopRangeSplitManyExits.getValue()) {
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
     * Records {@code check} if it compares the induction variable, plus a constant, against
     * something that does not change inside the loop, and the profile saw it go one way nearly
     * always. Narrows {@code range} to the values for which it does go that way.
     */
    private static void collect(Loop loop, IfNode check, ValueNode inductionVariable, double minimumBias, Range range, List<FoldableCheck> checks) {
        if (!ProfileSource.isTrusted(check.getProfileData().getProfileSource())) {
            if (check.condition() instanceof IntegerBelowNode) {
                reject("note: |<| probability is " + check.getProfileData().getProfileSource());
            }
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
            if (leftOffset != null && isFixedInLoop(loop, lessThan.getY())) {
                /* iv + c < K holds exactly below K - c. */
                (outcome ? range.upper : range.lower).add(bound(lessThan.getY(), -leftOffset));
                range.sawOffset(leftOffset);
                checks.add(new FoldableCheck(check, outcome));
            } else if (rightOffset != null && isFixedInLoop(loop, lessThan.getX())) {
                /* K < iv + c holds exactly from K - c + 1. */
                (outcome ? range.lower : range.upper).add(bound(lessThan.getX(), -rightOffset + 1));
                range.sawOffset(rightOffset);
                checks.add(new FoldableCheck(check, outcome));
            }
        } else if (condition instanceof IntegerBelowNode below) {
            Long offset = offsetFrom(below.getX(), inductionVariable);
            if (!outcome) {
                reject("note: |<| biased to fail");
            } else if (offset == null) {
                reject("note: |<| index is not iv + c but " + below.getX().getClass().getSimpleName());
            } else if (!isFixedInLoop(loop, below.getY())) {
                String why = below.getY().getClass().getSimpleName();
                if (below.getY() instanceof ArrayLengthNode length) {
                    why += !loop.isOutsideLoop(withoutChecks(length.array())) ? " of an array from inside the loop, " + length.array().getClass().getSimpleName() + " over " + withoutChecks(length.array()).getClass().getSimpleName() : " of an array that may be null";
                }
                reject("note: |<| length changes inside the loop, " + why);
            } else if (!(below.getY().stamp(NodeView.DEFAULT) instanceof IntegerStamp lengthStamp) || !lengthStamp.isPositive()) {
                reject("note: |<| length may be negative");
            }
            if (!outcome) {
                return;
            }
            if (offset != null && isFixedInLoop(loop, below.getY()) && below.getY().stamp(NodeView.DEFAULT) instanceof IntegerStamp stamp && stamp.isPositive()) {
                /* iv + c |<| K, with K not negative, holds exactly for 0 <= iv + c < K. */
                range.lower.add(new Bound(null, -offset));
                range.upper.add(bound(below.getY(), -offset));
                range.sawOffset(offset);
                checks.add(new FoldableCheck(check, true));
            }
        }
    }

    private static boolean isFixedInLoop(Loop loop, ValueNode value) {
        if (value.isJavaConstant()) {
            return true;
        }
        if (!(value.stamp(NodeView.DEFAULT) instanceof IntegerStamp stamp) || stamp.getBits() != 32) {
            return false;
        }
        return loop.isOutsideLoop(value) || isLengthOfFixedArray(loop, value);
    }

    /**
     * The length of an array is read where the array is used, which for an array used in a loop is
     * inside it, although neither the array nor its length changes there. Nor does the array
     * itself look as if it came from outside: the access checks it for null first, and what the
     * length is read from is the result of that check.
     */
    private static boolean isLengthOfFixedArray(Loop loop, ValueNode value) {
        return value instanceof ArrayLengthNode length && loop.isOutsideLoop(withoutChecks(length.array()));
    }

    private static ValueNode withoutChecks(ValueNode array) {
        ValueNode current = array;
        while (current instanceof PiNode pi) {
            current = pi.object();
        }
        return current;
    }

    /** Replaces lengths read inside the loop by the same length read once in front of it. */
    private static List<Bound> hoisted(Loop loop, List<Bound> bounds) {
        List<Bound> result = new ArrayList<>(bounds.size());
        for (Bound bound : bounds) {
            if (bound.base() instanceof ArrayLengthNode length && !loop.isOutsideLoop(length)) {
                result.add(new Bound(lengthAheadOf(loop, withoutChecks(length.array())), bound.offset()));
            } else {
                result.add(bound);
            }
        }
        return result;
    }

    /**
     * Reads the length of {@code array} in front of the loop. An array that may be null is tested
     * first and counts as having length zero. No index is below zero, so the middle loop then does
     * not run, and the loops either side of it, which still have every check they started with,
     * throw where the original would have.
     */
    private static ValueNode lengthAheadOf(Loop loop, ValueNode array) {
        StructuredGraph graph = loop.loopBegin().graph();
        FixedNode entry = loop.loopBegin().forwardEnd();
        if (StampTool.isPointerNonNull(array)) {
            ArrayLengthNode length = graph.add(new ArrayLengthNode(array));
            graph.addBeforeFixed(entry, length);
            return length;
        }
        FixedWithNextNode before = (FixedWithNextNode) entry.predecessor();
        BeginNode isNull = graph.add(new BeginNode());
        BeginNode notNull = graph.add(new BeginNode());
        EndNode nullEnd = graph.add(new EndNode());
        EndNode notNullEnd = graph.add(new EndNode());
        MergeNode merge = graph.add(new MergeNode());
        ValueNode checked = graph.addOrUniqueWithInputs(PiNode.create(array, ((AbstractObjectStamp) array.stamp(NodeView.DEFAULT)).asNonNull(), notNull));
        ArrayLengthNode length = graph.add(new ArrayLengthNode(checked));
        isNull.setNext(nullEnd);
        notNull.setNext(length);
        length.setNext(notNullEnd);
        merge.addForwardEnd(nullEnd);
        merge.addForwardEnd(notNullEnd);
        IfNode test = graph.add(new IfNode(graph.addOrUniqueWithInputs(IsNullNode.create(array)), isNull, notNull, BranchProbabilityData.injected(0.001)));
        before.setNext(test);
        merge.setNext(entry);
        return graph.addOrUnique(new ValuePhiNode(StampFactory.forKind(JavaKind.Int), merge, ConstantNode.forInt(0, graph), length));
    }

    private static Bound bound(ValueNode value, long offset) {
        return value.isJavaConstant() ? new Bound(null, value.asJavaConstant().asLong() + offset) : new Bound(value, offset);
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
