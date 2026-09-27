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

import java.util.Map;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.impl.ObjectAccess;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.graal.snippets.SubstrateTemplates;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.extended.BranchProbabilityNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;
import jdk.vm.ci.meta.JavaKind;

/**
 * The fast path of counting a receiver type, inline at the call site.
 * <p>
 * A virtual call in a recording image used to be a call into the runtime, which scanned the
 * site's row for the type. Most calls see the type the site saw last time, in the row's first
 * entry, and for those a load, a compare and an add are enough; the runtime is only called when
 * the first entry does not match. Recording cost one and a half to two times what Oracle's
 * recording image costs before this; a virtual call is where most of the difference was.
 */
public final class CrucibleRecordTypeSnippets extends SubstrateTemplates implements Snippets {

    @Snippet
    private static void recordTypeSnippet(@ConstantParameter int site, Object receiver) {
        if (BranchProbabilityNode.probability(BranchProbabilityNode.SLOW_PATH_PROBABILITY, receiver == null)) {
            return;
        }
        Object nonNullReceiver = PiNode.piCastNonNull(receiver, SnippetAnchorNode.anchor());
        CrucibleProfileRuntime runtime = CrucibleProfileRuntime.singleton();
        /* Installed at image build time and never null; a check here would need a frame state this node has not got. */
        int[] ids = (int[]) PiNode.piCastNonNull(runtime.typeIds(), SnippetAnchorNode.anchor());
        long[] counts = (long[]) PiNode.piCastNonNull(runtime.typeCounts(), SnippetAnchorNode.anchor());
        String[] keys = (String[]) PiNode.piCastNonNull(runtime.typeKeys(), SnippetAnchorNode.anchor());
        long thread = CurrentIsolate.getCurrentThread().rawValue();
        int stripe = (int) (((thread >>> 7) ^ (thread >>> 15)) & (CrucibleBranchCounters.STRIPES - 1));
        int base = (stripe * keys.length + site) * CrucibleProfileRuntime.TYPE_ROW_WIDTH;
        int typeId = DynamicHubIntrinsics.readHub(nonNullReceiver).getTypeID();
        ObjectLayout layout = ObjectLayout.singleton();
        if (BranchProbabilityNode.probability(BranchProbabilityNode.FAST_PATH_PROBABILITY, base + CrucibleProfileRuntime.TYPE_ROW_WIDTH <= ids.length &&
                        ObjectAccess.readInt(ids, Word.signed(layout.getArrayElementOffset(JavaKind.Int, base)), CrucibleProfileRuntime.COUNTERS_LOCATION) == typeId)) {
            Word offset = Word.signed(layout.getArrayElementOffset(JavaKind.Long, base));
            ObjectAccess.writeLong(counts, offset, ObjectAccess.readLong(counts, offset, CrucibleProfileRuntime.COUNTERS_LOCATION) + 1, CrucibleProfileRuntime.COUNTERS_LOCATION);
        } else {
            callRecordType(CrucibleProfileRuntime.RECORD_TYPE, site, nonNullReceiver);
        }
    }

    @NodeIntrinsic(value = ForeignCallNode.class)
    private static native void callRecordType(@ConstantNodeParameter ForeignCallDescriptor descriptor, int site, Object receiver);

    public static void registerLowerings(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        new CrucibleRecordTypeSnippets(options, providers, lowerings);
    }

    private final SnippetInfo recordType;

    private CrucibleRecordTypeSnippets(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        super(options, providers);
        this.recordType = snippet(providers, CrucibleRecordTypeSnippets.class, "recordTypeSnippet", LocationIdentity.any());
        lowerings.put(CrucibleRecordTypeNode.class, new RecordTypeLowering());
    }

    final class RecordTypeLowering implements NodeLoweringProvider<CrucibleRecordTypeNode> {
        @Override
        public void lower(CrucibleRecordTypeNode node, LoweringTool tool) {
            if (tool.getLoweringStage() != LoweringTool.StandardLoweringStage.LOW_TIER) {
                return;
            }
            Arguments args = new Arguments(recordType, node.graph(), tool.getLoweringStage());
            args.add("site", node.site());
            args.add("receiver", node.receiver());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }
}
