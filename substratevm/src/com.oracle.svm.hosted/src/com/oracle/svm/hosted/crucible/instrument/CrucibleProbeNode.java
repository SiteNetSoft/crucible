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

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.IterableNodeType;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Marks, in a method's graph as it comes out of parsing, a place where something is to be counted
 * once it is known what the method has been inlined into.
 * <p>
 * Counting used to be added to the graph a method is finally compiled from, and by then the
 * compiler has made use of the very context the counts are wanted for. Inlined into a caller that
 * knows what it passes, a call with several possible receivers becomes a direct call, and there is
 * then no virtual call left to count receivers at. The copies of the method inlined elsewhere still
 * have one, so the profile of the call, added up over the copies, holds only the receivers the
 * compiler could not work out. A build reading it takes the rarer receiver for the only one.
 * <p>
 * A probe goes in before any of that, travels with the method wherever it is inlined, picks up the
 * calling context in its source position as every node does, and is turned into the real counter
 * after inlining. Left in a graph that is compiled without that step, it lowers to nothing.
 */
@NodeInfo(cycles = NodeCycles.CYCLES_0, size = NodeSize.SIZE_0)
public final class CrucibleProbeNode extends FixedWithNextNode implements Lowerable, IterableNodeType {

    public static final NodeClass<CrucibleProbeNode> TYPE = NodeClass.create(CrucibleProbeNode.class);

    /** The receiver of the call that follows, or {@code null} for a probe that counts entries to the method. */
    @OptionalInput ValueNode receiver;
    /** The method the call names, which the receivers seen are later resolved against. */
    private final ResolvedJavaMethod target;

    /** A probe for the receiver of a call to {@code target}. */
    public CrucibleProbeNode(ValueNode receiver, ResolvedJavaMethod target) {
        super(TYPE, StampFactory.forVoid());
        this.receiver = receiver;
        this.target = target;
    }

    /** A probe for entries to the method it is in. */
    public CrucibleProbeNode() {
        this(null, null);
    }

    public ValueNode receiver() {
        return receiver;
    }

    public ResolvedJavaMethod target() {
        return target;
    }

    public boolean countsEntries() {
        return target == null;
    }

    @Override
    public void lower(LoweringTool tool) {
        graph().removeFixed(this);
    }
}
