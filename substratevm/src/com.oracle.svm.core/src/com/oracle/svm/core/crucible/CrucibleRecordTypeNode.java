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

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import org.graalvm.word.LocationIdentity;

/**
 * Counts the receiver type of one virtual call in a recording image. Lowered by
 * {@link CrucibleRecordTypeSnippets} to an inline check for the type the site saw last time,
 * with a call into the runtime for everything else.
 */
@NodeInfo(cycles = NodeCycles.CYCLES_8, size = NodeSize.SIZE_16)
public final class CrucibleRecordTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill {

    public static final NodeClass<CrucibleRecordTypeNode> TYPE = NodeClass.create(CrucibleRecordTypeNode.class);

    private final int site;
    @Input ValueNode receiver;

    public CrucibleRecordTypeNode(int site, ValueNode receiver) {
        super(TYPE, StampFactory.forVoid());
        this.site = site;
        this.receiver = receiver;
    }

    public int site() {
        return site;
    }

    public ValueNode receiver() {
        return receiver;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return CrucibleProfileRuntime.COUNTERS_LOCATION;
    }
}
