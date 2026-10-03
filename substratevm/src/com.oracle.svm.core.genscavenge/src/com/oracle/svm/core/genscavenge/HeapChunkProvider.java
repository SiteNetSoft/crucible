/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
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
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordBase;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk.AlignedHeader;
import com.oracle.svm.core.genscavenge.HeapChunk.Header;
import com.oracle.svm.core.genscavenge.UnalignedHeapChunk.UnalignedHeader;
import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.guest.staging.core.jdk.UninterruptibleUtils;
import com.oracle.svm.guest.staging.core.jdk.UninterruptibleUtils.AtomicUnsigned;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.core.os.ChunkBasedCommittedMemoryProvider;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.shared.util.UnsignedUtils;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Allocates and frees the memory for aligned and unaligned heap chunks. The methods are
 * thread-safe, so no locking is necessary when calling them.
 *
 * Memory for aligned chunks is not immediately released to the OS. Chunks with a total of up to
 * {@link CollectionPolicy#getMaximumFreeAlignedChunksSize()} bytes are saved in an unused chunk
 * list. Memory for unaligned chunks is released immediately.
 */
final class HeapChunkProvider {
    /**
     * The head of the linked list of unused aligned chunks. Chunks are chained using
     * {@link HeapChunk#getNext}.
     */
    private final UninterruptibleUtils.AtomicPointer<AlignedHeader> unusedAlignedChunks = new UninterruptibleUtils.AtomicPointer<>();

    /**
     * The number of chunks in the {@link #unusedAlignedChunks} list.
     *
     * The value is not updated atomically with respect to the {@link #unusedAlignedChunks list
     * head}, but this is OK because we only need the number of chunks for policy code (to avoid
     * running down the list and counting the number of chunks).
     */
    private final AtomicUnsigned numUnusedAlignedChunks = new AtomicUnsigned();

    /** Kept chunks are listed by size in pages, for chunks of fewer pages than this. */
    private static final int UNUSED_UNALIGNED_CHUNK_LISTS = 2048;

    /**
     * Chunks of dead large arrays kept committed for the next large arrays, up to
     * {@link SerialGCOptions#SerialGCLargeArrayChunkReserve} bytes, one list for each size in pages,
     * chained by {@link HeapChunk#getNext}. As for {@link #unusedAlignedChunks}, chunks are only
     * pushed during collections and popped in uninterruptible code.
     */
    private final UninterruptibleUtils.AtomicPointer<?>[] unusedUnalignedChunks = createUnusedUnalignedChunkLists();
    private final AtomicUnsigned bytesInUnusedUnalignedChunks = new AtomicUnsigned();

    @Platforms(Platform.HOSTED_ONLY.class)
    private static UninterruptibleUtils.AtomicPointer<?>[] createUnusedUnalignedChunkLists() {
        UninterruptibleUtils.AtomicPointer<?>[] lists = new UninterruptibleUtils.AtomicPointer<?>[UNUSED_UNALIGNED_CHUNK_LISTS];
        for (int i = 0; i < lists.length; i++) {
            lists[i] = new UninterruptibleUtils.AtomicPointer<UnalignedHeader>();
        }
        return lists;
    }

    /** The list for chunks of {@code committedChunkSize} bytes, or null if there is none. */
    @SuppressWarnings("unchecked")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private UninterruptibleUtils.AtomicPointer<UnalignedHeader> unusedUnalignedChunkList(UnsignedWord committedChunkSize) {
        UnsignedWord pages = committedChunkSize.unsignedDivide(ChunkBasedCommittedMemoryProvider.get().getGranularity());
        if (pages.aboveOrEqual(UNUSED_UNALIGNED_CHUNK_LISTS)) {
            return null;
        }
        return (UninterruptibleUtils.AtomicPointer<UnalignedHeader>) unusedUnalignedChunks[(int) pages.rawValue()];
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    HeapChunkProvider() {
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnsignedWord getBytesInUnusedChunks() {
        return numUnusedAlignedChunks.get().multiply(HeapParameters.getAlignedHeapChunkSize());
    }

    /** Acquire a new AlignedHeapChunk, either from the free list or from the operating system. */
    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    AlignedHeader produceAlignedChunk() {
        UnsignedWord chunkSize = HeapParameters.getAlignedHeapChunkSize();
        AlignedHeader result = popUnusedAlignedChunk();
        if (result.isNull()) {
            /* Unused list was empty, need to allocate memory. */
            result = (AlignedHeader) ChunkBasedCommittedMemoryProvider.get().allocateAlignedChunk(chunkSize, HeapParameters.getAlignedHeapChunkAlignment());
            AlignedHeapChunk.initialize(result, chunkSize);
        }
        assert HeapChunk.getTopOffset(result).equal(AlignedHeapChunk.getObjectsStartOffset());
        assert HeapChunk.getSize(result).equal(chunkSize);

        if (HeapParameters.getZapProducedHeapChunks()) {
            zapUnusedObjectMemory(result, HeapParameters.getProducedHeapChunkZapWord());
        }
        return result;
    }

    void freeExcessAlignedChunks() {
        assert VMOperation.isGCInProgress();
        consumeAlignedChunks(Word.nullPointer(), false);
    }

    /**
     * Releases a list of AlignedHeapChunks, either to the free list or back to the operating
     * system. This method may only be called after the chunks were already removed from the spaces.
     */
    void consumeAlignedChunks(AlignedHeader firstChunk, boolean keepAll) {
        assert VMOperation.isGCInProgress();
        assert firstChunk.isNull() || HeapChunk.getPrevious(firstChunk).isNull() : "prev must be null";

        UnsignedWord maxChunksToKeep = Word.zero();
        UnsignedWord unusedChunksToFree = Word.zero();
        if (keepAll) {
            maxChunksToKeep = UnsignedUtils.MAX_VALUE;
        } else {
            UnsignedWord freeListBytes = getBytesInUnusedChunks();
            UnsignedWord reserveBytes = GCImpl.getPolicy().getMaximumFreeAlignedChunksSize();
            UnsignedWord maxHeapFree = Word.unsigned(SerialGCOptions.MaxHeapFree.getValue());
            if (maxHeapFree.aboveThan(0)) {
                reserveBytes = UnsignedUtils.min(reserveBytes, maxHeapFree);
            }
            if (freeListBytes.belowThan(reserveBytes)) {
                maxChunksToKeep = reserveBytes.subtract(freeListBytes).unsignedDivide(HeapParameters.getAlignedHeapChunkSize());
            } else {
                unusedChunksToFree = freeListBytes.subtract(reserveBytes).unsignedDivide(HeapParameters.getAlignedHeapChunkSize());
            }
        }

        // Potentially keep some chunks in the free list for quicker allocation, free the rest
        AlignedHeader cur = firstChunk;
        while (cur.isNonNull() && maxChunksToKeep.aboveThan(0)) {
            AlignedHeader next = HeapChunk.getNext(cur);
            cleanAlignedChunk(cur);
            pushUnusedAlignedChunk(cur);

            maxChunksToKeep = maxChunksToKeep.subtract(1);
            cur = next;
        }
        freeAlignedChunkList(cur);

        // Release chunks from the free list to the operating system when spaces shrink
        freeUnusedAlignedChunksAtSafepoint(unusedChunksToFree);
    }

    private static void cleanAlignedChunk(AlignedHeader alignedChunk) {
        assert VMOperation.isGCInProgress();
        AlignedHeapChunk.reset(alignedChunk);
        if (HeapParameters.getZapConsumedHeapChunks()) {
            zapUnusedObjectMemory(alignedChunk, HeapParameters.getConsumedHeapChunkZapWord());
        }
    }

    /**
     * Push a chunk to the global linked list of unused chunks.
     * <p>
     * This method does not need to be atomic as unused chunks may only be pushed during garbage
     * collection. To prevent ABA problems, the application may only pop unused chunks while in
     * uninterruptible code.
     *
     * Note the asymmetry with {@link #popUnusedAlignedChunk()}, which does not use a global free
     * list.
     */
    private void pushUnusedAlignedChunk(AlignedHeader chunk) {
        assert VMOperation.isGCInProgress();

        HeapChunk.setNext(chunk, unusedAlignedChunks.get());
        unusedAlignedChunks.set(chunk);
        numUnusedAlignedChunks.addAndGet(Word.unsigned(1));
    }

    /**
     * Pop a chunk from the global linked list of unused chunks. Returns {@code null} if the list is
     * empty.
     * <p>
     * This method uses compareAndSet to protect itself from races with competing pop operations,
     * but it is <em>not</em> safe with respect to competing pushes. Since pushes can happen during
     * garbage collections, I avoid the ABA problem by making the kernel of this method
     * uninterruptible so it can not be interrupted by a safepoint.
     */
    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    private AlignedHeader popUnusedAlignedChunk() {
        AlignedHeader result = popUnusedAlignedChunkUninterruptibly();
        if (result.isNull()) {
            return Word.nullPointer();
        } else {
            numUnusedAlignedChunks.subtractAndGet(Word.unsigned(1));
            return result;
        }
    }

    @Uninterruptible(reason = "Must not be interrupted by competing pushes.")
    private AlignedHeader popUnusedAlignedChunkUninterruptibly() {
        while (true) {
            AlignedHeader result = unusedAlignedChunks.get();
            if (result.isNull()) {
                return Word.nullPointer();
            } else {
                AlignedHeader next = HeapChunk.getNext(result);
                if (unusedAlignedChunks.compareAndSet(result, next)) {
                    HeapChunk.setNext(result, Word.nullPointer());
                    return result;
                }
            }
        }
    }

    private void freeUnusedAlignedChunksAtSafepoint(UnsignedWord count) {
        assert VMOperation.isGCInProgress();
        if (count.equal(0)) {
            return;
        }

        AlignedHeader chunk = unusedAlignedChunks.get();
        UnsignedWord released = Word.zero();
        while (chunk.isNonNull() && released.belowThan(count)) {
            AlignedHeader next = HeapChunk.getNext(chunk);
            freeAlignedChunk(chunk);
            chunk = next;
            released = released.add(1);
        }
        unusedAlignedChunks.set(chunk);
        numUnusedAlignedChunks.subtractAndGet(released);
    }

    /**
     * Acquire an UnalignedHeapChunk, either one kept from a dead large array or from the operating
     * system.
     */
    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    UnalignedHeader produceUnalignedChunk(UnsignedWord objectSize) {
        UnsignedWord chunkSize = UnalignedHeapChunk.getChunkSizeForObject(objectSize);
        ChunkBasedCommittedMemoryProvider memoryProvider = ChunkBasedCommittedMemoryProvider.get();
        UnsignedWord committedChunkSize = UnsignedUtils.roundUp(chunkSize, memoryProvider.getGranularity());

        UnalignedHeader reused = popUnusedUnalignedChunk(committedChunkSize);
        if (reused.isNonNull()) {
            /*
             * Callers count on the zeroes of freshly committed memory, which also mark every card
             * of the chunk's remembered set dirty, as in a new chunk.
             */
            UnmanagedMemoryUtil.fill(HeapChunk.asPointer(reused), UnalignedHeapChunk.calculateObjectStartOffset(objectSize).add(objectSize), (byte) 0);
            UnalignedHeapChunk.initialize(reused, HeapChunk.getSize(reused), objectSize);
            return reused;
        }

        UnalignedHeader result = (UnalignedHeader) memoryProvider.allocateUnalignedChunk(committedChunkSize);
        UnalignedHeapChunk.initialize(result, committedChunkSize, objectSize);
        assert HeapChunk.getTopPointer(result).belowOrEqual(HeapChunk.getEndPointer(result)) : "UnalignedHeapChunk insufficient for requested object";

        /* Avoid zapping if unaligned chunks are pre-zeroed. */
        if (!memoryProvider.areUnalignedChunksZeroed() && HeapParameters.getZapProducedHeapChunks()) {
            zapUnusedObjectMemory(result, HeapParameters.getProducedHeapChunkZapWord());
        }
        return result;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static boolean areUnalignedChunksZeroed() {
        return ChunkBasedCommittedMemoryProvider.get().areUnalignedChunksZeroed();
    }

    /** Takes a kept chunk of exactly {@code committedChunkSize} bytes, or returns null. */
    @Uninterruptible(reason = "Must not be interrupted by competing pushes.")
    private UnalignedHeader popUnusedUnalignedChunk(UnsignedWord committedChunkSize) {
        UninterruptibleUtils.AtomicPointer<UnalignedHeader> list = unusedUnalignedChunkList(committedChunkSize);
        if (list == null) {
            return Word.nullPointer();
        }
        while (true) {
            UnalignedHeader result = list.get();
            if (result.isNull()) {
                return Word.nullPointer();
            }
            UnalignedHeader next = HeapChunk.getNext(result);
            if (list.compareAndSet(result, next)) {
                bytesInUnusedUnalignedChunks.subtractAndGet(HeapChunk.getSize(result));
                return result;
            }
        }
    }

    /**
     * Keeps UnalignedHeapChunks of dead large arrays for reuse while they fit in
     * {@link SerialGCOptions#SerialGCLargeArrayChunkReserve} and releases the rest back to the
     * operating system.
     */
    void consumeUnalignedChunks(UnalignedHeader firstChunk) {
        assert VMOperation.isGCInProgress();
        UnsignedWord reserve = Word.unsigned(SerialGCOptions.SerialGCLargeArrayChunkReserve.getValue());
        UnalignedHeader cur = firstChunk;
        while (cur.isNonNull()) {
            UnalignedHeader next = HeapChunk.getNext(cur);
            UnsignedWord size = HeapChunk.getSize(cur);
            UninterruptibleUtils.AtomicPointer<UnalignedHeader> list = unusedUnalignedChunkList(size);
            if (list != null && bytesInUnusedUnalignedChunks.get().add(size).belowOrEqual(reserve)) {
                HeapChunk.setPrevious(cur, Word.nullPointer());
                HeapChunk.setNext(cur, list.get());
                list.set(cur);
                bytesInUnusedUnalignedChunks.addAndGet(size);
            } else {
                freeUnalignedChunk(cur);
            }
            cur = next;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void zapUnusedObjectMemory(Header<?> chunk, WordBase value) {
        Pointer start = HeapChunk.getTopPointer(chunk);
        Pointer limit = HeapChunk.getEndPointer(chunk);
        for (Pointer p = start; p.belowThan(limit); p = p.add(SubstrateTarget.getWordSize())) {
            p.writeWord(0, value);
        }
    }

    void logFreeChunks(Log log) {
        HeapChunkLogging.logChunks(log, unusedAlignedChunks.get(), "F", false);
    }

    @Uninterruptible(reason = "Tear-down in progress.")
    void tearDown() {
        freeAlignedChunkList(unusedAlignedChunks.get());
        for (UninterruptibleUtils.AtomicPointer<?> list : unusedUnalignedChunks) {
            freeUnalignedChunkList((UnalignedHeader) list.get());
        }
    }

    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    static void freeAlignedChunkList(AlignedHeader first) {
        for (AlignedHeader chunk = first; chunk.isNonNull();) {
            AlignedHeader next = HeapChunk.getNext(chunk);
            freeAlignedChunk(chunk);
            chunk = next;
        }
    }

    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    static void freeUnalignedChunkList(UnalignedHeader first) {
        for (UnalignedHeader chunk = first; chunk.isNonNull();) {
            UnalignedHeader next = HeapChunk.getNext(chunk);
            freeUnalignedChunk(chunk);
            chunk = next;
        }
    }

    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    private static void freeAlignedChunk(AlignedHeader chunk) {
        ChunkBasedCommittedMemoryProvider.get().freeAlignedChunk(chunk, HeapParameters.getAlignedHeapChunkSize(), HeapParameters.getAlignedHeapChunkAlignment());
    }

    @Uninterruptible(reason = "Allocation internals must never end up in interruptible code.")
    private static void freeUnalignedChunk(UnalignedHeader chunk) {
        ChunkBasedCommittedMemoryProvider.get().freeUnalignedChunk(chunk, HeapChunk.getSize(chunk));
    }
}
