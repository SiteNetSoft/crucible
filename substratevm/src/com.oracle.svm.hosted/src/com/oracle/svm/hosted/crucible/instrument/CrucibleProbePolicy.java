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

import com.oracle.svm.core.UninterruptibleAnnotationUtils;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Puts the probes in, at the one point every method's graph passes on its way to being compiled or
 * inlined: after parsing, before it is stored. See {@link CrucibleProbeNode}.
 */
public final class CrucibleProbePolicy extends CompileQueue.Policy {

    public CrucibleProbePolicy(HostedUniverse universe) {
        super(universe);
    }

    @Override
    public void beforeEncode(HostedMethod method, StructuredGraph graph, HighTierContext context) {
        if (method.isDeoptTarget() || !graph.trackNodeSourcePosition() || isExcluded(method, context)) {
            return;
        }
        CrucibleProbeNode entry = graph.add(new CrucibleProbeNode(CrucibleProbeNode.Kind.ENTRY, null, null));
        entry.setNodeSourcePosition(new NodeSourcePosition(null, method, 0));
        graph.addAfterFixed(graph.start(), entry);

        for (MethodCallTargetNode call : graph.getNodes().filter(MethodCallTargetNode.class).snapshot()) {
            if (!call.invokeKind().isIndirect() || call.invoke() == null || call.arguments().isEmpty() || call.targetMethod() == null) {
                continue;
            }
            NodeSourcePosition position = call.getNodeSourcePosition();
            if (position == null) {
                continue;
            }
            CrucibleProbeNode probe = graph.add(new CrucibleProbeNode(CrucibleProbeNode.Kind.RECEIVER, call.arguments().get(0), call.targetMethod()));
            probe.setNodeSourcePosition(position);
            graph.addBeforeFixed(call.invoke().asFixedNode(), probe);
        }
        if (CrucibleOptions.CrucibleRecordTestsWithProbes.getValue()) {
            probeTests(graph);
        }
    }

    /**
     * An {@code instanceof} floats, so its probe goes ahead of the branch that uses it; a test
     * nothing branches on is not worth counting. Inlined where the compiler can see what is being
     * tested, the test folds away and the branch with it, and the probe is what is left to say
     * that the value came through.
     */
    private static void probeTests(StructuredGraph graph) {
        for (InstanceOfNode test : graph.getNodes().filter(InstanceOfNode.class).snapshot()) {
            NodeSourcePosition position = test.getNodeSourcePosition();
            if (position == null) {
                continue;
            }
            for (Node usage : test.usages().snapshot()) {
                if (usage instanceof IfNode branch) {
                    CrucibleProbeNode probe = graph.add(new CrucibleProbeNode(CrucibleProbeNode.Kind.TESTED_VALUE, test.getValue(), null));
                    probe.setNodeSourcePosition(position);
                    graph.addBeforeFixed(branch, probe);
                    break;
                }
            }
        }
    }

    /** What must not be counted in; see {@code CrucibleInstrumentationPhase}. */
    static boolean isExcluded(HostedMethod method, HighTierContext context) {
        if (method.getDeclaringClass().equals(context.getMetaAccess().lookupJavaType(CrucibleProfileRuntime.class))) {
            return true;
        }
        return UninterruptibleAnnotationUtils.isUninterruptible(method);
    }
}
