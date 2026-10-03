/*
 * Copyright (c) 2015, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.jdk;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import java.util.Map;

import org.graalvm.word.LocationIdentity;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.JavaMemoryUtil;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.graal.snippets.SubstrateTemplates;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.util.ArrayUtil;
import com.oracle.svm.shared.Uninterruptible;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ForeignCallWithExceptionNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.arraycopy.ArrayCopyNode;
import jdk.graal.compiler.replacements.nodes.BasicArrayCopyNode;
import jdk.vm.ci.meta.JavaKind;

public final class SubstrateArraycopySnippets extends SubstrateTemplates implements Snippets {
    private static final SubstrateForeignCallDescriptor ARRAYCOPY = SnippetRuntime.findForeignCall(SubstrateArraycopySnippets.class, "doArraycopy", HAS_SIDE_EFFECT, LocationIdentity.any());

    /*
     * Copies whose element kind the compiler already knows, and whose types and bounds it has
     * already checked, skip the dispatch above and go straight to the copy. Each kind has its own
     * target so that a call kills only that kind's array location.
     */
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_BOOLEAN = exactArraycopy("arraycopyBoolean", JavaKind.Boolean);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_BYTE = exactArraycopy("arraycopyByte", JavaKind.Byte);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_SHORT = exactArraycopy("arraycopyShort", JavaKind.Short);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_CHAR = exactArraycopy("arraycopyChar", JavaKind.Char);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_INT = exactArraycopy("arraycopyInt", JavaKind.Int);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_FLOAT = exactArraycopy("arraycopyFloat", JavaKind.Float);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_LONG = exactArraycopy("arraycopyLong", JavaKind.Long);
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_DOUBLE = exactArraycopy("arraycopyDouble", JavaKind.Double);
    /** The card mark after a reference copy touches more than the array, so this one kills everything. */
    private static final SubstrateForeignCallDescriptor ARRAYCOPY_OBJECT = SnippetRuntime.findForeignCall(SubstrateArraycopySnippets.class, "arraycopyObject", HAS_SIDE_EFFECT, LocationIdentity.any());

    private static final SubstrateForeignCallDescriptor[] FOREIGN_CALLS = new SubstrateForeignCallDescriptor[]{ARRAYCOPY, ARRAYCOPY_BOOLEAN, ARRAYCOPY_BYTE, ARRAYCOPY_SHORT, ARRAYCOPY_CHAR,
                    ARRAYCOPY_INT, ARRAYCOPY_FLOAT, ARRAYCOPY_LONG, ARRAYCOPY_DOUBLE, ARRAYCOPY_OBJECT};

    private static SubstrateForeignCallDescriptor exactArraycopy(String name, JavaKind kind) {
        return SnippetRuntime.findForeignCall(SubstrateArraycopySnippets.class, name, HAS_SIDE_EFFECT, NamedLocationIdentity.getArrayLocation(kind));
    }

    public static void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(FOREIGN_CALLS);
    }

    protected SubstrateArraycopySnippets(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        super(options, providers);
        lowerings.put(ArrayCopyNode.class, new SubstrateArrayCopyLowering());
    }

    /**
     * The actual implementation of {@link System#arraycopy}, called via the foreign call
     * {@link #ARRAYCOPY}.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false)
    private static void doArraycopy(Object fromArray, int fromIndex, Object toArray, int toIndex, int length) {
        if (fromArray == null || toArray == null) {
            throw new NullPointerException();
        }
        DynamicHub fromHub = DynamicHubIntrinsics.readHub(fromArray);
        DynamicHub toHub = DynamicHubIntrinsics.readHub(toArray);
        int fromLayoutEncoding = fromHub.getLayoutEncoding();

        if (LayoutEncoding.isPrimitiveArray(fromLayoutEncoding)) {
            if (fromArray == toArray && fromIndex < toIndex) {
                ArrayUtil.boundsCheckInSnippet(fromArray, fromIndex, toArray, toIndex, length);
                JavaMemoryUtil.copyPrimitiveArrayBackward(fromArray, fromIndex, fromArray, toIndex, length, fromLayoutEncoding);
                return;
            } else if (fromHub == toHub) {
                ArrayUtil.boundsCheckInSnippet(fromArray, fromIndex, toArray, toIndex, length);
                JavaMemoryUtil.copyPrimitiveArrayForward(fromArray, fromIndex, toArray, toIndex, length, fromLayoutEncoding);
                return;
            }
        } else if (LayoutEncoding.isObjectArray(fromLayoutEncoding)) {
            if (fromArray == toArray && fromIndex < toIndex) {
                ArrayUtil.boundsCheckInSnippet(fromArray, fromIndex, toArray, toIndex, length);
                JavaMemoryUtil.copyObjectArrayBackward(fromArray, fromIndex, fromArray, toIndex, length, fromLayoutEncoding);
                return;
            } else if (fromHub == toHub) {
                ArrayUtil.boundsCheckInSnippet(fromArray, fromIndex, toArray, toIndex, length);
                JavaMemoryUtil.copyObjectArrayForward(fromArray, fromIndex, toArray, toIndex, length, fromLayoutEncoding);
                return;
            } else if (LayoutEncoding.isObjectArray(toHub.getLayoutEncoding())) {
                ArrayUtil.boundsCheckInSnippet(fromArray, fromIndex, toArray, toIndex, length);
                if (DynamicHub.toClass(toHub).isAssignableFrom(DynamicHub.toClass(fromHub))) {
                    JavaMemoryUtil.copyObjectArrayForward(fromArray, fromIndex, toArray, toIndex, length, fromLayoutEncoding);
                } else {
                    JavaMemoryUtil.copyObjectArrayForwardWithStoreCheck(fromArray, fromIndex, toArray, toIndex, length);
                }
                return;
            }
        }
        throw new ArrayStoreException();
    }

    /**
     * Copies {@code length} elements between arrays whose element kind is {@code elementKind} and
     * whose types and bounds have been checked already. Meant for snippets: the kind must be a
     * compile-time constant. The call goes through a node that picks the stub for the kind when it
     * is lowered, so that the call takes the bytecode index of the copy it replaces.
     */
    public static void exactArraycopy(Object src, int srcPos, Object dest, int destPos, int length, JavaKind elementKind) {
        SubstrateExactArrayCopyCallNode.exactArraycopy(src, srcPos, dest, destPos, length, elementKind);
    }

    private static SubstrateForeignCallDescriptor exactArraycopyDescriptor(JavaKind elementKind) {
        return switch (elementKind) {
            case Boolean -> ARRAYCOPY_BOOLEAN;
            case Byte -> ARRAYCOPY_BYTE;
            case Short -> ARRAYCOPY_SHORT;
            case Char -> ARRAYCOPY_CHAR;
            case Int -> ARRAYCOPY_INT;
            case Float -> ARRAYCOPY_FLOAT;
            case Long -> ARRAYCOPY_LONG;
            case Double -> ARRAYCOPY_DOUBLE;
            case Object -> ARRAYCOPY_OBJECT;
            default -> throw GraalError.shouldNotReachHere("unexpected element kind " + elementKind);
        };
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyBoolean(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Boolean), arrayIndexShift(JavaKind.Boolean));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyByte(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Byte), arrayIndexShift(JavaKind.Byte));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyShort(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Short), arrayIndexShift(JavaKind.Short));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyChar(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Char), arrayIndexShift(JavaKind.Char));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyInt(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Int), arrayIndexShift(JavaKind.Int));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyFloat(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Float), arrayIndexShift(JavaKind.Float));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyLong(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Long), arrayIndexShift(JavaKind.Long));
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyDouble(Object src, int srcPos, Object dest, int destPos, int length) {
        copyPrimitiveElements(src, srcPos, dest, destPos, length, arrayBaseOffset(JavaKind.Double), arrayIndexShift(JavaKind.Double));
    }

    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void copyPrimitiveElements(Object src, int srcPos, Object dest, int destPos, int length, int baseOffset, int indexShift) {
        UnsignedWord srcOffset = elementOffset(baseOffset, indexShift, srcPos);
        UnsignedWord destOffset = elementOffset(baseOffset, indexShift, destPos);
        UnsignedWord size = Word.unsigned(length).shiftLeft(indexShift);
        if (src == dest && srcPos < destPos) {
            JavaMemoryUtil.copyPrimitiveArrayBackward(src, srcOffset, dest, destOffset, size);
        } else {
            JavaMemoryUtil.copyPrimitiveArrayForward(src, srcOffset, dest, destOffset, size);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static UnsignedWord elementOffset(int baseOffset, int indexShift, int index) {
        return Word.unsigned(baseOffset).add(Word.unsigned(index).shiftLeft(indexShift));
    }

    @Fold
    static int arrayBaseOffset(JavaKind kind) {
        return ObjectLayout.singleton().getArrayBaseOffset(kind);
    }

    @Fold
    static int arrayIndexShift(JavaKind kind) {
        return ObjectLayout.singleton().getArrayIndexShift(kind);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Arrays must not move while copying.")
    private static void arraycopyObject(Object src, int srcPos, Object dest, int destPos, int length) {
        UnsignedWord srcOffset = elementOffset(arrayBaseOffset(JavaKind.Object), arrayIndexShift(JavaKind.Object), srcPos);
        UnsignedWord destOffset = elementOffset(arrayBaseOffset(JavaKind.Object), arrayIndexShift(JavaKind.Object), destPos);
        if (src == dest && srcPos < destPos) {
            JavaMemoryUtil.copyReferencesBackward(src, srcOffset, dest, destOffset, Word.unsigned(length));
        } else {
            JavaMemoryUtil.copyReferencesForward(src, srcOffset, dest, destOffset, Word.unsigned(length));
        }
    }

    static final class SubstrateArrayCopyLowering implements NodeLoweringProvider<ArrayCopyNode> {
        @Override
        public void lower(ArrayCopyNode node, LoweringTool tool) {
            StructuredGraph graph = node.graph();
            ForeignCallWithExceptionNode call = graph.add(new ForeignCallWithExceptionNode(ARRAYCOPY, node.getSource(), node.getSourcePosition(), node.getDestination(),
                            node.getDestinationPosition(), node.getLength()));
            call.setStateAfter(node.stateAfter());
            call.setStateDuring(node.stateDuring());
            call.setBci(node.bci());
            graph.replaceWithExceptionSplit(node, call);
        }
    }

    @NodeInfo(allowedUsageTypes = {InputType.Memory, InputType.Value}, cycles = CYCLES_UNKNOWN, size = SIZE_UNKNOWN)
    public static final class SubstrateGenericArrayCopyCallNode extends BasicArrayCopyNode implements Lowerable {
        public static final NodeClass<SubstrateGenericArrayCopyCallNode> TYPE = NodeClass.create(SubstrateGenericArrayCopyCallNode.class);

        public SubstrateGenericArrayCopyCallNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, JavaKind elementKind) {
            super(TYPE, src, srcPos, dest, destPos, length, elementKind);
        }

        @Override
        public void lower(LoweringTool tool) {
            if (graph().getGuardsStage().areFrameStatesAtDeopts()) {
                StructuredGraph graph = graph();
                ForeignCallWithExceptionNode call = graph.add(new ForeignCallWithExceptionNode(ARRAYCOPY, getSource(), getSourcePosition(), getDestination(),
                                getDestinationPosition(), getLength()));
                call.setStateAfter(stateAfter());
                call.setStateDuring(stateDuring());
                call.setBci(bci());
                graph.replaceWithExceptionSplit(this, call);
            }
        }

        @NodeIntrinsic
        public static native int genericArraycopy(Object src, int srcPos, Object dest, int destPos, int length, @ConstantNodeParameter JavaKind elementKind);
    }

    @NodeInfo(allowedUsageTypes = {InputType.Memory, InputType.Value}, cycles = CYCLES_UNKNOWN, size = SIZE_UNKNOWN)
    public static final class SubstrateExactArrayCopyCallNode extends BasicArrayCopyNode implements Lowerable {
        public static final NodeClass<SubstrateExactArrayCopyCallNode> TYPE = NodeClass.create(SubstrateExactArrayCopyCallNode.class);

        public SubstrateExactArrayCopyCallNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, JavaKind elementKind) {
            super(TYPE, src, srcPos, dest, destPos, length, elementKind);
        }

        @Override
        public void lower(LoweringTool tool) {
            if (graph().getGuardsStage().areFrameStatesAtDeopts()) {
                StructuredGraph graph = graph();
                ForeignCallWithExceptionNode call = graph.add(new ForeignCallWithExceptionNode(exactArraycopyDescriptor(getElementKind()), getSource(), getSourcePosition(), getDestination(),
                                getDestinationPosition(), getLength()));
                call.setStateAfter(stateAfter());
                call.setStateDuring(stateDuring());
                call.setBci(bci());
                graph.replaceWithExceptionSplit(this, call);
            }
        }

        @NodeIntrinsic
        public static native void exactArraycopy(Object src, int srcPos, Object dest, int destPos, int length, @ConstantNodeParameter JavaKind elementKind);
    }
}
