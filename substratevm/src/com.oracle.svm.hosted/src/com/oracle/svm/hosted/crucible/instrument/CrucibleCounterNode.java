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

import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.crucible.CrucibleBranchCounters;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.hosted.nodes.ReadReservedRegister;
import com.oracle.svm.core.graal.code.CGlobalDataInfo;
import com.oracle.svm.core.graal.nodes.CGlobalDataLoadAddressNode;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.AndNode;
import jdk.graal.compiler.nodes.calc.MulNode;
import jdk.graal.compiler.nodes.calc.UnsignedRightShiftNode;
import jdk.graal.compiler.nodes.calc.XorNode;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.vm.ci.meta.JavaKind;

/**
 * Bumps one counter of {@link com.oracle.svm.core.crucible.CrucibleBranchCounters}.
 * <p>
 * It stays a node of its own until the low tier and only there becomes a read, an add and a write.
 * A write is a state split, and the places a counter goes have no frame state to give it; after
 * frame states have been assigned nothing asks for one. It kills only the counters' own location,
 * so the rest of the method's memory graph does not notice it.
 */
@NodeInfo(cycles = NodeCycles.CYCLES_2, size = NodeSize.SIZE_2)
public final class CrucibleCounterNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill {

    public static final NodeClass<CrucibleCounterNode> TYPE = NodeClass.create(CrucibleCounterNode.class);

    private final CGlobalDataInfo block;
    private final int slot;

    public CrucibleCounterNode(CGlobalDataInfo block, int slot) {
        super(TYPE, StampFactory.forVoid());
        this.block = block;
        this.slot = slot;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return CrucibleProfileRuntime.COUNTERS_LOCATION;
    }

    private static ValueNode shifted(StructuredGraph graph, ValueNode value, int by) {
        return graph.addOrUnique(UnsignedRightShiftNode.create(value, ConstantNode.forInt(by, graph), NodeView.DEFAULT));
    }

    @Override
    public void lower(LoweringTool tool) {
        if (tool.getLoweringStage() != LoweringTool.StandardLoweringStage.LOW_TIER) {
            return;
        }
        StructuredGraph graph = graph();
        CGlobalDataLoadAddressNode base = graph.unique(new CGlobalDataLoadAddressNode(block));
        /*
         * The stripe comes from the address of the thread's own structure, which is in a register.
         * Nothing has to be set up per thread, so there is no moment at which a thread runs counted
         * code without a place to count in.
         */
        ValueNode thread = ReadReservedRegister.createReadIsolateThreadNode(graph);
        if (thread instanceof FixedWithNextNode fixed) {
            graph.add(fixed);
            graph.addBeforeFixed(this, fixed);
        } else {
            thread = graph.addOrUnique(thread);
        }
        ValueNode mixed = graph.addOrUnique(XorNode.create(shifted(graph, thread, 7), shifted(graph, thread, 15), NodeView.DEFAULT));
        ValueNode stripe = graph.addOrUnique(AndNode.create(mixed, ConstantNode.forLong(CrucibleBranchCounters.STRIPES - 1, graph), NodeView.DEFAULT));
        long stripeBytes = (long) CrucibleBranchCounters.stripeSlots() * Long.BYTES;
        ValueNode offset = graph.addOrUnique(AddNode.create(graph.addOrUnique(MulNode.create(stripe, ConstantNode.forLong(stripeBytes, graph), NodeView.DEFAULT)),
                        ConstantNode.forLong((long) slot * Long.BYTES, graph), NodeView.DEFAULT));
        AddressNode address = graph.unique(new OffsetAddressNode(base, offset));
        ReadNode read = graph.add(new ReadNode(address, CrucibleProfileRuntime.COUNTERS_LOCATION, StampFactory.forKind(JavaKind.Long), BarrierType.NONE, MemoryOrderMode.PLAIN));
        graph.addBeforeFixed(this, read);
        WriteNode write = graph.add(new WriteNode(address, CrucibleProfileRuntime.COUNTERS_LOCATION, graph.addOrUnique(AddNode.create(read, ConstantNode.forLong(1, graph), NodeView.DEFAULT)),
                        BarrierType.NONE, MemoryOrderMode.PLAIN));
        graph.replaceFixedWithFixed(this, write);
    }
}
