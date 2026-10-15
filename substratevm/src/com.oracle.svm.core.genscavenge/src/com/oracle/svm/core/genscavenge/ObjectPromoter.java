/*
 * Copyright (c) 2013, 2025, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.svm.shared.Uninterruptible.CORE_GC_CODE;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import com.oracle.svm.core.config.ObjectLayout;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.ObjectAccess;
import org.graalvm.word.impl.Word;

import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.shared.Uninterruptible;

/** Promotes individual objects or whole heap chunks to a target {@link Space}. */
public class ObjectPromoter {
    /** Promote an aligned Object to the target Space. */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    static Object copyAlignedObject(Object original, Space originalSpace, Space targetSpace) {
        return copyAlignedObject(original, originalSpace, targetSpace, Word.nullPointer());
    }

    /**
     * Promote an aligned Object to the target Space, in a collection on several threads into the
     * chunks of the thread whose state is {@code workerState} (see {@link ParallelGC}).
     */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    static Object copyAlignedObject(Object original, Space originalSpace, Space targetSpace, Pointer workerState) {
        assert originalSpace.isFromSpace() || (originalSpace == targetSpace && targetSpace.isCompactingOldSpace());
        if (workerState.isNonNull()) {
            return copyAlignedObjectParallel(original, targetSpace, workerState);
        }
        if (ParallelGC.isEnabled()) {
            return copyAlignedObjectOutOfLine(original, targetSpace);
        }
        return copyAndForward(original, targetSpace);
    }

    /**
     * The serial copy, kept out of the code that also copies in parallel: inlined there, the
     * compiler schedules its reads of the original's header and hub before the branch that chooses
     * between the two, where for a worker the original may already be forwarded and its header
     * no longer leads to a hub.
     */
    @NeverInline("Keep the serial copy's reads out of the parallel copy's code.")
    @Uninterruptible(reason = CORE_GC_CODE)
    private static Object copyAlignedObjectOutOfLine(Object original, Space targetSpace) {
        return copyAndForward(original, targetSpace);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    private static Object copyAndForward(Object original, Space targetSpace) {
        assert ObjectHeaderImpl.isAlignedObject(original);

        Object copy = copyAlignedObject(original, targetSpace);
        if (copy != null) {
            ObjectHeaderImpl.installForwardingPointer(original, copy);
        }
        return copy;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    private static Object copyAlignedObject(Object originalObj, Space targetSpace) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(originalObj);
        assert !ObjectHeaderImpl.isMarked(originalObj) : "must never copy a marked object";

        UnsignedWord originalSize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (ObjectLayout.singleton().isIdentityHashFieldOptional()) {
            ObjectHeader oh = Heap.getHeap().getObjectHeader();
            Word header = oh.readHeaderFromObject(originalObj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, true);
                assert copySize.aboveOrEqual(originalSize);
            }
        }

        Pointer copyMemory = allocateMemory(copySize, targetSpace);
        if (probability(VERY_SLOW_PATH_PROBABILITY, copyMemory.isNull())) {
            return null;
        }

        /*
         * This does a direct memory copy, without regard to whether the copied data contains object
         * references. That's okay, because all references in the copy are visited and overwritten
         * later on anyways (the card table is also updated at that point if necessary).
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(originalObj);
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, copyMemory, originalSize);

        Object copy = copyMemory.toObjectNonNull();
        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            // Must do first: ensures correct object size below and in other places
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(originalObj);
            int offset = LayoutEncoding.getIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }
        if (targetSpace.isOldSpace()) {
            if (SerialGCOptions.useCompactingOldGen() && GCImpl.getGCImpl().isCompleteCollection()) {
                /*
                 * In a compacting complete collection, the remembered set bit is set already during
                 * marking and the first object table is built later during fix-up.
                 */
            } else {
                /*
                 * If an object was copied to the old generation, its remembered set bit must be set
                 * and the first object table must be updated (even when copying from old to old).
                 */
                AlignedHeapChunk.AlignedHeader copyChunk = AlignedHeapChunk.getEnclosingChunk(copy);
                RememberedSet.get().enableRememberedSetForObject(copyChunk, copy, copySize);
            }
        }
        return copy;
    }

    /**
     * Copies an object that other threads may be copying too. The eight bytes at the hub offset
     * are read once and all that is needed is taken from them, as another thread forwarding the
     * object overwrites them; the copy gets them before the forwarding pointer is installed, so
     * that a thread that finds the copy through it sees a valid header. The thread that fails to
     * install it gives its memory back.
     */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    private static Object copyAlignedObjectParallel(Object original, Space targetSpace, Pointer workerState) {
        assert VMOperation.isGCInProgress();
        Pointer originalMemory = Word.objectToUntrackedPointer(original);
        int hubOffset = ObjectHeaderImpl.hubOffset();
        /* A volatile read, so that the eight bytes are read once and all that follows agrees. */
        long eightHeaderBytes = originalMemory.readWordVolatile(hubOffset, LocationIdentity.ANY_LOCATION).rawValue();
        Word header = ObjectHeaderImpl.headerFromEightBytes(eightHeaderBytes);
        ObjectHeaderImpl ohi = ObjectHeaderImpl.getObjectHeaderImpl();
        if (ObjectHeaderImpl.isForwardedHeader(header)) {
            return ohi.getForwardedObject(originalMemory, header);
        }
        assert ObjectHeaderImpl.isAlignedHeader(header) && !ObjectHeaderImpl.isMarkedHeader(header);

        UnsignedWord originalSize = LayoutEncoding.getSizeFromHeaderInGC(original, header, eightHeaderBytes, hubOffset, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (ObjectLayout.singleton().isIdentityHashFieldOptional() && probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
            addIdentityHashField = true;
            copySize = LayoutEncoding.getSizeFromHeaderInGC(original, header, eightHeaderBytes, hubOffset, true);
        }

        Pointer copyMemory = allocateMemoryParallel(copySize, targetSpace, workerState);
        if (probability(VERY_SLOW_PATH_PROBABILITY, copyMemory.isNull())) {
            return null;
        }
        /*
         * Build the whole copy before publishing it, so that no thread can find a copy that is not
         * finished. Another thread forwarding the original meanwhile changes only the eight bytes
         * at the hub offset, which the copy then gets from the snapshot.
         */
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, copyMemory, originalSize);
        copyMemory.writeLong(hubOffset, eightHeaderBytes);
        Object copy = copyMemory.toObjectNonNull();
        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(original);
            int offset = LayoutEncoding.getIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ohi.setIdentityHashInField(copy);
        }
        if (targetSpace.isOldSpace()) {
            ObjectHeaderImpl.setRememberedSetBit(copy);
        }
        Object forwarded = ohi.installForwardingPointerParallel(original, eightHeaderBytes, originalSize, copy);
        if (forwarded != copy) {
            ParallelGC.retractAllocation(workerState, targetSpace, copyMemory, copySize);
            return forwarded;
        }
        if (targetSpace.isOldSpace()) {
            /* The chunk is this thread's own, so its first object table needs no synchronization. */
            AlignedHeapChunk.AlignedHeader copyChunk = AlignedHeapChunk.getEnclosingChunk(copy);
            RememberedSet.get().enableRememberedSetForObject(copyChunk, copy, copySize);
        }
        return copy;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    private static Pointer allocateMemoryParallel(UnsignedWord objectSize, Space targetSpace, Pointer workerState) {
        AlignedHeapChunk.AlignedHeader chunk = ParallelGC.getAllocationChunk(workerState, ParallelGC.targetIndex(targetSpace));
        if (chunk.isNonNull()) {
            Pointer result = AlignedHeapChunk.tryAllocateMemory(chunk, objectSize);
            if (result.isNonNull()) {
                return result;
            }
        }
        return GCImpl.getGCImpl().getParallelGC().allocateInNewChunk(workerState, targetSpace, objectSize);
    }

    @Uninterruptible(reason = CORE_GC_CODE)
    static void promoteAlignedChunkWithPinnedObjectsBeforeSweeping(AlignedHeapChunk.AlignedHeader chunk, Space originalSpace, Space targetSpace) {
        assert targetSpace != originalSpace && originalSpace.isFromSpace();
        assert chunk.getObjectPinCount() != 0 : "should be used only with pinned objects";

        originalSpace.extractAlignedHeapChunk(chunk);
        targetSpace.appendAlignedHeapChunk(chunk);

        /* If necessary, the remembered set will be rebuilt while sweeping. */
    }

    /** Promote an UnalignedHeapChunk by moving it to the target Space. */
    @Uninterruptible(reason = CORE_GC_CODE)
    static void promoteUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk, Space originalSpace, Space targetSpace) {
        assert targetSpace != originalSpace && originalSpace.isFromSpace();

        originalSpace.extractUnalignedHeapChunk(chunk);
        targetSpace.appendUnalignedHeapChunk(chunk);

        if (targetSpace.isOldSpace()) {
            if (originalSpace.isYoungSpace()) {
                RememberedSet.get().enableRememberedSetForChunk(chunk);
            } else {
                assert originalSpace.isOldSpace();
                RememberedSet.get().clearRememberedSet(chunk);
            }
        }
    }

    /**
     * Allocate memory from an AlignedHeapChunk in the target Space.
     */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CORE_GC_CODE, mayBeInlined = true)
    private static Pointer allocateMemory(UnsignedWord objectSize, Space targetSpace) {
        Pointer result = Word.nullPointer();
        /* Fast-path: try allocating in the last chunk. */
        AlignedHeapChunk.AlignedHeader oldChunk = targetSpace.getLastAlignedHeapChunk();
        if (oldChunk.isNonNull()) {
            result = AlignedHeapChunk.tryAllocateMemory(oldChunk, objectSize);
        }
        if (result.isNonNull()) {
            return result;
        }
        /* Slow-path: try allocating a new chunk for the requested memory. */
        return allocateInNewChunk(objectSize, targetSpace);
    }

    @Uninterruptible(reason = CORE_GC_CODE)
    private static Pointer allocateInNewChunk(UnsignedWord objectSize, Space targetSpace) {
        AlignedHeapChunk.AlignedHeader newChunk = requestAlignedHeapChunk(targetSpace);
        if (newChunk.isNonNull()) {
            return AlignedHeapChunk.tryAllocateMemory(newChunk, objectSize);
        }
        return Word.nullPointer();
    }

    @Uninterruptible(reason = CORE_GC_CODE)
    static AlignedHeapChunk.AlignedHeader requestAlignedHeapChunk(Space targetSpace) {
        AlignedHeapChunk.AlignedHeader chunk;
        if (targetSpace.isYoungSpace()) {
            assert targetSpace.isSurvivorSpace();
            chunk = HeapImpl.getHeapImpl().getYoungGeneration().requestAlignedSurvivorChunk();
        } else {
            chunk = HeapImpl.getHeapImpl().getOldGeneration().requestAlignedChunk();
        }
        if (chunk.isNonNull()) {
            targetSpace.appendAlignedHeapChunk(chunk);
        }
        return chunk;
    }
}
