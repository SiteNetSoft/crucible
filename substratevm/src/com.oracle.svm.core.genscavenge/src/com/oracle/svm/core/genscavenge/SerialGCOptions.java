/*
 * Copyright (c) 2022, 2026, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.svm.guest.staging.option.RuntimeOptionKey.RuntimeOptionKeyFlag.RegisterForIsolateArgumentParser;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.genscavenge.compacting.ObjectMoveInfo;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.guest.staging.option.RuntimeOptionValidationSupport;
import com.oracle.svm.guest.staging.option.RuntimeOptionValidationSupport.RuntimeOptionValidation;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.shared.option.SubstrateOptionsParser;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;

/** Options that are only valid for the serial GC (and not for the epsilon GC). */
public final class SerialGCOptions {
    @Option(help = "The garbage collection policy. Default: 'Adaptive2'. Former default: 'Adaptive' (deprecated). Serial GC only.", type = OptionType.User)//
    public static final RuntimeOptionKey<String> InitialCollectionPolicy = new RuntimeOptionKey<>(null, SerialGCOptions::validateInitialCollectionPolicy, RegisterForIsolateArgumentParser) {
        @Override
        public boolean shouldRegisterForIsolateArgumentParser() {
            return SubstrateOptions.useSerialGC() && super.shouldRegisterForIsolateArgumentParser();
        }
    };

    @Option(help = "Percentage of total collection time that should be spent on young generation collections. Serial GC with collection policy 'BySpaceAndTime' only.", type = OptionType.User)//
    public static final RuntimeOptionKey<Integer> PercentTimeInIncrementalCollection = new RuntimeOptionKey<>(50, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Ratio of time spent running to time spent collecting that the young generation is grown to reach: 1 accepts half the time in collection, " +
                    "19 a twentieth. A higher ratio trades memory for throughput. 0 for the policy's own value. Serial GC with collection policy 'Adaptive2' only.", type = OptionType.User)//
    public static final RuntimeOptionKey<Integer> SerialGCTimeRatio = new RuntimeOptionKey<>(0, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Number of young collections an object survives before it is promoted to the old generation: 0 promotes straight from eden, which suits programs " +
                    "whose objects either die at once or live long, and costs programs whose objects live for a few collections. -2, the default, starts at 1 and goes to 0 for as long as " +
                    "most of what survives one collection survives the next as well. -3 starts at 2 instead, comes down to 1 and to 0 as most of what reaches an age reaches the next, " +
                    "and stays at 2 for a program whose objects survive two collections and not a third. -1 lets the policy choose, which starts at 7 and only ever raises it. " +
                    "Serial GC with collection policy 'Adaptive2' only.", type = OptionType.User)//
    public static final RuntimeOptionKey<Integer> SerialGCTenuringThreshold = new RuntimeOptionKey<>(-2, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "The maximum free bytes reserved for allocations, in bytes (0 for automatic according to GC policy). Serial GC only.", type = OptionType.User)//
    public static final RuntimeOptionKey<Long> MaxHeapFree = new RuntimeOptionKey<>(0L, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Bytes of chunks of dead large arrays that a collection keeps committed for the next large arrays instead of returning them to the operating system. " +
                    "A reused chunk is zeroed by the allocation; a fresh one costs a commit, a page fault per page and an uncommit when it dies. 0 returns every chunk. Serial GC only.", type = OptionType.User)//
    public static final RuntimeOptionKey<Long> SerialGCLargeArrayChunkReserve = new RuntimeOptionKey<>(0L, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Determines if a full GC collects the young generation separately or together with the old generation. Serial GC only.", type = OptionType.Expert) //
    public static final RuntimeOptionKey<Boolean> CollectYoungGenerationSeparately = new RuntimeOptionKey<>(null, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Enables card marking for image heap objects, which arranges them in chunks. Automatically enabled when supported. Serial GC only.", type = OptionType.Expert) //
    public static final HostedOptionKey<Boolean> ImageHeapCardMarking = new HostedOptionKey<>(null, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Print summary GC information after application main method returns. Serial GC only.", type = OptionType.Debug)//
    public static final RuntimeOptionKey<Boolean> PrintGCSummary = new RuntimeOptionKey<>(false, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Print the time for each of the phases of each collection, if +VerboseGC. Serial GC only.", type = OptionType.Debug)//
    public static final RuntimeOptionKey<Boolean> PrintGCTimes = new RuntimeOptionKey<>(false, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Verify the remembered set if VerifyHeap is enabled. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> VerifyRememberedSet = new HostedOptionKey<>(true, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Verify all object references if VerifyHeap is enabled. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> VerifyReferences = new HostedOptionKey<>(true, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Verify that object references point into valid heap chunks if VerifyHeap is enabled. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> VerifyReferencesPointIntoValidChunk = new HostedOptionKey<>(false, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Verify write barriers. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> VerifyWriteBarriers = new HostedOptionKey<>(false, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Trace heap chunks during collections, if +VerboseGC. Serial GC only.", type = OptionType.Debug) //
    public static final RuntimeOptionKey<Boolean> TraceHeapChunks = new RuntimeOptionKey<>(false, SerialGCOptions::validateSerialRuntimeOption);

    @Option(help = "Develop demographics of the object references visited. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> GreyToBlackObjRefDemographics = new HostedOptionKey<>(false, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "While a collection scans the objects it copied, prefetch the objects their references point to and visit each reference this many references later; 0 visits each at once. Serial GC only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> GreyScanPrefetchQueue = new HostedOptionKey<>(0, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "With GreyScanPrefetchQueue, issue the prefetch; without, only the order of the visits changes. Serial GC only.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> GreyScanPrefetchIssue = new HostedOptionKey<>(true, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "While a collection visits the references of an object, prefetch what the reference this many slots further on in the same object points to; 0 does not. Serial GC only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> GreyScanPrefetchAhead = new HostedOptionKey<>(8, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "While a collection visits an object it copied, prefetch what the references of the next copied object point to. Serial GC only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> GreyScanPrefetchNextObject = new HostedOptionKey<>(true, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Prefetch while scanning copied objects only in a collection after a young collection that copied at least this many kilobytes; 0 always. Serial GC only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> GreyScanPrefetchMinCopiedKB = new HostedOptionKey<>(1024, SerialGCOptions::validateSerialHostedOption);

    @Option(help = "Ignore the maximum heap size while in VM-internal code. Serial GC only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> IgnoreMaxHeapSizeWhileInVMInternalCode = new HostedOptionKey<>(false, SerialGCOptions::validateSerialHostedOption);

    /** Query these options only through an appropriate method. */
    public static class ConcealedOptions {
        /*
         * Off by default, where the community edition has it on: copying is what Oracle's
         * binaries collect the old generation with, and a complete collection takes 386 ms with
         * it on akka-uct against 533 compacting. With the heap capped at the memory Oracle's
         * binary uses, the twelve Renaissance benchmarks come out 2 to 4% ahead of it copying and
         * level compacting, and none of them falls off the cliff scala-stm-bench7 does
         * compacting. Compacting keeps the heap smaller, which is why it stays: build with
         * -H:+CompactingOldGen where memory is what limits.
         */
        @Option(help = "Collect old generation by compacting in-place instead of copying. Serial GC only.", type = OptionType.Expert) //
        public static final HostedOptionKey<Boolean> CompactingOldGen = new HostedOptionKey<>(false, SerialGCOptions::validateCompactingOldGen);

        @Option(help = "Determines if a remembered set is used, which is necessary for collecting the young and old generation independently. Serial GC only.", type = OptionType.Expert) //
        public static final HostedOptionKey<Boolean> UseRememberedSet = new HostedOptionKey<>(true, SerialGCOptions::validateSerialHostedOption);

        /** Use {@link HeapParameters#getMaxSurvivorSpaces} instead. */
        @Option(help = "Maximum number of survivor spaces. Serial GC only.", type = OptionType.Expert) //
        public static final HostedOptionKey<Integer> MaxSurvivorSpaces = new HostedOptionKey<>(null, SerialGCOptions::validateSerialHostedOption);
    }

    public static class DeprecatedOptions {
        @Option(help = "Ignore the maximum heap size while in VM-internal code. Serial GC only.", type = OptionType.Expert, deprecated = true, deprecationMessage = "Please use the option 'IgnoreMaxHeapSizeWhileInVMInternalCode' instead.")//
        public static final HostedOptionKey<Boolean> IgnoreMaxHeapSizeWhileInVMOperation = new HostedOptionKey<>(false, SerialGCOptions::validateSerialHostedOption) {
            @Override
            protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, Boolean oldValue, Boolean newValue) {
                IgnoreMaxHeapSizeWhileInVMInternalCode.update(values, newValue);
            }
        };

    }

    private SerialGCOptions() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void registerRuntimeOptionValidations() {
        RuntimeOptionValidationSupport.singleton().register(new RuntimeOptionValidation<>(SerialGCOptions::validateInitialCollectionPolicyValue, InitialCollectionPolicy));
    }

    private static void validateSerialHostedOption(HostedOptionKey<?> optionKey) {
        if (optionKey.hasBeenSet() && !SubstrateOptions.useSerialGC()) {
            throw UserError.abort("The option '" + optionKey.getName() + "' can only be used together with the serial garbage collector ('--gc=serial').");
        }
    }

    private static void validateInitialCollectionPolicy(RuntimeOptionKey<String> optionKey) {
        validateSerialRuntimeOption(optionKey);
        validateInitialCollectionPolicyValue(optionKey);
    }

    private static void validateSerialRuntimeOption(RuntimeOptionKey<?> optionKey) {
        if (optionKey.hasBeenSet() && !SubstrateOptions.useSerialGC()) {
            throw UserError.abort("The option '" + optionKey.getName() + "' can only be used together with the serial garbage collector ('--gc=serial').");
        }
    }

    private static void validateInitialCollectionPolicyValue(RuntimeOptionKey<String> optionKey) {
        CollectionPolicies.validatePolicyName(optionKey);

        if (optionKey.hasBeenSet() && !SerialGCOptions.useRememberedSet()) {
            throw invalidOptionValue("Collection policies cannot be used when 'UseRememberedSet' is disabled (attempted to set via '%s').", optionKey.getName());
        }
    }

    private static RuntimeException invalidOptionValue(String message, Object... args) {
        if (SubstrateUtil.HOSTED) {
            throw UserError.abort(message, args);
        }
        throw new IllegalArgumentException(String.format(message, args));
    }

    private static void validateCompactingOldGen(HostedOptionKey<Boolean> compactingOldGen) {
        validateSerialHostedOption(compactingOldGen);
        if (!SubstrateOptions.useSerialGC() || !compactingOldGen.getValue()) {
            return;
        }
        if (!useRememberedSet()) {
            throw UserError.abort("%s requires %s.", SubstrateOptionsParser.commandArgument(ConcealedOptions.CompactingOldGen, "+"),
                            SubstrateOptionsParser.commandArgument(ConcealedOptions.UseRememberedSet, "+"));
        }
        if (SerialAndEpsilonGCOptions.AlignedHeapChunkSize.getValue() > ObjectMoveInfo.MAX_CHUNK_SIZE) {
            throw UserError.abort("%s requires %s.", SubstrateOptionsParser.commandArgument(ConcealedOptions.CompactingOldGen, "+"),
                            SubstrateOptionsParser.commandArgument(SerialAndEpsilonGCOptions.AlignedHeapChunkSize, "<value below or equal to " + ObjectMoveInfo.MAX_CHUNK_SIZE + ">"));
        }
    }

    @Fold
    public static boolean useRememberedSet() {
        return !SubstrateOptions.useEpsilonGC() && ConcealedOptions.UseRememberedSet.getValue();
    }

    @Fold
    public static boolean useCompactingOldGen() {
        return !SubstrateOptions.useEpsilonGC() && ConcealedOptions.CompactingOldGen.getValue();
    }
}
