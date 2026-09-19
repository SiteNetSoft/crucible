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

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.ReceiverBasedDevirtualization;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * One arm of a type-guarded dispatch: if the receiver is {@code dispatchedType}, call
 * {@code dispatchedMethod} directly.
 * <p>
 * The guard is built from {@code InstanceOfNode} and {@code PiNode}, so unlike the address-based
 * form this works before lowering — which is the point. A direct call created ahead of the inliner
 * is a call the inliner can inline, and inlining is where the value of devirtualising actually is.
 */
public final class CrucibleReceiverDevirtualization extends ReceiverBasedDevirtualization {

    private final ResolvedJavaType dispatchedType;
    private final ResolvedJavaMethod dispatchedMethod;
    private final double probability;
    private final NodeSourcePosition callerPosition;
    private Invoke duplicatedInvoke;

    public CrucibleReceiverDevirtualization(ResolvedJavaType dispatchedType, ResolvedJavaMethod dispatchedMethod, double probability, NodeSourcePosition callerPosition) {
        this.dispatchedType = dispatchedType;
        this.dispatchedMethod = dispatchedMethod;
        this.probability = probability;
        this.callerPosition = callerPosition;
    }

    @Override
    protected ResolvedJavaType dispatchedType() {
        return dispatchedType;
    }

    @Override
    protected ResolvedJavaMethod dispatchedMethod() {
        return dispatchedMethod;
    }

    @Override
    protected Invoke duplicatedInvoke() {
        return duplicatedInvoke;
    }

    @Override
    protected void setDuplicatedInvoke(Invoke invoke) {
        this.duplicatedInvoke = invoke;
    }

    @Override
    public NodeSourcePosition callerPosition() {
        return callerPosition;
    }

    @Override
    public double probability() {
        return probability;
    }
}
