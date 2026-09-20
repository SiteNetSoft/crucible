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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfile;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.nodes.ProfileData.ProfileSource;
import jdk.vm.ci.code.BytecodePosition;

/**
 * Serves a parsed CrucibleVM profile to the image builder. Registering an instance as the
 * {@link PGOProfilesLookup} singleton is the only wiring pass 2 needs: upstream's
 * {@code PGOApplyProfilesPhase} and the PGO paths of {@code SubstratePriorityInliningPhase} consume
 * it from there.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class CrucibleProfilesLookup implements PGOProfilesLookup {

    /**
     * Records handed to upstream are flattened triples of
     * {@code (successor bci, successor index, count)}; see
     * {@code PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE}.
     */
    private static final int RECORD_SIZE = 3;

    private final List<String> categories;
    private Map<String, Long> callCounts;
    /** Full inlining context, joined with {@link ProfileKey#CTX_SEP}, to conditional records. */
    private Map<String, long[]> byContext;
    /** {@code <methodId>:<bci>} to conditional records, for profiles recorded without inlining. */
    private Map<String, long[]> byPoint;
    /** Method id of the innermost frame of a context, to the total of its successor counts. */
    private Map<String, Long> conditionalTotals;
    /** Receiver types by full inlining context, and by bare point for the fallback. */
    private Map<String, List<CrucibleProfile.ObservedType>> invokesByContext;
    private Map<String, List<CrucibleProfile.ObservedType>> invokesByPoint;
    private Map<String, List<CrucibleProfile.ObservedType>> testsByContext;
    private Map<String, List<CrucibleProfile.ObservedType>> testsByPoint;
    /** Type name to analysis type, built once the hosted universe exists. */
    private Map<String, AnalysisType> typesByName = Map.of();
    /** Total recorded method executions, used to express one method's share of the whole run. */
    private final long totalCalls;
    /**
     * Branches taken plus calls made while each method was the one being compiled, which with the
     * code inlined into it is a fair measure of how much of the run happened there. A call count
     * is not: a method entered once that loops a billion times counts for nothing by it.
     */
    private final Map<String, Long> workByRoot = new HashMap<>();
    private final long totalWork;
    /** Method id to its position in the order the run first entered methods. */
    private Map<String, Integer> firstCallOrder;

    /*
     * Upstream tracks lookup hit rates behind -H:+PGOPrintProfileQuality but only reports them in
     * the enterprise build, so a community-edition image gives no sign of whether a profile was
     * applied or silently ignored. These counters back the summary printed by
     * CrucibleProfileFeature.
     */
    private final AtomicLong conditionalHits = new AtomicLong();
    private final AtomicLong conditionalMisses = new AtomicLong();
    private final AtomicLong contextInsensitiveHits = new AtomicLong();
    private final AtomicLong typeHits = new AtomicLong();
    private final AtomicLong typeMisses = new AtomicLong();
    private final Queue<String> sampleMisses = new ConcurrentLinkedQueue<>();
    private static final int MAX_SAMPLES = 5;
    private final Queue<String> traced = new ConcurrentLinkedQueue<>();
    private final String trace = CrucibleOptions.CrucibleProfileTrace.getValue();

    public CrucibleProfilesLookup(CrucibleProfile profile) {
        this.categories = List.copyOf(profile.categories());
        this.callCounts = new HashMap<>();
        this.byContext = new HashMap<>();
        this.byPoint = new HashMap<>();
        this.conditionalTotals = new HashMap<>();
        this.invokesByContext = new HashMap<>();
        this.invokesByPoint = new HashMap<>();
        this.testsByContext = new HashMap<>();
        this.testsByPoint = new HashMap<>();
        this.firstCallOrder = new HashMap<>();

        long calls = 0;
        long totalWorkSeen = 0;
        for (CrucibleProfile.Method method : profile.methods()) {
            long work = 0;
            calls += method.calls();
            callCounts.merge(method.id(), method.calls(), Long::sum);
            if (method.firstCall() != 0) {
                firstCallOrder.merge(method.id(), method.firstCall(), Math::min);
            }
            for (CrucibleProfile.Conditional conditional : method.conditionals()) {
                long[] records = toRecords(conditional);
                List<String> ctx = conditional.ctx();
                if (ctx.isEmpty()) {
                    continue;
                }
                byContext.put(String.join(ProfileKey.CTX_SEP, ctx), records);
                /*
                 * The innermost frame doubles as the context-insensitive key. A method inlined into
                 * several callers yields several records for the same point, and the fallback, which
                 * is what nearly every lookup ends up using, has to speak for all of them: whichever
                 * record happened to come first may be from a caller that hardly ran.
                 */
                byPoint.merge(ctx.get(0), records, CrucibleProfilesLookup::sumRecords);

                String innermost = methodIdOf(ctx.get(0));
                long total = 0;
                for (CrucibleProfile.Successor s : conditional.successors()) {
                    total += s.count();
                }
                conditionalTotals.merge(innermost, total, Long::sum);
                work += total;
            }
            work += method.calls();
            workByRoot.merge(method.id(), work, Long::sum);
            totalWorkSeen += work;
            for (CrucibleProfile.VirtualInvoke invoke : method.virtualInvokes()) {
                List<String> ctx = invoke.ctx();
                if (ctx.isEmpty() || invoke.types().isEmpty()) {
                    continue;
                }
                invokesByContext.put(String.join(ProfileKey.CTX_SEP, ctx), invoke.types());
                invokesByPoint.merge(ctx.get(0), invoke.types(), CrucibleProfilesLookup::sumTypes);
            }
            for (CrucibleProfile.InstanceOfSite test : method.instanceOfs()) {
                if (test.ctx().isEmpty() || test.types().isEmpty()) {
                    continue;
                }
                testsByContext.put(String.join(ProfileKey.CTX_SEP, test.ctx()), test.types());
                testsByPoint.merge(test.ctx().get(0), test.types(), CrucibleProfilesLookup::sumTypes);
            }
        }
        this.totalCalls = calls;
        this.totalWork = totalWorkSeen;
    }

    /**
     * Share of all recorded executions that ran this method, or -1 when the profile says nothing
     * about it. Upstream reads this as an approximation of time spent in the method.
     */
    public double selfTimeShare(HostedMethod method) {
        if (callCounts == null || totalCalls <= 0) {
            return -1;
        }
        Long count = callCounts.get(ProfileKey.methodId(method));
        return count == null ? -1 : (double) count / totalCalls;
    }

    /** Position of this method in the order the run first entered methods, or 0 if never seen. */
    public int firstCallOrder(HostedMethod method) {
        Integer order = firstCallOrder == null ? null : firstCallOrder.get(ProfileKey.methodId(method));
        return order == null ? 0 : order;
    }

    /** Whether the profile saw enough of this method for the inliner to treat it as a hot caller. */
    public boolean isHotCaller(HostedMethod method, double ratio) {
        double share = selfTimeShare(method);
        return share >= 0 && share >= ratio;
    }

    /**
     * Builds the name index the receiver-type profiles are resolved through. Call once the hosted
     * universe exists; until then type profiles resolve to nothing and are reported as misses.
     */
    public void indexTypes(HostedUniverse universe) {
        Map<String, AnalysisType> index = new HashMap<>();
        for (HostedType type : universe.getTypes()) {
            index.putIfAbsent(type.getName(), type.getWrapped());
        }
        typesByName = index;
    }

    /** Share of all recorded work that happened in this method and what was inlined into it. */
    public double workShare(HostedMethod method) {
        if (totalWork <= 0) {
            return 0;
        }
        Long work = workByRoot.get(ProfileKey.methodId(method));
        return work == null ? 0 : (double) work / totalWork;
    }

    /** Adds up two records of the same control split, or keeps the busier if they disagree on its shape. */
    private static long[] sumRecords(long[] a, long[] b) {
        boolean sameShape = a.length == b.length;
        for (int i = 0; sameShape && i < a.length; i += RECORD_SIZE) {
            sameShape = a[i] == b[i] && a[i + 1] == b[i + 1];
        }
        if (!sameShape) {
            return total(a) >= total(b) ? a : b;
        }
        long[] sum = a.clone();
        for (int i = 2; i < sum.length; i += RECORD_SIZE) {
            sum[i] += b[i];
        }
        return sum;
    }

    private static long total(long[] records) {
        long total = 0;
        for (int i = 2; i < records.length; i += RECORD_SIZE) {
            total += records[i];
        }
        return total;
    }

    private static List<CrucibleProfile.ObservedType> sumTypes(List<CrucibleProfile.ObservedType> a, List<CrucibleProfile.ObservedType> b) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (CrucibleProfile.ObservedType type : a) {
            counts.merge(type.name(), type.count(), Long::sum);
        }
        for (CrucibleProfile.ObservedType type : b) {
            counts.merge(type.name(), type.count(), Long::sum);
        }
        List<CrucibleProfile.ObservedType> sum = new java.util.ArrayList<>(counts.size());
        counts.forEach((name, count) -> sum.add(new CrucibleProfile.ObservedType(name, count)));
        return sum;
    }

    private static long[] toRecords(CrucibleProfile.Conditional conditional) {
        List<CrucibleProfile.Successor> successors = conditional.successors();
        long[] records = new long[successors.size() * RECORD_SIZE];
        for (int i = 0; i < successors.size(); i++) {
            CrucibleProfile.Successor s = successors.get(i);
            records[i * RECORD_SIZE] = s.bci();
            records[i * RECORD_SIZE + 1] = s.key();
            records[i * RECORD_SIZE + 2] = s.count();
        }
        return records;
    }

    /** Strips the {@code :<bci>} suffix from a context element. */
    private static String methodIdOf(String contextElement) {
        int separator = contextElement.lastIndexOf(':');
        return separator < 0 ? contextElement : contextElement.substring(0, separator);
    }

    private static String contextKey(BytecodePosition position) {
        List<String> elements = new ArrayList<>();
        for (BytecodePosition p = position; p != null; p = p.getCaller()) {
            elements.add(ProfileKey.methodId(p.getMethod()) + ":" + p.getBCI());
        }
        return String.join(ProfileKey.CTX_SEP, elements);
    }

    @Override
    public Optional<ProfiledValue<Long>> getCallCountProfile(HostedMethod method) {
        if (callCounts == null) {
            return Optional.empty();
        }
        Long count = callCounts.get(ProfileKey.methodId(method));
        return count == null ? Optional.empty() : Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, count));
    }

    @Override
    public long getCallCountOrZero(HostedMethod method) {
        return getCallCountProfile(method).map(ProfiledValue::value).orElse(0L);
    }

    @Override
    public boolean isExecuted(HostedMethod method) {
        return getCallCountOrZero(method) > 0;
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
        if (byContext == null) {
            return Optional.empty();
        }
        String key = contextKey(callingContext);
        long[] records = byContext.get(key);
        if (records == null) {
            /* No match for this exact inlining context: fall back to the point on its own. */
            records = byPoint.get(ProfileKey.methodId(callingContext.getMethod()) + ":" + callingContext.getBCI());
            if (records != null) {
                contextInsensitiveHits.incrementAndGet();
            }
        }
        if (records == null) {
            conditionalMisses.incrementAndGet();
            if (sampleMisses.size() < MAX_SAMPLES) {
                sampleMisses.add(key);
            }
            traceIfRequested("MISS", key);
            return Optional.empty();
        }
        conditionalHits.incrementAndGet();
        traceIfRequested("HIT ", key);
        return Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, records));
    }

    @Override
    public Optional<ProfiledValue<Long>> getTotalConditionalProfileValue(HostedMethod method) {
        if (conditionalTotals == null) {
            return Optional.empty();
        }
        Long total = conditionalTotals.get(ProfileKey.methodId(method));
        return total == null ? Optional.empty() : Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, total));
    }

    @Override
    public long getTotalConditionalProfileValueOrZero(HostedMethod method) {
        return getTotalConditionalProfileValue(method).map(ProfiledValue::value).orElse(0L);
    }

    @Override
    public Optional<Map<AnalysisType, Long>> getVirtualInvokeProfile(BytecodePosition callingContext) {
        if (invokesByContext == null) {
            return Optional.empty();
        }
        List<CrucibleProfile.ObservedType> observed = invokesByContext.get(contextKey(callingContext));
        if (observed == null) {
            observed = invokesByPoint.get(ProfileKey.methodId(callingContext.getMethod()) + ":" + callingContext.getBCI());
        }
        if (observed == null) {
            typeMisses.incrementAndGet();
            return Optional.empty();
        }
        Map<AnalysisType, Long> resolved = new HashMap<>();
        for (CrucibleProfile.ObservedType type : observed) {
            AnalysisType analysisType = typesByName.get(type.name());
            if (analysisType != null) {
                resolved.merge(analysisType, type.count(), Long::sum);
            }
        }
        if (resolved.isEmpty()) {
            /* Every recorded type is gone from this image; that is a miss, not an empty profile. */
            typeMisses.incrementAndGet();
            return Optional.empty();
        }
        typeHits.incrementAndGet();
        traceIfRequested("TYPE", contextKey(callingContext));
        return Optional.of(resolved);
    }

    @Override
    public Optional<Map<AnalysisMethod, Long>> getVirtualInvokeMethodProfile(BytecodePosition callingContext) {
        return Optional.empty();
    }

    @Override
    public Optional<Map<jdk.vm.ci.meta.JavaType, Long>> getInstanceofProfile(BytecodePosition callingContext) {
        if (testsByContext == null) {
            return Optional.empty();
        }
        List<CrucibleProfile.ObservedType> observed = testsByContext.get(contextKey(callingContext));
        if (observed == null) {
            observed = testsByPoint.get(ProfileKey.methodId(callingContext.getMethod()) + ":" + callingContext.getBCI());
        }
        if (observed == null) {
            return Optional.empty();
        }
        Map<jdk.vm.ci.meta.JavaType, Long> resolved = new HashMap<>();
        for (CrucibleProfile.ObservedType type : observed) {
            AnalysisType analysisType = typesByName.get(type.name());
            if (analysisType != null) {
                resolved.merge(analysisType, type.count(), Long::sum);
            }
        }
        return resolved.isEmpty() ? Optional.empty() : Optional.of(resolved);
    }

    @Override
    public Optional<Map<AnalysisType, Long>> getMonitorProfiles() {
        return Optional.empty();
    }

    @Override
    public boolean profileCategoryRecorded(String category) {
        return categories.contains(category);
    }

    /** One line describing how much of the profile the build actually consumed. */
    public String applicationSummary() {
        long hits = conditionalHits.get();
        long misses = conditionalMisses.get();
        long total = hits + misses;
        String rate = total == 0 ? "n/a" : String.format("%.1f%%", 100.0 * hits / total);
        long typeTotal = typeHits.get() + typeMisses.get();
        String typeRate = typeTotal == 0 ? "n/a" : String.format("%.1f%%", 100.0 * typeHits.get() / typeTotal);
        return "Crucible: applied " + hits + " of " + total + " conditional profile lookups (" + rate + "), " +
                        contextInsensitiveHits.get() + " via the context-insensitive fallback; " +
                        typeHits.get() + " of " + typeTotal + " receiver-type lookups (" + typeRate + ").";
    }

    private void traceIfRequested(String outcome, String key) {
        if (!trace.isEmpty() && key.contains(trace)) {
            traced.add(outcome + " " + key);
        }
    }

    /** Lookups matching the {@code -H:CrucibleProfileTrace} substring, in the order they happened. */
    public String tracedLookups() {
        return "Crucible: traced lookups containing \"" + trace + "\":\n  " + String.join("\n  ", traced);
    }

    /** Sample keys that matched nothing, with a few recorded keys to compare them against. */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder("Crucible: sample unmatched lookup contexts:\n");
        for (String miss : sampleMisses) {
            sb.append("  lookup:   ").append(miss).append('\n');
        }
        sb.append("Crucible: sample recorded contexts:\n");
        byContext.keySet().stream().limit(MAX_SAMPLES).forEach(k -> sb.append("  recorded: ").append(k).append('\n'));
        return sb.toString();
    }

    public long conditionalHitCount() {
        return conditionalHits.get();
    }

    @Override
    public void clear() {
        callCounts = null;
        byContext = null;
        byPoint = null;
        conditionalTotals = null;
        invokesByContext = null;
        invokesByPoint = null;
        testsByContext = null;
        testsByPoint = null;
        firstCallOrder = null;
        typesByName = Map.of();
    }
}
