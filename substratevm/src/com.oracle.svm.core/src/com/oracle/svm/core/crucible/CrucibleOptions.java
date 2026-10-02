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

import java.util.List;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;

/** Build-time and run-time options of the CrucibleVM profiling pipeline. */
public final class CrucibleOptions {

    @Option(help = "Instrument the image to collect an execution profile that is written at exit.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleInstrument = new HostedOptionKey<>(false) {
        @Override
        protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, Boolean oldValue, Boolean newValue) {
            if (newValue) {
                /* Profile keys are derived from node source positions, so they must be tracked. */
                GraalOptions.TrackNodeSourcePosition.update(values, true);
            }
        }
    };

    @Option(help = "Path of the profile written by an instrumented image at exit.", type = OptionType.User)//
    public static final RuntimeOptionKey<String> CrucibleProfileOutput = new RuntimeOptionKey<>("crucible-profile.json");

    @Option(help = "Seconds between periodic profile writes in an instrumented image; 0 writes only at exit. " +
                    "A profile is otherwise lost entirely if the process is killed rather than shut down.", type = OptionType.User)//
    public static final RuntimeOptionKey<Integer> CrucibleProfileDumpInterval = new RuntimeOptionKey<>(0);

    @Option(help = "Path of a CrucibleVM profile to apply while building this image. The image is built at -O3 unless an optimization level is asked for.", type = OptionType.User)//
    public static final HostedOptionKey<String> CrucibleProfile = new HostedOptionKey<>("") {
        @Override
        protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, String oldValue, String newValue) {
            if (newValue != null && !newValue.isEmpty()) {
                /* Profiles are keyed by node source position, so they must be tracked to apply. */
                GraalOptions.TrackNodeSourcePosition.update(values, true);
            }
        }
    };

    /**
     * Builds at -O3 when a profile is given and no optimization level is: a profile is given for
     * speed, and much of what it is worth is lost below -O3. Whether a level was given cannot be
     * read from the option values, where the builder puts the default before it parses anything,
     * so it is read from the arguments, in either spelling and from wherever they came.
     */
    public static void raiseLevelForProfile(EconomicMap<OptionKey<?>, Object> values, List<String> arguments) {
        if (!(values.get(CrucibleProfile) instanceof String profile) || profile.isEmpty()) {
            return;
        }
        String option = "-H:" + SubstrateOptions.Optimize.getName();
        for (String argument : arguments) {
            if (argument.startsWith(option + "=") || argument.startsWith(option + "@")) {
                return;
            }
        }
        SubstrateOptions.Optimize.update(values, SubstrateOptions.OptimizationLevel.O3.getOptionSwitch());
    }

    @Option(help = "Print sample context keys that failed to match when applying a CrucibleVM profile.", type = OptionType.Debug)//
    public static final HostedOptionKey<Boolean> CrucibleProfileDiagnostics = new HostedOptionKey<>(false);

    @Option(help = "Report every profile lookup whose context contains this substring, hit or miss.", type = OptionType.Debug)//
    public static final HostedOptionKey<String> CrucibleProfileTrace = new HostedOptionKey<>("");

    @Option(help = "Tell the compiler which methods the profile saw running often. This is the only thing that lets upstream " +
                    "apply receiver-type profiles, and therefore devirtualise. Off by default: devirtualisation without inlining " +
                    "measured as a regression, and nothing else consults the flag.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleMarkHotCallers = new HostedOptionKey<>(false);

    @Option(help = "Share of recorded calls a method needs before it counts as a hot caller.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleHotCallerRatio = new HostedOptionKey<>(0.001);

    @Option(help = "Rewrite an indirect call into a type-guarded direct call when the profile shows a dominant receiver. " +
                    "Off by default: measured as a regression, because the resulting direct call cannot be inlined afterwards " +
                    "and a guard costs more than a well-predicted indirect call saves.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleDevirtualize = new HostedOptionKey<>(false);

    @Option(help = "Share of calls one receiver type must account for before its call site is devirtualised.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleDevirtualizeMinimumBias = new HostedOptionKey<>(0.7);

    @Option(help = "Most guarded targets to emit at a single devirtualised call site.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleDevirtualizeMaxTargets = new HostedOptionKey<>(2);

    @Option(help = "Turn a strongly biased virtual call into a type-guarded direct call before inlining. " +
                    "Left unset it follows the optimization level: on at -O2 and below, off at -O3, where the compiler " +
                    "already handles the dispatch and the guard measured as a 2.5% regression.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleTypeGuard = new HostedOptionKey<>(true);

    @Option(help = "Smallest share of the calls at a site that a receiver type needs for the inliner to test for it and inline its method.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleMinimumReceiverShare = new HostedOptionKey<>(0.01);

    @Option(help = "How many times lower the inliner's threshold is inside the methods the run spent its time in. 1 leaves it alone.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleHotRootInlineBoost = new HostedOptionKey<>(1.0);

    @Option(help = "Share of the recorded work a method needs for the inliner to spend more on it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleHotRootShare = new HostedOptionKey<>(0.01);

    @Option(help = "Share of the sampled time a method and what it calls need for its calls to be resolved context by context.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleHotContextShare = new HostedOptionKey<>(0.01);

    @Option(help = "A method entered at most this often, and doing next to none of the recorded work, is compiled as cold.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleColdMaximumCalls = new HostedOptionKey<>(3);

    @Option(help = "Share of the recorded work below which a method entered only a few times counts as cold.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleColdMaximumWorkShare = new HostedOptionKey<>(0.00001);

    @Option(help = "Largest callee, in bytes of bytecode, that a cold method still inlines.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleColdInlineMaximumBytecodes = new HostedOptionKey<>(12);

    @Option(help = "Below -O3, compile the methods the run spent its time in with the inliner settings of -O3.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleHotMethodsAtFullSettings = new HostedOptionKey<>(true);

    @Option(help = "In a recording image, bump branch counters inline rather than through a call.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleInlineCounters = new HostedOptionKey<>(true);

    @Option(help = "In a recording image, also note the order in which methods were first entered, for -H:+CrucibleCodeLayoutByStartup. Costs a call at every method entry.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleRecordStartupOrder = new HostedOptionKey<>(false);

    @Option(help = "Compile a method again for a caller the sampled stacks show it spending time under, so that what it calls can be resolved " +
                    "for that caller alone. Needs a profile with sampled stacks.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleContextClones = new HostedOptionKey<>(true);

    @Option(help = "Least share of the samples a method must account for under one caller to be compiled again for it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleContextCloneMinimumShare = new HostedOptionKey<>(0.01);

    @Option(help = "Most copies made of methods for their callers.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleContextCloneLimit = new HostedOptionKey<>(200);

    @Option(help = "How much sooner the inliner looks into a call the sampled stacks saw time spent under, in units of its own priorities. " +
                    "Needs a profile with sampled stacks.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleHotBonusWhileExpanding = new HostedOptionKey<>(0);

    @Option(help = "How many times over the benefit of inlining a call counts when the sampled stacks saw all of the time at that call spent under it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleHotBonusWhileInlining = new HostedOptionKey<>(0);

    @Option(help = "Fewest samples under a call for the sampled stacks to be believed about where it goes, in place of the counted receivers.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleMinimumSamplesAtCall = new HostedOptionKey<>(32);

    @Option(help = "Tell the inliner where a call goes from the sampled stacks in methods compiled for everyone, and not only in copies made for one caller. " +
                    "Samples count time, so a receiver that is called often and returns quickly can be missing from them; outside a copy the counted receivers are complete.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleSampledTargetsOutsideCopies = new HostedOptionKey<>(true);
    @Option(help = "In a recording image, count a receiver type inline when it is the one the site saw last, and call the runtime only for a new one.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleInlineReceiverCounts = new HostedOptionKey<>(true);

    @Option(help = "In a recording image, mark what is to be counted in each method before inlining, so that every copy of a method counts, " +
                    "including the ones the compiler could simplify because of where they were inlined.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleRecordWithProbes = new HostedOptionKey<>(true);

    @Option(help = "In a recording image, count branches in uninterruptible code as well, which is where the garbage collector is. Needs the inline counters.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleRecordUninterruptible = new HostedOptionKey<>(true);

    @Option(help = "Count what instanceof tests the same way, in every copy of a method. The counts are fuller and recording was slower for it " +
                    "on the one benchmark measured, with no gain in the image built from them.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleRecordTestsWithProbes = new HostedOptionKey<>(false);

    @Option(help = "In a recording image, count branches the same way, in every copy of a method. A branch the compiler can decide " +
                    "where the method was inlined is otherwise not counted there, and the profile of that calling context has one side of it only.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleRecordBranchesWithProbes = new HostedOptionKey<>(true);

    @Option(help = "In a recording image, leave a call with several possible receivers a call, so that the receivers can be counted at it. " +
                    "Turned off, the image inlines as an optimized one does and records contexts more like the ones that will be looked up, at the price of those counts.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleRecordKeepsCallsVirtual = new HostedOptionKey<>(true);

    @Option(help = "Fewest observations a recorded calling context needs before its record is used in place of the call site's own.", type = OptionType.Expert)//
    public static final HostedOptionKey<Long> CrucibleContextMinimumCount = new HostedOptionKey<>(1000L);

    @Option(help = "Least share of a call site's observations a recorded calling context needs before its record is used in place of the site's own.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleContextMinimumShare = new HostedOptionKey<>(0.001);

    @Option(help = "Give the methods the run spent its time in, and the copies made of methods for one caller, the priority inliner's larger budget " +
                    "that Oracle GraalVM's builder gives a hot compilation unit. Pays with sampled stacks and copies; without copies it can cost.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleHotInliningBudget = new HostedOptionKey<>(false);

    @Option(help = "How many times a call counts for more in the inliner's eyes when one of its arguments is an object allocated for it, " +
                    "which escape analysis can remove once the callee is inlined. 1 leaves the inliner as it is.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleFreshArgumentBenefit = new HostedOptionKey<>(1.0);

    @Option(help = "How many times a call counts for more in the inliner's eyes when every value its callee returns is an object the callee allocates, " +
                    "which escape analysis can remove once the callee is inlined. 1 leaves the inliner as it is.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleFreshResultBenefit = new HostedOptionKey<>(1.0);

    @Option(help = "Count a call for more under -H:CrucibleFreshArgumentBenefit only when the object allocated for it is a lambda.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleFreshArgumentLambdasOnly = new HostedOptionKey<>(false);

    @Option(help = "Compiler options for the methods the run spent its time in only, as Name=value:Name=value. For trying a setting out.", type = OptionType.Debug)//
    public static final HostedOptionKey<String> CrucibleHotMethodOptions = new HostedOptionKey<>("");

    @Option(help = "Split the iteration range of a hot counted loop so that its middle part runs without the checks " +
                    "the profile saw almost never fail.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleLoopRangeSplit = new HostedOptionKey<>(true);

    @Option(help = "How lopsided a check inside a loop has to be before the loop's range is split around it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleLoopRangeSplitMinimumBias = new HostedOptionKey<>(0.99);

    @Option(help = "How many lopsided checks a loop needs before splitting its range is worth three copies of it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleLoopRangeSplitMinimumChecks = new HostedOptionKey<>(1);

    @Option(help = "How many iterations per entry the profile has to show before a loop's range is split.", type = OptionType.Expert)//
    public static final HostedOptionKey<Double> CrucibleLoopRangeSplitMinimumFrequency = new HostedOptionKey<>(64.0);

    @Option(help = "Split loop ranges a second time after lowering, around the bounds checks of array accesses.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleLoopRangeSplitAfterLowering = new HostedOptionKey<>(true);

    @Option(help = "Unswitch loops once more after lowering, before splitting their ranges. Lowering is what creates the null check " +
                    "on an array, and until that is moved out of the loop the array's length cannot be read ahead of it.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleLoopRangeSplitUnswitchFirst = new HostedOptionKey<>(false);

    @Option(help = "Split the range of loops that can also be left some other way than by running out, by an exception for one.", type = OptionType.Expert)//
    public static final HostedOptionKey<Boolean> CrucibleLoopRangeSplitManyExits = new HostedOptionKey<>(true);

    @Option(help = "Largest loop, in compiler nodes, whose range is split.", type = OptionType.Expert)//
    public static final HostedOptionKey<Integer> CrucibleLoopRangeSplitMaximumSize = new HostedOptionKey<>(1500);

    @Option(help = "Order the image's code section by how often the profile saw each method run.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleCodeLayout = new HostedOptionKey<>(true);

    @Option(help = "Lay the code section out in the order the profiled run first entered methods, rather than by call count.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleCodeLayoutByStartup = new HostedOptionKey<>(false);

    @Option(help = "Do not inline inside methods the profile never saw run, which is what makes a profiled image smaller.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleColdCodeSize = new HostedOptionKey<>(true);

    @Option(help = "Compile the methods the profile never saw run with the settings of -Os, as a build at that level compiles every method.", type = OptionType.User)//
    public static final HostedOptionKey<Boolean> CrucibleColdOptimizeForSize = new HostedOptionKey<>(true);

    @Option(help = "Share of all recorded executions a callee must account for before it is inlined even when the " +
                    "static budget refuses. Nothing in the community edition uses profiled call counts to decide inlining.", type = OptionType.User)//
    public static final HostedOptionKey<Double> CrucibleHotInlineShare = new HostedOptionKey<>(0.005);

    @Option(help = "How many inlining frames to record with each profile site. A call inside a stream's machinery goes to one place " +
                    "from each of its callers and to a dozen over all of them, and only the frames above it tell those apart; eight " +
                    "was worth 7 to 10% on the stream-heavy benchmarks for a recording image a fifth larger. 1 records the innermost " +
                    "frame alone; 0 the whole context.", type = OptionType.User)//
    public static final HostedOptionKey<Integer> CrucibleMaxContextDepth = new HostedOptionKey<>(8);

    private CrucibleOptions() {
    }
}
