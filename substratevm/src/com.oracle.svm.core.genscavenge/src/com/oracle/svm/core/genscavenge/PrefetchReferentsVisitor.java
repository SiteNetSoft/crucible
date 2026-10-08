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
package com.oracle.svm.core.genscavenge;

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.graal.nodes.PrefetchReadNode;
import com.oracle.svm.core.heap.InstanceReferenceMapDecoder;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.UninterruptibleObjectReferenceVisitor;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;
import com.oracle.svm.core.hub.DynamicHubSupport;
import com.oracle.svm.core.hub.HubType;
import com.oracle.svm.core.hub.InteriorObjRefWalker;
import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.Uninterruptible;

import jdk.graal.compiler.nodes.java.ArrayLengthNode;

/**
 * Prefetches what the references of an object point to, and changes nothing. While a collection
 * visits a copied object, this is applied to the next one, so that its referents are on their way
 * when it is visited: most objects hold too few references for a prefetch within the object to be
 * early enough.
 */
final class PrefetchReferentsVisitor implements UninterruptibleObjectReferenceVisitor {
    /** At most this many slots of an object array are prefetched ahead. */
    private static final int ARRAY_SLOTS = 8;

    @Platforms(Platform.HOSTED_ONLY.class)
    PrefetchReferentsVisitor() {
    }

    /** Prefetches the referents of {@code obj} if it is an instance or an object array. */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    void prefetchReferentsOf(Object obj) {
        DynamicHub hub = DynamicHubIntrinsics.readHub(obj);
        int hubType = hub.getHubType();
        if (hubType == HubType.INSTANCE) {
            InstanceReferenceMapDecoder.walkReferencesInline(Word.objectToUntrackedPointer(obj), DynamicHubSupport.getInstanceReferenceMap(hub), this, obj);
        } else if (hubType == HubType.OBJECT_ARRAY) {
            int length = ArrayLengthNode.arrayLength(obj);
            InteriorObjRefWalker.walkObjectArrayRangeInline(obj, 0, Math.min(length, ARRAY_SLOTS), this);
        }
    }

    @Override
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public void visitObjectReferences(Pointer firstObjRef, boolean compressed, int referenceSize, Object holderObject, int count) {
        Pointer pos = firstObjRef;
        Pointer end = firstObjRef.add(Word.unsigned(count).multiply(referenceSize));
        while (pos.belowThan(end)) {
            Pointer p = ReferenceAccess.singleton().readObjectAsUntrackedPointer(pos, compressed);
            if (p.isNonNull()) {
                PrefetchReadNode.prefetch(p);
            }
            pos = pos.add(referenceSize);
        }
    }

    @Override
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public void visitDerivedReferenceBase(Pointer baseObjRef, boolean compressed, int referenceSize, Object holderObject) {
    }

    @Override
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public void visitDerivedReference(Pointer baseObjRef, Pointer derivedObjRef, boolean compressed, Object holderObject) {
    }
}
