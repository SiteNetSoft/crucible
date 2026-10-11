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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractDeoptimizeNode;
import jdk.graal.compiler.nodes.AbstractEndNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopEndNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ReturnNode;
import jdk.graal.compiler.nodes.ShortCircuitOrNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.UnwindNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.WithExceptionNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.calc.BinaryArithmeticNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.FloatEqualsNode;
import jdk.graal.compiler.nodes.calc.FloatLessThanNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IntegerLessThanNode;
import jdk.graal.compiler.nodes.calc.IntegerTestNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.calc.PointerEqualsNode;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.MonitorEnterNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * What a conditional branch looks like in the graph of its method before anything is inlined: the
 * kind of test, what its operands are, and what each side goes on to do. A recorded run says how
 * often each side of such a branch was taken; written next to that, these numbers are what a model
 * of branches is trained on, and computed alone they are what it predicts from in a build that
 * has no recording.
 * <p>
 * Every feature is a number, and a category is a small integer, which is all a decision tree
 * needs. Each is found by looking at the branch and following straight-line code from it, so one
 * branch costs a bounded amount of work whatever the size of its method.
 */
public final class CrucibleBranchFeatures {

    /** How far along straight-line code a side of a branch is followed. */
    private static final int MAX_PATH = 64;

    private static final String[] SIDE = {"term", "len", "invokes", "allocs", "stores", "loads", "loopExit", "monitor", "mergeIsLoop", "afterTerm"};

    /** How many of the blocks a side of a branch dominates are looked at. */
    private static final int MAX_REGION = 64;

    private static final String[] REGION = {"blocks", "rInvokes", "rAllocs", "rStores", "rThrows", "rReturns", "rLoops", "rBackEdges", "rLoopExits"};

    /** The names of the features, in the order {@link #extract} returns them. */
    public static final String[] NAMES = names();

    private static String[] names() {
        String[] head = {"cond", "negated", "xKind", "yKind", "xConst", "yConst", "stackKind"};
        String[] tail = {"loopDepth", "inLoopHeader", "codeSize", "isStatic", "isInit", "isLambda", "isLibrary", "graphIfs", "graphLoops"};
        String[] names = new String[head.length + 2 * SIDE.length + 2 * REGION.length + tail.length];
        int i = 0;
        for (String name : head) {
            names[i++] = name;
        }
        for (String side : new String[]{"t_", "f_"}) {
            for (String name : SIDE) {
                names[i++] = side + name;
            }
        }
        for (String side : new String[]{"t_", "f_"}) {
            for (String name : REGION) {
                names[i++] = side + name;
            }
        }
        for (String name : tail) {
            names[i++] = name;
        }
        return names;
    }

    private CrucibleBranchFeatures() {
    }

    /** What is computed once for a graph and used for each of its branches. */
    public static final class GraphInfo {
        final double[] features;
        /** The blocks of the graph with their loops and dominators, or null if they could not be had. */
        final ControlFlowGraph cfg;

        GraphInfo(double[] features, ControlFlowGraph cfg) {
            this.features = features;
            this.cfg = cfg;
        }
    }

    public static GraphInfo graphInfo(StructuredGraph graph) {
        return graphInfo(graph, false);
    }

    /**
     * @param withFrequencies also work out how often each block runs for one entry of the method,
     *            from the probabilities its branches carry.
     */
    private static GraphInfo graphInfo(StructuredGraph graph, boolean withFrequencies) {
        ControlFlowGraph cfg = null;
        try {
            cfg = ControlFlowGraph.newBuilder(graph).connectBlocks(true).computeLoops(true).computeDominators(true).computeFrequency(withFrequencies).build();
        } catch (RuntimeException | AssertionError e) {
            /* A graph in a state the blocks cannot be computed for is described without them. */
        }
        return new GraphInfo(graphFeatures(graph), cfg);
    }

    /** What is the same for every branch of a graph. */
    private static double[] graphFeatures(StructuredGraph graph) {
        ResolvedJavaMethod method = graph.method();
        String name = method.getName();
        String holder = method.getDeclaringClass().getName();
        boolean lambda = name.startsWith("lambda$") || holder.contains("$$Lambda");
        boolean library = holder.startsWith("Ljava/") || holder.startsWith("Ljdk/") || holder.startsWith("Lsun/") || holder.startsWith("Lcom/oracle/svm/");
        return new double[]{method.getCodeSize(), method.isStatic() ? 1 : 0, method.isConstructor() || method.isClassInitializer() ? 1 : 0, lambda ? 1 : 0, library ? 1 : 0,
                        graph.getNodes(IfNode.TYPE).count(), graph.getNodes(LoopBeginNode.TYPE).count()};
    }

    public static double[] extract(IfNode branch, GraphInfo info) {
        double[] graphFeatures = info.features;
        double[] f = new double[NAMES.length];
        int i = 0;
        LogicNode condition = branch.condition();
        boolean negated = false;
        while (condition instanceof LogicNegationNode negation) {
            condition = negation.getValue();
            negated = !negated;
        }
        ValueNode x = null;
        ValueNode y = null;
        if (condition instanceof CompareNode compare) {
            x = compare.getX();
            y = compare.getY();
        } else if (condition instanceof IsNullNode isNull) {
            x = isNull.getValue();
        } else if (condition instanceof InstanceOfNode instanceOf) {
            x = instanceOf.getValue();
        } else if (condition instanceof IntegerTestNode test) {
            x = test.getX();
            y = test.getY();
        }
        f[i++] = conditionKind(condition);
        f[i++] = negated ? 1 : 0;
        f[i++] = operandKind(x);
        f[i++] = operandKind(y);
        f[i++] = constantClass(x);
        f[i++] = constantClass(y);
        f[i++] = stackKind(x);
        i = side(branch.trueSuccessor(), f, i);
        i = side(branch.falseSuccessor(), f, i);
        HIRBlock block = info.cfg == null ? null : info.cfg.blockFor(branch);
        i = region(block == null ? null : info.cfg.blockFor(branch.trueSuccessor()), f, i);
        i = region(block == null ? null : info.cfg.blockFor(branch.falseSuccessor()), f, i);
        f[i++] = block == null ? 0 : block.getLoopDepth();
        f[i++] = block != null && block.isLoopHeader() ? 1 : 0;
        System.arraycopy(graphFeatures, 0, f, i, graphFeatures.length);
        return f;
    }

    private static int conditionKind(LogicNode condition) {
        if (condition instanceof IsNullNode) {
            return 1;
        } else if (condition instanceof IntegerEqualsNode) {
            return 2;
        } else if (condition instanceof IntegerLessThanNode) {
            return 3;
        } else if (condition instanceof IntegerBelowNode) {
            return 4;
        } else if (condition instanceof ObjectEqualsNode) {
            return 5;
        } else if (condition instanceof PointerEqualsNode) {
            return 6;
        } else if (condition instanceof InstanceOfNode instanceOf) {
            return instanceOf.type().isExact() ? 7 : 8;
        } else if (condition instanceof FloatLessThanNode) {
            return 9;
        } else if (condition instanceof FloatEqualsNode) {
            return 10;
        } else if (condition instanceof IntegerTestNode) {
            return 11;
        } else if (condition instanceof ShortCircuitOrNode) {
            return 12;
        } else if (condition instanceof LogicConstantNode) {
            return 13;
        }
        return 0;
    }

    private static int operandKind(ValueNode value) {
        if (value == null) {
            return 0;
        } else if (value.isConstant()) {
            return 1;
        } else if (value instanceof ParameterNode) {
            return 2;
        } else if (value instanceof PhiNode phi) {
            return phi.merge() instanceof LoopBeginNode ? 4 : 3;
        } else if (value instanceof Invoke) {
            return 5;
        } else if (value instanceof LoadFieldNode) {
            return 6;
        } else if (value instanceof ArrayLengthNode) {
            return 7;
        } else if (value instanceof LoadIndexedNode) {
            return 8;
        } else if (value instanceof BinaryArithmeticNode) {
            return 9;
        } else if (value instanceof AbstractNewObjectNode) {
            return 10;
        }
        return 11;
    }

    private static int constantClass(ValueNode value) {
        if (value == null || !value.isJavaConstant()) {
            return 0;
        }
        JavaConstant constant = value.asJavaConstant();
        if (constant.isNull()) {
            return 1;
        }
        JavaKind kind = constant.getJavaKind();
        if (!kind.isNumericInteger()) {
            return 8;
        }
        long v = constant.asLong();
        if (v == 0) {
            return 2;
        } else if (v == 1) {
            return 3;
        } else if (v == -1) {
            return 4;
        } else if (v < 0) {
            return 7;
        }
        return v <= 16 ? 5 : 6;
    }

    private static int stackKind(ValueNode value) {
        if (value == null) {
            return 0;
        }
        switch (value.getStackKind()) {
            case Int:
                return 1;
            case Long:
                return 2;
            case Object:
                return 3;
            case Float:
            case Double:
                return 4;
            default:
                return 5;
        }
    }

    /** Where straight-line code from a begin node ends: the features of one side of a branch. */
    private static int side(AbstractBeginNode begin, double[] f, int at) {
        int[] counts = new int[SIDE.length];
        FixedNode end = follow(begin, counts);
        counts[0] = terminator(end);
        if (end instanceof AbstractEndNode endNode && !(end instanceof LoopEndNode)) {
            AbstractMergeNode merge = endNode.merge();
            counts[8] = merge instanceof LoopBeginNode ? 1 : 0;
            /* One step further: what the code the two sides share goes on to do. */
            counts[9] = terminator(follow(merge, new int[SIDE.length]));
        }
        int i = at;
        for (int count : counts) {
            f[i++] = count;
        }
        return i;
    }

    /**
     * What all the code a side of a branch dominates does, which is the code that runs only if the
     * branch goes that way: whether it ends in a throw or a return, calls, allocates, loops.
     */
    private static int region(HIRBlock start, double[] f, int at) {
        int[] counts = new int[REGION.length];
        if (start != null) {
            ArrayDeque<HIRBlock> work = new ArrayDeque<>();
            work.push(start);
            while (!work.isEmpty() && counts[0] < MAX_REGION) {
                HIRBlock block = work.pop();
                counts[0]++;
                if (block.isLoopHeader()) {
                    counts[6]++;
                }
                if (block.getBeginNode() instanceof LoopExitNode) {
                    counts[8]++;
                }
                for (FixedNode node : block.getNodes()) {
                    if (node instanceof Invoke) {
                        counts[1]++;
                    } else if (node instanceof AbstractNewObjectNode) {
                        counts[2]++;
                    } else if (node instanceof StoreFieldNode || node instanceof StoreIndexedNode) {
                        counts[3]++;
                    }
                }
                FixedNode end = block.getEndNode();
                if (end instanceof UnwindNode || end instanceof AbstractDeoptimizeNode) {
                    counts[4]++;
                } else if (end instanceof ReturnNode) {
                    counts[5]++;
                } else if (end instanceof LoopEndNode) {
                    counts[7]++;
                }
                for (HIRBlock dominated = block.getFirstDominated(); dominated != null; dominated = dominated.getDominatedSibling()) {
                    work.push(dominated);
                }
            }
        }
        int i = at;
        for (int count : counts) {
            f[i++] = count;
        }
        return i;
    }

    private static FixedNode follow(FixedNode start, int[] counts) {
        FixedNode current = start;
        for (int steps = 0; steps < MAX_PATH; steps++) {
            if (current instanceof Invoke) {
                counts[2]++;
            } else if (current instanceof AbstractNewObjectNode) {
                counts[3]++;
            } else if (current instanceof StoreFieldNode || current instanceof StoreIndexedNode) {
                counts[4]++;
            } else if (current instanceof LoadFieldNode || current instanceof LoadIndexedNode) {
                counts[5]++;
            } else if (current instanceof LoopExitNode) {
                counts[6] = 1;
            } else if (current instanceof MonitorEnterNode) {
                counts[7] = 1;
            }
            FixedNode next;
            if (current instanceof FixedWithNextNode withNext) {
                next = withNext.next();
            } else if (current instanceof WithExceptionNode withException) {
                /* A call that may throw: the code goes on where it returns. */
                next = withException.next();
            } else {
                return current;
            }
            if (next == null) {
                return current;
            }
            counts[1]++;
            current = next;
        }
        return null;
    }

    private static int terminator(FixedNode end) {
        if (end == null) {
            return 0;
        } else if (end instanceof ReturnNode) {
            return 1;
        } else if (end instanceof AbstractDeoptimizeNode) {
            return 2;
        } else if (end instanceof UnwindNode) {
            return 3;
        } else if (end instanceof LoopEndNode) {
            return 4;
        } else if (end instanceof AbstractEndNode) {
            return 5;
        } else if (end instanceof IfNode) {
            return 6;
        } else if (end instanceof ControlSplitNode) {
            return 7;
        }
        return 8;
    }

    /* Writing the features of the branches a recording covers, for training. */

    private static PrintWriter writer;

    private static synchronized PrintWriter writer(String file) throws IOException {
        if (writer == null) {
            BufferedWriter out = Files.newBufferedWriter(Path.of(file), StandardCharsets.UTF_8);
            writer = new PrintWriter(out);
            writer.println("probability,weight," + String.join(",", NAMES));
        }
        return writer;
    }

    /**
     * Writes one line for each branch of the graph that carries a recorded probability: the
     * probability of its true side, how often the branch ran, and its features. How often it ran is
     * how often its method was entered times how often its block runs for one entry, which the
     * recorded probabilities of the method's branches give.
     */
    public static void write(StructuredGraph graph, long methodCalls, String file) {
        GraphInfo info = null;
        StringBuilder lines = new StringBuilder();
        for (IfNode branch : graph.getNodes(IfNode.TYPE)) {
            if (!branch.getProfileData().getProfileSource().isProfiled()) {
                continue;
            }
            if (info == null) {
                info = graphInfo(graph, true);
            }
            HIRBlock block = info.cfg == null ? null : info.cfg.blockFor(branch);
            double runs = Math.max(1, methodCalls) * (block == null ? 1.0 : block.getRelativeFrequency());
            lines.append(branch.getTrueSuccessorProbability()).append(',').append(Math.round(runs));
            for (double feature : extract(branch, info)) {
                lines.append(',');
                if (feature == Math.rint(feature)) {
                    lines.append((long) feature);
                } else {
                    lines.append(feature);
                }
            }
            lines.append('\n');
        }
        if (lines.length() == 0) {
            return;
        }
        try {
            PrintWriter out = writer(file);
            synchronized (CrucibleBranchFeatures.class) {
                out.print(lines);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write branch features to " + file, e);
        }
    }

    public static synchronized void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
