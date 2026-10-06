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

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.MapCursor;

import com.oracle.svm.core.crucible.CrucibleOptions;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.ScheduleResult;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.BinaryNode;
import jdk.graal.compiler.nodes.calc.UnaryNode;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.extended.FixedValueAnchorNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.schedule.SchedulePhase;
import jdk.graal.compiler.phases.schedule.SchedulePhase.SchedulingStrategy;

/**
 * Moves arithmetic that only rarely taken paths use onto those paths.
 * <p>
 * A value computed without side effects floats, and is scheduled where all its uses meet. When
 * control flow duplication has copied the code after some branches, a value that one rarely taken
 * path computed can have a use in every copy of that path, and the place they all meet is above
 * the branches: BranchBench computed a helper's fully unrolled loop on every iteration where it
 * was needed on one in a thousand. Where the blocks that use such a value run together far less
 * often than the block it is scheduled in, each of them gets its own copy of the value, and of
 * what only the value uses, and each copy is scheduled with its uses.
 */
public final class CrucibleSinkPhase extends BasePhase<CoreProviders> {

    public static final AtomicLong VALUES_SUNK = new AtomicLong();
    public static final AtomicLong NODES_COPIED = new AtomicLong();

    /** The blocks that use a value must run at most this share as often as the one it is in. */
    private static final double MAX_USE_SHARE = 0.25;
    /** Most nodes copied for one value into one block. */
    private static final int MAX_SUBTREE = 2000;

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        for (int round = 0; round < 4; round++) {
            if (!sinkOnce(graph)) {
                return;
            }
        }
    }

    private static boolean isPure(Node node) {
        return node instanceof BinaryNode || node instanceof UnaryNode;
    }

    /** The value and the pure nodes below it that nothing outside them uses. */
    private static List<Node> ownSubtree(ValueNode root) {
        EconomicSet<Node> owned = EconomicSet.create(Equivalence.IDENTITY);
        List<Node> order = new ArrayList<>();
        owned.add(root);
        order.add(root);
        boolean added = true;
        while (added && order.size() <= MAX_SUBTREE) {
            added = false;
            for (int i = 0; i < order.size(); i++) {
                for (Node input : order.get(i).inputs()) {
                    if (owned.contains(input) || !isPure(input)) {
                        continue;
                    }
                    boolean onlyOwned = true;
                    for (Node usage : input.usages()) {
                        if (!owned.contains(usage)) {
                            onlyOwned = false;
                            break;
                        }
                    }
                    if (onlyOwned) {
                        owned.add(input);
                        order.add(input);
                        added = true;
                    }
                }
            }
        }
        return order;
    }

    private static boolean sinkOnce(StructuredGraph graph) {
        SchedulePhase.runWithoutContextOptimizations(graph, SchedulingStrategy.LATEST_OUT_OF_LOOPS, true);
        ScheduleResult schedule = graph.getLastSchedule();
        boolean changed = false;
        EconomicSet<Node> touched = EconomicSet.create(Equivalence.IDENTITY);
        for (Node node : graph.getNodes().snapshot()) {
            if (!node.isAlive() || touched.contains(node) || !isPure(node) || node.usages().count() < 2) {
                continue;
            }
            ValueNode value = (ValueNode) node;
            HIRBlock home = schedule.blockFor(value);
            if (home == null) {
                continue;
            }
            /* The block each use needs the value in: a phi needs it at the end of its predecessor. */
            EconomicMap<HIRBlock, List<Node>> usesByBlock = EconomicMap.create(Equivalence.IDENTITY);
            boolean usable = true;
            for (Node usage : value.usages()) {
                HIRBlock block;
                if (usage instanceof PhiNode phi) {
                    if (countInputs(phi, value) != 1) {
                        usable = false;
                        break;
                    }
                    block = schedule.blockFor(phi.merge().phiPredecessorAt(indexOf(phi, value)));
                } else {
                    block = schedule.blockFor(usage);
                }
                if (block == null || block == home) {
                    usable = false;
                    break;
                }
                List<Node> uses = usesByBlock.get(block);
                if (uses == null) {
                    uses = new ArrayList<>();
                    usesByBlock.put(block, uses);
                }
                uses.add(usage);
            }
            if (!usable || usesByBlock.size() < 2) {
                continue;
            }
            double useFrequency = 0;
            for (HIRBlock block : usesByBlock.getKeys()) {
                useFrequency += block.getRelativeFrequency();
            }
            if (useFrequency > MAX_USE_SHARE * home.getRelativeFrequency()) {
                continue;
            }
            List<Node> subtree = ownSubtree(value);
            if (subtree.size() < CrucibleOptions.CrucibleSinkMinNodes.getValue() || subtree.size() > MAX_SUBTREE) {
                continue;
            }
            /*
             * Copies of the same arithmetic on the same inputs are one value to value numbering,
             * which would make them one node again, scheduled where it was. Each copy reads its
             * inputs through an anchor at the start of its own block instead: the anchors differ,
             * and the copy cannot float above them.
             */
            EconomicSet<Node> owned = EconomicSet.create(Equivalence.IDENTITY);
            owned.addAll(subtree);
            List<ValueNode> outside = new ArrayList<>();
            for (Node member : subtree) {
                for (Node input : member.inputs()) {
                    if (!owned.contains(input) && !(input instanceof ConstantNode) && input instanceof ValueNode valueInput && !outside.contains(valueInput)) {
                        outside.add(valueInput);
                    }
                }
            }
            for (MapCursor<HIRBlock, List<Node>> cursor = usesByBlock.getEntries(); cursor.advance();) {
                List<Node> uses = cursor.getValue();
                AbstractBeginNode begin = cursor.getKey().getBeginNode();
                EconomicMap<Node, Node> anchored = EconomicMap.create(Equivalence.IDENTITY);
                for (ValueNode input : outside) {
                    FixedValueAnchorNode anchor = graph.add(new FixedValueAnchorNode(input));
                    graph.addAfterFixed(begin, anchor);
                    anchored.put(input, anchor);
                }
                EconomicMap<Node, Node> copies = graph.addDuplicates(subtree, graph, subtree.size(), anchored);
                Node copy = copies.get(value);
                for (Node usage : uses) {
                    usage.replaceAllInputs(value, copy);
                }
                NODES_COPIED.addAndGet(subtree.size());
            }
            touched.addAll(subtree);
            GraphUtil.killWithUnusedFloatingInputs(value);
            VALUES_SUNK.incrementAndGet();
            changed = true;
        }
        return changed;
    }

    private static int countInputs(PhiNode phi, ValueNode value) {
        int n = 0;
        for (int i = 0; i < phi.valueCount(); i++) {
            if (phi.valueAt(i) == value) {
                n++;
            }
        }
        return n;
    }

    private static int indexOf(PhiNode phi, ValueNode value) {
        for (int i = 0; i < phi.valueCount(); i++) {
            if (phi.valueAt(i) == value) {
                return i;
            }
        }
        throw new IllegalStateException();
    }
}
