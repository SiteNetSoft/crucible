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
import static com.oracle.svm.shared.Uninterruptible.CORE_GC_CODE;

import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPoint.Publish;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordBase;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.container.Container;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk.AlignedHeader;
import com.oracle.svm.core.genscavenge.UnalignedHeapChunk.UnalignedHeader;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.graal.nodes.WriteCurrentVMThreadNode;
import com.oracle.svm.core.graal.snippets.CEntryPointSnippets;
import com.oracle.svm.core.hub.InteriorObjRefWalker;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.jdk.Jvm;
import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.guest.staging.c.function.CEntryPointOptions;
import com.oracle.svm.guest.staging.core.memory.UntrackedNullableNativeMemory;
import com.oracle.svm.guest.staging.core.thread.OSThreadHandle;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;

/**
 * Collects the young generation on several threads.
 * <p>
 * The roots are scanned by the thread that runs the collection, as in a serial collection; what
 * they reach is copied into chunks of that thread's own, and then worker threads scan what was
 * copied, copying what it reaches into chunks of their own, until nothing is left to scan. A worker
 * that has filled a chunk it has not scanned to the end hands the rest to the others through a
 * queue; a worker with nothing in the queue scans its own chunks. A chunk is only ever allocated
 * into by the thread it belongs to, so allocation needs no synchronization, and an object is only
 * ever scanned by one thread.
 * <p>
 * Two threads can reach the same object. Each copies it into memory of its own and then tries to
 * install a forwarding pointer with a compare-and-swap: the one that fails gives its memory back
 * and uses the other's copy. A large object is not copied but has its chunk moved, under
 * {@link #mutex}, which also guards the queue, taking new chunks and the list of discovered
 * references.
 * <p>
 * The workers are threads of the operating system that are not attached to the isolate: they do
 * not take part in safepoints, so they can run while the collection holds one, and they only run
 * code that is uninterruptible. They are started at the first collection that uses them and wait
 * between collections.
 * <p>
 * Each thread keeps its state in native memory: the isolate, then for each space it copies into
 * (the old generation and each survivor space) the chunk it allocates in and how far that chunk
 * has been scanned. The visitor each worker scans with holds its state, so copying an object finds
 * the thread's chunks without a lookup.
 * <p>
 * The design follows a parallel collector that was developed upstream and parallelized the scan of
 * complete collections; this one is for incremental collections.
 */
public final class ParallelGC {
    /** At most this many worker threads; the visitors they scan with are built with the image. */
    static final int MAX_WORKERS = 8;

    /* Layout of a thread's state, in words. */
    private static final int ISOLATE_SLOT = 0;
    private static final int SCANNED_CHUNK_SLOT = 1;
    private static final int POPPED_END_SLOT = 2;
    private static final int FIRST_TARGET_SLOT = 3;

    /** The low bit of a queue entry that is an unaligned chunk rather than a position in an aligned one. */
    private static final int UNALIGNED_BIT = 0b01;

    private final VMMutex mutex = new VMMutex("parallelGC");
    private final VMCondition workAvailable = new VMCondition(mutex, "parallelGCWork");
    private final VMCondition allIdle = new VMCondition(mutex, "parallelGCIdle");
    private final CEntryPointLiteral<CFunctionPointer> workerRoutine;
    private final GreyToBlackObjectVisitor[] workerVisitors;

    private Pointer queue;
    private int queueCapacity;
    private int queueSize;

    private boolean initialized;
    private int workers;
    private Pointer states;
    private Pointer threads;
    /** For {@link #checkCopies}: each thread's chunk in the old generation and its top as a collection begins. */
    private Pointer keptChunks;
    private int busy;
    private volatile boolean inParallelPhase;
    private volatile boolean active;

    @Platforms(Platform.HOSTED_ONLY.class)
    ParallelGC(GreyToBlackObjectVisitor[] workerVisitors) {
        this.workerVisitors = workerVisitors;
        this.workerRoutine = CEntryPointLiteral.create(ParallelGC.class, "workerRun", Pointer.class);
    }

    /** Whether the image can collect its young generation on several threads. */
    @Fold
    static boolean isEnabled() {
        return SerialGCOptions.ParallelYoungCollection.getValue();
    }

    /** Whether a collection on several threads is in progress. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean isActive() {
        return active;
    }

    /** Whether the current thread is one of the workers, which are not attached to the isolate. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static boolean isWorkerThread() {
        return isEnabled() && CurrentIsolate.getCurrentThread().isNull() && GCImpl.getGCImpl().getParallelGC().isActive();
    }

    /**
     * What the survivor spaces may take beyond their capacity in a collection on several threads:
     * a chunk for each thread, which holds one partly filled chunk per space that a serial
     * collection would have filled.
     */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static UnsignedWord survivorSlack() {
        if (!isEnabled() || !GCImpl.getGCImpl().getParallelGC().isActive()) {
            return Word.zero();
        }
        return HeapParameters.getAlignedHeapChunkSize().multiply(GCImpl.getGCImpl().getParallelGC().workers + 1);
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    VMMutex getMutex() {
        return mutex;
    }

    /* The state of a thread. */

    @Fold
    static int targets() {
        return HeapParameters.getMaxSurvivorSpaces() + 1;
    }

    @Fold
    static int stateWords() {
        return FIRST_TARGET_SLOT + 2 * targets();
    }

    /** The index of the chunk a thread allocates in when it copies into {@code space}. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static int targetIndex(Space space) {
        return space.isOldSpace() ? 0 : space.getAge();
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static int wordSize() {
        return SubstrateTarget.getWordSize();
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static AlignedHeader getAllocationChunk(Pointer state, int target) {
        return state.readWord((FIRST_TARGET_SLOT + target) * wordSize());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static void setAllocationChunk(Pointer state, int target, AlignedHeader chunk) {
        state.writeWord((FIRST_TARGET_SLOT + target) * wordSize(), chunk);
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static Pointer getScanStart(Pointer state, int target) {
        return state.readWord((FIRST_TARGET_SLOT + targets() + target) * wordSize());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static void setScanStart(Pointer state, int target, Pointer start) {
        state.writeWord((FIRST_TARGET_SLOT + targets() + target) * wordSize(), start);
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static AlignedHeader getScannedChunk(Pointer state) {
        return state.readWord(SCANNED_CHUNK_SLOT * wordSize());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static void setScannedChunk(Pointer state, AlignedHeader chunk) {
        state.writeWord(SCANNED_CHUNK_SLOT * wordSize(), chunk);
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private Pointer stateOf(int index) {
        return states.add(index * stateWords() * wordSize());
    }

    /* A collection. */

    /**
     * Prepares for an incremental collection on several threads, starting the workers the first
     * time, and returns the state of the thread that runs the collection.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    Pointer beginCollection() {
        if (!initialized) {
            initialize();
        }
        /* The chunks kept in the old generation were scanned to their top, by now perhaps by a serial collection. */
        for (int i = 0; i <= workers; i++) {
            Pointer state = stateOf(i);
            AlignedHeader chunk = getAllocationChunk(state, 0);
            if (chunk.isNonNull()) {
                setScanStart(state, 0, HeapChunk.getTopPointer(chunk));
            }
            keptChunks.writeWord(2 * i * wordSize(), chunk);
            keptChunks.writeWord((2 * i + 1) * wordSize(), chunk.isNonNull() ? HeapChunk.getTopPointer(chunk) : Word.nullPointer());
        }
        active = true;
        return stateOf(workers);
    }

    /**
     * Scans everything the roots reached and everything that reaches, on the workers and on the
     * thread that runs the collection, which first hands the unscanned rest of its chunks to the
     * queue and then takes work like a worker until no thread has any left.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    void scanInParallel(GreyToBlackObjectVisitor collectorVisitor) {
        Pointer own = stateOf(workers);
        mutex.lockNoTransitionUnspecifiedOwner();
        try {
            /*
             * Hand the unscanned rest of this thread's chunks to the queue, up to their top now:
             * this thread goes on copying into them while it scans too.
             */
            for (int t = 0; t < targets(); t++) {
                AlignedHeader chunk = getAllocationChunk(own, t);
                if (chunk.isNonNull() && HeapChunk.getTopPointer(chunk).aboveThan(getScanStart(own, t))) {
                    push(getScanStart(own, t), HeapChunk.getTopPointer(chunk));
                    setScanStart(own, t, HeapChunk.getTopPointer(chunk));
                }
            }
            if (queueSize == 0) {
                return;
            }
            inParallelPhase = true;
            busy++;
            workAvailable.broadcast();
        } finally {
            mutex.unlockNoTransitionUnspecifiedOwner();
        }
        while (true) {
            Pointer entry;
            mutex.lockNoTransitionUnspecifiedOwner();
            try {
                entry = pop(own);
                while (entry.isNull() && !hasOwnWork(own)) {
                    if (--busy == 0) {
                        endPhase();
                        return;
                    }
                    workAvailable.blockNoTransitionUnspecifiedOwner();
                    if (!inParallelPhase) {
                        return;
                    }
                    busy++;
                    entry = pop(own);
                }
            } finally {
                mutex.unlockNoTransitionUnspecifiedOwner();
            }
            if (entry.isNonNull()) {
                scan(entry, getPoppedEnd(own), collectorVisitor);
            } else {
                scanOwnChunks(own, collectorVisitor);
            }
        }
    }

    /** Ends a phase once no thread has work left: wakes whoever waits for its end. */
    @Uninterruptible(reason = CORE_GC_CODE)
    private void endPhase() {
        inParallelPhase = false;
        allIdle.broadcast();
        workAvailable.broadcast();
    }

    /**
     * Ends a collection. Each thread keeps its chunk in the old generation for the next one, as a
     * serial collection keeps the old generation's last chunk: dropped after every collection,
     * their unused rest adds up to a chunk per thread and collection, which fills the old
     * generation early in a program that collects often. The survivor spaces are swapped, so
     * their chunks are dropped.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    void endCollection() {
        for (int i = 0; i <= workers; i++) {
            Pointer state = stateOf(i);
            for (int t = 1; t < targets(); t++) {
                setAllocationChunk(state, t, Word.nullPointer());
                setScanStart(state, t, Word.nullPointer());
            }
        }
        active = false;
    }

    /** Drops the chunks kept in the old generation, which a complete collection releases or moves. */
    @Uninterruptible(reason = CORE_GC_CODE)
    void forgetOldGenerationChunks() {
        if (!initialized) {
            return;
        }
        for (int i = 0; i <= workers; i++) {
            setAllocationChunk(stateOf(i), 0, Word.nullPointer());
            setScanStart(stateOf(i), 0, Word.nullPointer());
        }
    }

    /* Allocation. */

    /**
     * Gives a thread a new chunk to copy into {@code targetSpace} and allocates {@code size} bytes
     * in it, or returns null if the space can take no more chunks. The unscanned rest of the chunk
     * it replaces goes to the queue, unless the thread is scanning that chunk itself and so will
     * scan it to its end.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    Pointer allocateInNewChunk(Pointer state, Space targetSpace, UnsignedWord size) {
        int target = targetIndex(targetSpace);
        mutex.lockNoTransitionUnspecifiedOwner();
        try {
            AlignedHeader old = getAllocationChunk(state, target);
            if (old.isNonNull() && old.notEqual(getScannedChunk(state)) && HeapChunk.getTopPointer(old).aboveThan(getScanStart(state, target))) {
                push(getScanStart(state, target), HeapChunk.getTopPointer(old));
            }
            AlignedHeader chunk = ObjectPromoter.requestAlignedHeapChunk(targetSpace);
            setAllocationChunk(state, target, chunk);
            if (chunk.isNull()) {
                setScanStart(state, target, Word.nullPointer());
                return Word.nullPointer();
            }
            setScanStart(state, target, AlignedHeapChunk.getObjectsStart(chunk));
        } finally {
            mutex.unlockNoTransitionUnspecifiedOwner();
        }
        return AlignedHeapChunk.tryAllocateMemory(getAllocationChunk(state, target), size);
    }

    /** Gives back the memory of a copy that lost the race to forward its object. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void retractAllocation(Pointer state, Space targetSpace, Pointer copy, UnsignedWord size) {
        AlignedHeader chunk = getAllocationChunk(state, targetIndex(targetSpace));
        assert HeapChunk.getTopPointer(chunk).equal(copy.add(size)) : "only the last allocation can be retracted";
        HeapChunk.setTopPointer(chunk, copy);
    }

    /**
     * Promotes an object in an unaligned chunk by moving the chunk, as {@link GCImpl#promoteObject}
     * does, and queues it to be scanned. All of it happens under the mutex, including reading the
     * chunk's space: another thread may be moving the chunk, which has no space while it is moved,
     * or may have moved it already.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    Object promoteUnalignedObject(Object original, UnalignedHeader chunk, boolean completeCollection, int tenuringAge) {
        mutex.lockNoTransitionUnspecifiedOwner();
        try {
            Space originalSpace = HeapChunk.getSpace(chunk);
            if (originalSpace.isToSpace()) {
                return original;
            }
            HeapImpl heap = HeapImpl.getHeapImpl();
            YoungGeneration young = heap.getYoungGeneration();
            Space targetSpace = null;
            if (!completeCollection && originalSpace.getNextAgeForPromotion() < tenuringAge) {
                if (young.unalignedChunkFitsInSurvivors(chunk)) {
                    targetSpace = young.getSurvivorToSpaceAt(originalSpace.getNextAgeForPromotion() - 1);
                } else {
                    GCImpl.getAccounting().onSurvivorOverflowed();
                }
            }
            if (targetSpace == null) {
                targetSpace = ((CopyingOldGeneration) heap.getOldGeneration()).getToSpace();
            }
            ObjectPromoter.promoteUnalignedHeapChunk(chunk, originalSpace, targetSpace);
            push(HeapChunk.asPointer(chunk).or(UNALIGNED_BIT), Word.nullPointer());
            return original;
        } finally {
            mutex.unlockNoTransitionUnspecifiedOwner();
        }
    }

    /* The queue, guarded by the mutex. */

    /** Queues the objects from {@code start} up to {@code end}, or an unaligned chunk with no end. */
    @Uninterruptible(reason = CORE_GC_CODE)
    private void push(Pointer start, Pointer end) {
        assert start.isNonNull();
        if (queueSize == queueCapacity) {
            int capacity = queueCapacity * 2;
            Pointer grown = UntrackedNullableNativeMemory.realloc(queue, Word.unsigned(capacity).multiply(2 * wordSize()));
            VMError.guarantee(grown.isNonNull(), "Out of native memory for the parallel collector's queue.");
            queue = grown;
            queueCapacity = capacity;
        }
        queue.writeWord(2 * queueSize * wordSize(), start);
        queue.writeWord((2 * queueSize + 1) * wordSize(), end);
        queueSize++;
        if (inParallelPhase) {
            workAvailable.signal();
        }
    }

    /** Takes an entry from the queue, with its end left in the thread's state, or returns null. */
    @Uninterruptible(reason = CORE_GC_CODE)
    private Pointer pop(Pointer state) {
        if (queueSize == 0) {
            return Word.nullPointer();
        }
        queueSize--;
        state.writeWord(POPPED_END_SLOT * wordSize(), queue.readWord((2 * queueSize + 1) * wordSize()));
        return queue.readWord(2 * queueSize * wordSize());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static Pointer getPoppedEnd(Pointer state) {
        return state.readWord(POPPED_END_SLOT * wordSize());
    }

    /**
     * Whether a collection on several threads has a worker to share the work with, starting the
     * workers the first time. On one processor the serial collection is faster.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    boolean hasWorkers() {
        if (!initialized) {
            initialize();
        }
        return workers > 0;
    }

    /* The workers. */

    /** The processors the image may use, as Runtime.availableProcessors counts them: the option, then the container's limit. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static int activeProcessorCount() {
        int option = SubstrateOptions.ActiveProcessorCount.getValue();
        if (option > 0) {
            return option;
        }
        if (Container.isSupported() && Container.singleton().isContainerized()) {
            return Container.singleton().getActiveProcessorCount();
        }
        return Jvm.JVM_ActiveProcessorCount();
    }

    @Uninterruptible(reason = CORE_GC_CODE)
    private void initialize() {
        /* The thread that runs the collection scans too, so it counts as one of them. */
        int requested = SerialGCOptions.ParallelYoungCollectionThreads.getValue();
        int count = (requested > 0 ? requested : activeProcessorCount()) - 1;
        int limit = MAX_WORKERS < workerVisitors.length ? MAX_WORKERS : workerVisitors.length;
        workers = count < 0 ? 0 : (count > limit ? limit : count);

        queueCapacity = 1024;
        queue = UntrackedNullableNativeMemory.malloc(Word.unsigned(queueCapacity).multiply(2 * wordSize()));
        states = UntrackedNullableNativeMemory.calloc(Word.unsigned(workers + 1).multiply(stateWords()).multiply(wordSize()));
        threads = UntrackedNullableNativeMemory.calloc(Word.unsigned(workers).multiply(wordSize()));
        keptChunks = UntrackedNullableNativeMemory.calloc(Word.unsigned(workers + 1).multiply(2 * wordSize()));
        VMError.guarantee(queue.isNonNull() && states.isNonNull() && threads.isNonNull() && keptChunks.isNonNull(), "Out of native memory for the parallel collector.");
        for (int i = 0; i <= workers; i++) {
            stateOf(i).writeWord(ISOLATE_SLOT * wordSize(), CurrentIsolate.getIsolate());
        }
        for (int i = 0; i < workers; i++) {
            workerVisitors[i].getObjRefVisitor().setWorkerState(stateOf(i));
        }

        /* Start the workers and wait until each is waiting for work. */
        mutex.lockNoTransitionUnspecifiedOwner();
        try {
            busy = workers;
            inParallelPhase = true;
            for (int i = 0; i < workers; i++) {
                OSThreadHandle thread = PlatformThreads.singleton().startThreadUnmanaged(workerRoutine.getFunctionPointer(), stateOf(i), 0, false);
                VMError.guarantee(thread.isNonNull(), "Could not start a thread of the parallel collector.");
                threads.writeWord(i * wordSize(), thread);
            }
            while (inParallelPhase && workers > 0) {
                allIdle.blockNoTransitionUnspecifiedOwner();
            }
            inParallelPhase = false;
        } finally {
            mutex.unlockNoTransitionUnspecifiedOwner();
        }
        initialized = true;
    }

    @Uninterruptible(reason = "The heap base is not set up yet.")
    @CEntryPoint(include = Enabled.class, publishAs = Publish.NotPublished)
    @CEntryPointOptions(prologue = WorkerPrologue.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    private static WordBase workerRun(Pointer state) {
        try {
            GCImpl.getGCImpl().getParallelGC().work(state);
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
        return Word.nullPointer();
    }

    @Uninterruptible(reason = CORE_GC_CODE)
    private void work(Pointer state) {
        GreyToBlackObjectVisitor visitor = visitorOf(state);
        while (true) {
            Pointer entry;
            mutex.lockNoTransitionUnspecifiedOwner();
            try {
                entry = pop(state);
                while (entry.isNull() && !hasOwnWork(state)) {
                    if (--busy == 0) {
                        endPhase();
                    }
                    do {
                        workAvailable.blockNoTransitionUnspecifiedOwner();
                    } while (!inParallelPhase);
                    busy++;
                    entry = pop(state);
                }
            } finally {
                mutex.unlockNoTransitionUnspecifiedOwner();
            }
            if (entry.isNonNull()) {
                scan(entry, getPoppedEnd(state), visitor);
            } else {
                scanOwnChunks(state, visitor);
            }
        }
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private GreyToBlackObjectVisitor visitorOf(Pointer state) {
        for (int i = 0; i < workers; i++) {
            if (stateOf(i).equal(state)) {
                return workerVisitors[i];
            }
        }
        throw VMError.shouldNotReachHere("not the state of a worker");
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static boolean hasOwnWork(Pointer state) {
        for (int t = 0; t < targets(); t++) {
            AlignedHeader chunk = getAllocationChunk(state, t);
            if (chunk.isNonNull() && HeapChunk.getTopPointer(chunk).aboveThan(getScanStart(state, t))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans a queue entry: an unaligned chunk, or the objects of an aligned chunk from the entry up
     * to its end, and not on to the chunk's top, as the thread that owns the chunk may still copy
     * into it.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    private static void scan(Pointer entry, Pointer end, GreyToBlackObjectVisitor visitor) {
        if (entry.and(UNALIGNED_BIT).notEqual(0)) {
            UnalignedHeapChunk.walkObjectsInline((UnalignedHeader) entry.and(Word.unsigned(UNALIGNED_BIT).not()), visitor);
            return;
        }
        Pointer p = entry;
        while (p.belowThan(end)) {
            Object obj = p.toObjectNonNull();
            Pointer next = p.add(LayoutEncoding.getSizeFromObjectInlineInGC(obj));
            visitor.visitObject(obj);
            p = next;
        }
    }

    /**
     * Scans what a worker has copied into its own chunks. Scanning copies more, possibly into the
     * chunk being scanned, which is scanned on to its end; if that chunk is replaced meanwhile, it
     * is not queued, as this thread scans it to its end.
     */
    @Uninterruptible(reason = CORE_GC_CODE)
    private static void scanOwnChunks(Pointer state, GreyToBlackObjectVisitor visitor) {
        for (int t = 0; t < targets(); t++) {
            AlignedHeader chunk = getAllocationChunk(state, t);
            if (chunk.isNull() || !HeapChunk.getTopPointer(chunk).aboveThan(getScanStart(state, t))) {
                continue;
            }
            setScannedChunk(state, chunk);
            AlignedHeapChunk.walkObjectsFromInline(chunk, getScanStart(state, t), visitor);
            setScannedChunk(state, Word.nullPointer());
            if (getAllocationChunk(state, t).equal(chunk)) {
                setScanStart(state, t, HeapChunk.getTopPointer(chunk));
            }
        }
    }

    /* A check, for debugging. */

    private final CopyChecker copyChecker = new CopyChecker();

    /**
     * Fails if an object that this collection copied refers to an object left in a from-space of
     * the young generation, which is released after the collection: the reference was not updated,
     * as when a copy is never scanned. Called once references are processed and before the
     * from-spaces are released. The copies are the survivor to-spaces, the old generation's
     * to-space, and what was added to the chunks kept in the old generation.
     */
    void checkCopies() {
        HeapImpl heap = HeapImpl.getHeapImpl();
        YoungGeneration young = heap.getYoungGeneration();
        for (int i = 0; i < young.getMaxSurvivorSpaces(); i++) {
            young.getSurvivorToSpaceAt(i).walkObjects(copyChecker);
        }
        Space oldToSpace = ((CopyingOldGeneration) heap.getOldGeneration()).getToSpace();
        oldToSpace.walkObjects(copyChecker);
        for (int i = 0; i <= workers; i++) {
            AlignedHeader chunk = (AlignedHeader) keptChunks.readWord(2 * i * wordSize());
            if (chunk.isNonNull() && HeapChunk.getSpace(chunk) != oldToSpace) {
                HeapChunk.walkObjectsFrom(chunk, keptChunks.readWord((2 * i + 1) * wordSize()), copyChecker);
            }
        }
    }

    private static final class CopyChecker implements ObjectVisitor, ObjectReferenceVisitor {
        @Platforms(Platform.HOSTED_ONLY.class)
        CopyChecker() {
        }

        @Override
        public void visitObject(Object obj) {
            InteriorObjRefWalker.walkObject(obj, this);
        }

        @Override
        public void visitObjectReferences(Pointer firstObjRef, boolean compressed, int referenceSize, Object holderObject, int count) {
            Pointer end = firstObjRef.add(Word.unsigned(count).multiply(referenceSize));
            for (Pointer objRef = firstObjRef; objRef.belowThan(end); objRef = objRef.add(referenceSize)) {
                Pointer p = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, compressed);
                if (p.isNull() || HeapImpl.getHeapImpl().isInImageHeap(p)) {
                    continue;
                }
                Word header = ObjectHeaderImpl.getObjectHeaderImpl().readHeaderFromPointer(p);
                if (ObjectHeaderImpl.isMarkedHeader(header)) {
                    continue;
                }
                boolean forwarded = ObjectHeaderImpl.isForwardedHeader(header);
                Space space = null;
                if (!forwarded) {
                    space = ObjectHeaderImpl.isAlignedHeader(header) ? HeapChunk.getSpace(AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p))
                                    : HeapChunk.getSpace(UnalignedHeapChunk.getEnclosingChunkFromObjectPointer(p));
                }
                if (forwarded || (space != null && space.isYoungSpace() && space.isFromSpace())) {
                    Log.log().string("[ParallelYoungCollectionCheck: the copy ").zhex(Word.objectToUntrackedPointer(holderObject)).string(" of ").string(holderObject.getClass().getName())
                                    .string(" refers at offset ").unsigned(objRef.subtract(Word.objectToUntrackedPointer(holderObject))).string(" to ").zhex(p)
                                    .string(forwarded ? ", which was forwarded" : ", which is in the from-space ").string(forwarded ? "" : space.getName())
                                    .string(", header ").zhex(header).string("]").newline();
                    throw VMError.shouldNotReachHere("ParallelYoungCollectionCheck failed");
                }
            }
        }

        @Override
        public void visitDerivedReference(Pointer baseObjRef, Pointer derivedObjRef, boolean compressed, Object holderObject) {
        }
    }

    private static final class WorkerPrologue implements CEntryPointOptions.Prologue {
        @Uninterruptible(reason = "prologue")
        @SuppressWarnings("unused")
        public static void enter(Pointer state) {
            CEntryPointSnippets.initBaseRegisters(state.readWord(ISOLATE_SLOT * wordSize()));
            WriteCurrentVMThreadNode.writeCurrentVMThread(Word.nullPointer());
        }
    }

    static final class Enabled implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return isEnabled();
        }
    }
}
