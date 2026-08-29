# M1: Instrumentation Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `native-image -H:+CrucibleInstrument` produces an image that counts method entries and control-split successor executions and writes `crucible-profile.json` (schema v1) at exit.

**Architecture:** A hosted `InternalFeature` appends `CrucibleInstrumentationPhase` to the end of the high tier; the phase injects a fully-uninterruptible foreign call `CrucibleProfileRuntime.increment(slot)` after the start node and after every successor of each relevant `ControlSplitNode`. A build-time `CounterSlotAllocator` maps slots to `ProfileKey`s; in `afterCompilation` (which runs before image-heap layout) the feature sizes the `long[]` counter array and the `String[]` key table and stores both on the `CrucibleProfileRuntime` image singleton. A tear-down hook streams the JSON profile.

**Tech Stack:** Java 25, Graal compiler phases (`jdk.graal.compiler`), Substrate VM feature API (`InternalFeature`, `ImageSingletons`, `RuntimeSupport`), JUnit 4 via `mx unittest`.

**Spec:** `docs/design/2026-08-29-cruciblevm-pgo-design.md` §3–§6, §8

## Global Constraints

- Never modify upstream Java files. The only upstream file touched is `substratevm/mx.substratevm/suite.py` (one new project entry, spec §3).
- New Java packages exactly: `com.oracle.svm.core.crucible` (in project `com.oracle.svm.core`), `com.oracle.svm.hosted.crucible.instrument` (in `com.oracle.svm.hosted`), tests in new project `com.oracle.svm.crucible.test`.
- No third-party JSON library in `svm.core` (spec §5): the writer is hand-written.
- Every new Java file starts with the license header copied from `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/util/CounterFeature.java`, with the copyright line replaced by `Copyright (c) 2026, CrucibleVM contributors. All rights reserved.` (upstream checkstyle requires a header).
- Commit messages follow the repository's existing style, with no added attribution trailers.
- Always `source crucible/env.sh` first; run `mx` commands from `substratevm/`. Rebuilds after Java edits: `mx build` (incremental, 1–5 min).
- Profile output default: `crucible-profile.json` in the running image's working directory; overridable at run time with `-XX:CrucibleProfileOutput=<path>`.
- Schema (spec §6): `schemaVersion` 1, `producer.tool` = `CrucibleVM`, `producer.graalBase` = `vm-25.3.4.1`, categories `methodCounts` and `conditionalProfiles`.

---

### Task 1: Options, `ProfileKey`, and the test project

**Files:**
- Create: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/CrucibleOptions.java`
- Create: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/ProfileKey.java`
- Create: `substratevm/src/com.oracle.svm.crucible.test/src/com/oracle/svm/crucible/test/ProfileKeyTest.java`
- Modify: `substratevm/mx.substratevm/suite.py` (add project after the `"com.oracle.svm.hosted.test"` entry, ~line 1187)

**Interfaces:**
- Produces: `CrucibleOptions.CrucibleInstrument: HostedOptionKey<Boolean>`, `CrucibleOptions.CrucibleProfileOutput: RuntimeOptionKey<String>`.
- Produces: `ProfileKey` (sealed interface) with records `MethodEntry(String methodId)` and `Conditional(String methodId, List<String> context, int bci, int successor)`; `String encode()`, `static ProfileKey decode(String)`, `static String methodId(ResolvedJavaMethod)`, `static ProfileKey.Conditional forPosition(NodeSourcePosition pos, int successor)`.
- Encoding (used by the runtime table and the writer): `M|<methodId>` and `C|<methodId>|<ctx>|<bci>|<successor>` where `<ctx>` is `#`-joined elements `<methodId>:<bci>` callee-first. `|`, `#`, `:` never occur in JVM descriptors.

- [ ] **Step 1: Add the test project to `suite.py`**

Insert directly after the closing `},` of the `"com.oracle.svm.hosted.test"` project entry:

```python
        "com.oracle.svm.crucible.test": {
            "subDir": "src",
            "sourceDirs": ["src"],
            "dependencies": [
                "mx:JUNIT_TOOL",
                "SVM",
                "compiler:GRAAL_TEST",
            ],
            "requiresConcealed" : {
                "jdk.internal.vm.ci": [
                    "jdk.vm.ci.meta",
                ]
            },
            "checkstyle": "com.oracle.svm.test",
            "workingSets": "SVM,Test",
            "annotationProcessors": [
                "compiler:GRAAL_PROCESSOR",
                "SVM_PROCESSOR",
            ],
            "javaCompliance" : "21+",
            "spotbugs": "false",
            "jacoco" : "exclude",
        },
```
Copy `"javaCompliance"`, `"spotbugs"`, `"jacoco"` values from the neighbouring `com.oracle.svm.hosted.test` entry if they differ.

- [ ] **Step 2: Write the failing test**

```java
package com.oracle.svm.crucible.test;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.ProfileKey;

public class ProfileKeyTest {

    @Test
    public void methodEntryRoundTrips() {
        ProfileKey key = new ProfileKey.MethodEntry("LFoo;.bar(I)V");
        Assert.assertEquals("M|LFoo;.bar(I)V", key.encode());
        Assert.assertEquals(key, ProfileKey.decode(key.encode()));
    }

    @Test
    public void conditionalRoundTrips() {
        ProfileKey key = new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LBaz;.q()V:3", "LFoo;.bar(I)V:17"), 3, 1);
        Assert.assertEquals("C|LFoo;.bar(I)V|LBaz;.q()V:3#LFoo;.bar(I)V:17|3|1", key.encode());
        Assert.assertEquals(key, ProfileKey.decode(key.encode()));
    }

    @Test
    public void conditionalWithoutInliningHasSingleContextElement() {
        ProfileKey.Conditional key = new ProfileKey.Conditional("LFoo;.bar(I)V", List.of("LFoo;.bar(I)V:17"), 17, 0);
        Assert.assertEquals("C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|0", key.encode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownKind() {
        ProfileKey.decode("X|foo");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd substratevm && mx build 2>&1 | tail -3`
Expected: compilation error mentioning `com.oracle.svm.core.crucible.ProfileKey` does not exist.

- [ ] **Step 4: Write `CrucibleOptions`**

```java
package com.oracle.svm.core.crucible;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.core.option.RuntimeOptionKey;
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

    private CrucibleOptions() {
    }
}
```
If `com.oracle.svm.shared.option.HostedOptionKey` does not resolve, use the import that `SubstrateOptions.java` uses for `HostedOptionKey`; likewise copy the `RuntimeOptionKey` import from `SubstrateOptions.java`.

- [ ] **Step 5: Write `ProfileKey`**

```java
package com.oracle.svm.core.crucible;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Identity of one profile counter. Encoded as a single string so the slot table can live in the
 * image heap as a {@code String[]} and be emitted without further lookups at tear-down.
 */
public sealed interface ProfileKey permits ProfileKey.MethodEntry, ProfileKey.Conditional {

    String SEP = "|";
    String CTX_SEP = "#";

    String methodId();

    String encode();

    record MethodEntry(String methodId) implements ProfileKey {
        @Override
        public String encode() {
            return "M" + SEP + methodId;
        }
    }

    record Conditional(String methodId, List<String> context, int bci, int successor) implements ProfileKey {
        @Override
        public String encode() {
            return "C" + SEP + methodId + SEP + String.join(CTX_SEP, context) + SEP + bci + SEP + successor;
        }
    }

    static ProfileKey decode(String s) {
        String[] parts = s.split("\\" + SEP, -1);
        switch (parts[0]) {
            case "M":
                return new MethodEntry(parts[1]);
            case "C":
                List<String> ctx = Arrays.asList(parts[2].split(CTX_SEP, -1));
                return new Conditional(parts[1], ctx, Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
            default:
                throw new IllegalArgumentException("Unknown profile key: " + s);
        }
    }

    /** JVM-style descriptor {@code L<class>;.<name><signature>} of a method. */
    static String methodId(ResolvedJavaMethod method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + method.getSignature().toMethodDescriptor();
    }

    /** Builds the key for successor {@code successor} of the control split at {@code pos}. */
    static Conditional forPosition(NodeSourcePosition pos, int successor) {
        List<String> ctx = new ArrayList<>();
        NodeSourcePosition p = pos;
        while (p != null) {
            ctx.add(methodId(p.getMethod()) + ":" + p.getBCI());
            p = p.getCaller();
        }
        return new Conditional(methodId(pos.getRootMethod()), ctx, pos.getBCI(), successor);
    }
}
```

- [ ] **Step 6: Build and run the test**

Run: `cd substratevm && mx build 2>&1 | tail -2 && mx unittest ProfileKeyTest`
Expected: build OK; `OK (4 tests)`.

- [ ] **Step 7: Commit**

```bash
git add substratevm/mx.substratevm/suite.py substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible substratevm/src/com.oracle.svm.crucible.test
git commit -m "Add Crucible options, profile key encoding, and test project"
```

---

### Task 2: `CrucibleProfileRuntime` — counters, key table, and the increment foreign call

**Files:**
- Create: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/CrucibleProfileRuntime.java`

**Interfaces:**
- Produces: image singleton `CrucibleProfileRuntime` with `static CrucibleProfileRuntime singleton()`, `void install(long[] counters, String[] keys, String imageBuildId)` (hosted-only, called from `afterCompilation`), `long[] counters()`, `String[] keys()`, `String imageBuildId()`.
- Produces: `static final SubstrateForeignCallDescriptor INCREMENT` and `static final LocationIdentity COUNTERS_LOCATION`; target `static void increment(int slot)`.
- Produces: `static final String GRAAL_BASE = "vm-25.3.4.1"`.

- [ ] **Step 1: Write the class**

```java
package com.oracle.svm.core.crucible;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.shared.Uninterruptible;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect;
import jdk.graal.compiler.nodes.NamedLocationIdentity;

/**
 * Run-time storage for instrumentation counters. The arrays are created at build time after all
 * compilations are done ({@code afterCompilation} runs before image-heap layout) and are therefore
 * part of the image heap.
 */
public final class CrucibleProfileRuntime {

    public static final String GRAAL_BASE = "vm-25.3.4.1";

    public static final LocationIdentity COUNTERS_LOCATION = NamedLocationIdentity.mutable("CrucibleCounters");

    public static final SubstrateForeignCallDescriptor INCREMENT = SnippetRuntime.findForeignCall(CrucibleProfileRuntime.class, "increment", CallSideEffect.HAS_SIDE_EFFECT, COUNTERS_LOCATION);

    private long[] counters = new long[0];
    private String[] keys = new String[0];
    private String imageBuildId = "";

    @Platforms(Platform.HOSTED_ONLY.class)
    public CrucibleProfileRuntime() {
    }

    public static CrucibleProfileRuntime singleton() {
        return ImageSingletons.lookup(CrucibleProfileRuntime.class);
    }

    public static boolean isPresent() {
        return ImageSingletons.contains(CrucibleProfileRuntime.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void install(long[] newCounters, String[] newKeys, String newImageBuildId) {
        assert newCounters.length == newKeys.length;
        this.counters = newCounters;
        this.keys = newKeys;
        this.imageBuildId = newImageBuildId;
    }

    public long[] counters() {
        return counters;
    }

    public String[] keys() {
        return keys;
    }

    public String imageBuildId() {
        return imageBuildId;
    }

    /** Target of the foreign call injected by the instrumentation phase. Racy by design. */
    @Uninterruptible(reason = "Called from compiled code without a frame state; must not safepoint.")
    @SubstrateForeignCallTarget(fullyUninterruptible = true, stubCallingConvention = false)
    private static void increment(int slot) {
        long[] c = singleton().counters;
        if (slot >= 0 && slot < c.length) {
            c[slot]++;
        }
    }
}
```
If `SubstrateForeignCallTarget` is not in `com.oracle.svm.core.snippets`, locate it with `grep -rl 'public @interface SubstrateForeignCallTarget' substratevm/src` and fix the import. If `@Uninterruptible` complains that `ImageSingletons.lookup` is interruptible, add `@Uninterruptible(reason = "...", mayBeInlined = true)` to `singleton()`.

- [ ] **Step 2: Build**

Run: `cd substratevm && mx build 2>&1 | tail -2`
Expected: `BUILD SUCCESSFUL` (or no error lines).

- [ ] **Step 3: Commit**

```bash
git add substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/CrucibleProfileRuntime.java
git commit -m "Add profile counter runtime and increment foreign call"
```

---

### Task 3: `CrucibleProfileWriter` — schema v1 JSON emitter

**Files:**
- Create: `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/CrucibleProfileWriter.java`
- Create: `substratevm/src/com.oracle.svm.crucible.test/src/com/oracle/svm/crucible/test/CrucibleProfileWriterTest.java`

**Interfaces:**
- Produces: `static void write(Appendable out, String[] keys, long[] counters, String imageBuildId) throws IOException` (pure, testable) and `static RuntimeSupport.Hook teardownHook()`.
- Output shape is exactly spec §6. Methods are emitted sorted by `methodId`; conditionals sorted by `(bci, ctx)`; successors sorted by `key`. Zero-count successors are omitted; methods with `calls == 0` and no non-zero conditionals are omitted.

- [ ] **Step 1: Write the failing test**

```java
package com.oracle.svm.crucible.test;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.CrucibleProfileWriter;

public class CrucibleProfileWriterTest {

    private static String write(String[] keys, long[] counts) throws IOException {
        StringBuilder sb = new StringBuilder();
        CrucibleProfileWriter.write(sb, keys, counts, "build-1");
        return sb.toString();
    }

    @Test
    public void emitsHeaderAndCategories() throws IOException {
        String json = write(new String[0], new long[0]);
        Assert.assertTrue(json, json.contains("\"schemaVersion\": 1"));
        Assert.assertTrue(json, json.contains("\"tool\": \"CrucibleVM\""));
        Assert.assertTrue(json, json.contains("\"graalBase\": \"vm-25.3.4.1\""));
        Assert.assertTrue(json, json.contains("\"imageBuildId\": \"build-1\""));
        Assert.assertTrue(json, json.contains("\"categories\": [\"methodCounts\", \"conditionalProfiles\"]"));
        Assert.assertTrue(json, json.contains("\"methods\": []"));
    }

    @Test
    public void groupsCountersByMethodAndConditional() throws IOException {
        String[] keys = {
                        "M|LFoo;.bar(I)V",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|0",
                        "C|LFoo;.bar(I)V|LFoo;.bar(I)V:17|17|1",
                        "M|LZzz;.never()V",
        };
        long[] counts = {12, 10, 2, 0};
        String json = write(keys, counts);
        String expected = String.join("\n",
                        "{",
                        "  \"schemaVersion\": 1,",
                        "  \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"vm-25.3.4.1\", \"imageBuildId\": \"build-1\" },",
                        "  \"categories\": [\"methodCounts\", \"conditionalProfiles\"],",
                        "  \"methods\": [",
                        "    {",
                        "      \"id\": \"LFoo;.bar(I)V\",",
                        "      \"calls\": 12,",
                        "      \"conditionals\": [",
                        "        { \"ctx\": [\"LFoo;.bar(I)V:17\"], \"bci\": 17, \"successors\": [ { \"key\": 0, \"count\": 10 }, { \"key\": 1, \"count\": 2 } ] }",
                        "      ]",
                        "    }",
                        "  ]",
                        "}",
                        "");
        Assert.assertEquals(expected, json);
    }

    @Test
    public void escapesQuotesAndBackslashesInIds() throws IOException {
        String json = write(new String[]{"M|La\"b\\c;.m()V"}, new long[]{1});
        Assert.assertTrue(json, json.contains("\"id\": \"La\\\"b\\\\c;.m()V\""));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd substratevm && mx build 2>&1 | tail -3`
Expected: compilation error, `CrucibleProfileWriter` not found.

- [ ] **Step 3: Write the writer**

```java
package com.oracle.svm.core.crucible;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.oracle.svm.core.log.Log;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;

/** Streams the collected counters as a schema-v1 CrucibleVM profile. */
public final class CrucibleProfileWriter {

    private CrucibleProfileWriter() {
    }

    public static RuntimeSupport.Hook teardownHook() {
        return isFirstIsolate -> writeAtExit();
    }

    private static void writeAtExit() {
        if (!CrucibleProfileRuntime.isPresent()) {
            return;
        }
        CrucibleProfileRuntime rt = CrucibleProfileRuntime.singleton();
        Path path = Path.of(CrucibleOptions.CrucibleProfileOutput.getValue());
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            write(w, rt.keys(), rt.counters(), rt.imageBuildId());
        } catch (IOException e) {
            Log.log().string("CrucibleVM: could not write profile to ").string(path.toString()).string(": ").string(e.toString()).newline();
        }
    }

    /** Per-method accumulation used only while writing. */
    private static final class MethodData {
        long calls;
        /* (bci, ctx) -> (successor -> count) */
        final TreeMap<String, TreeMap<Integer, Long>> conditionals = new TreeMap<>();
        final Map<String, ProfileKey.Conditional> exemplars = new TreeMap<>();
    }

    public static void write(Appendable out, String[] keys, long[] counters, String imageBuildId) throws IOException {
        TreeMap<String, MethodData> methods = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            long count = counters[i];
            ProfileKey key = ProfileKey.decode(keys[i]);
            MethodData md = methods.computeIfAbsent(key.methodId(), k -> new MethodData());
            if (key instanceof ProfileKey.MethodEntry) {
                md.calls += count;
            } else if (key instanceof ProfileKey.Conditional c && count != 0) {
                String group = String.format("%010d|%s", c.bci(), String.join(ProfileKey.CTX_SEP, c.context()));
                md.conditionals.computeIfAbsent(group, g -> new TreeMap<>()).merge(c.successor(), count, Long::sum);
                md.exemplars.putIfAbsent(group, c);
            }
        }

        out.append("{\n");
        out.append("  \"schemaVersion\": 1,\n");
        out.append("  \"producer\": { \"tool\": \"CrucibleVM\", \"graalBase\": \"").append(CrucibleProfileRuntime.GRAAL_BASE)
                        .append("\", \"imageBuildId\": \"").append(escape(imageBuildId)).append("\" },\n");
        out.append("  \"categories\": [\"methodCounts\", \"conditionalProfiles\"],\n");

        List<Map.Entry<String, MethodData>> live = new ArrayList<>();
        for (Map.Entry<String, MethodData> e : methods.entrySet()) {
            if (e.getValue().calls != 0 || !e.getValue().conditionals.isEmpty()) {
                live.add(e);
            }
        }
        if (live.isEmpty()) {
            out.append("  \"methods\": []\n}\n");
            return;
        }
        out.append("  \"methods\": [\n");
        for (int m = 0; m < live.size(); m++) {
            String id = live.get(m).getKey();
            MethodData md = live.get(m).getValue();
            out.append("    {\n");
            out.append("      \"id\": \"").append(escape(id)).append("\",\n");
            out.append("      \"calls\": ").append(Long.toString(md.calls));
            if (md.conditionals.isEmpty()) {
                out.append("\n");
            } else {
                out.append(",\n      \"conditionals\": [\n");
                int c = 0;
                for (Map.Entry<String, TreeMap<Integer, Long>> ce : md.conditionals.entrySet()) {
                    ProfileKey.Conditional ex = md.exemplars.get(ce.getKey());
                    out.append("        { \"ctx\": [");
                    for (int i = 0; i < ex.context().size(); i++) {
                        out.append(i == 0 ? "" : ", ").append('"').append(escape(ex.context().get(i))).append('"');
                    }
                    out.append("], \"bci\": ").append(Integer.toString(ex.bci())).append(", \"successors\": [ ");
                    int s = 0;
                    for (Map.Entry<Integer, Long> se : ce.getValue().entrySet()) {
                        out.append(s++ == 0 ? "" : ", ").append("{ \"key\": ").append(se.getKey().toString())
                                        .append(", \"count\": ").append(se.getValue().toString()).append(" }");
                    }
                    out.append(" ] }").append(++c < md.conditionals.size() ? ",\n" : "\n");
                }
                out.append("      ]\n");
            }
            out.append("    }").append(m + 1 < live.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
```
`String.format` is fine here: the writer runs at tear-down, not from uninterruptible code. If `Log` lives elsewhere, take the import from `CounterSupport.java`.

- [ ] **Step 4: Build and run the tests**

Run: `cd substratevm && mx build 2>&1 | tail -2 && mx unittest CrucibleProfileWriterTest ProfileKeyTest`
Expected: `OK (7 tests)`.

- [ ] **Step 5: Commit**

```bash
git add substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/CrucibleProfileWriter.java substratevm/src/com.oracle.svm.crucible.test/src/com/oracle/svm/crucible/test/CrucibleProfileWriterTest.java
git commit -m "Add schema v1 profile writer"
```

---

### Task 4: `CounterSlotAllocator`

**Files:**
- Create: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible/instrument/CounterSlotAllocator.java`
- Create: `substratevm/src/com.oracle.svm.crucible.test/src/com/oracle/svm/crucible/test/CounterSlotAllocatorTest.java`

**Interfaces:**
- Produces: `CounterSlotAllocator` with `int allocate(ProfileKey key)` (thread-safe; same key → same slot), `String[] freeze()` (returns encoded keys indexed by slot, then rejects further allocation), `int size()`.

- [ ] **Step 1: Write the failing test**

```java
package com.oracle.svm.crucible.test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.crucible.instrument.CounterSlotAllocator;

public class CounterSlotAllocatorTest {

    @Test
    public void slotsAreDenseAndStable() {
        CounterSlotAllocator a = new CounterSlotAllocator();
        int s0 = a.allocate(new ProfileKey.MethodEntry("LA;.m()V"));
        int s1 = a.allocate(new ProfileKey.Conditional("LA;.m()V", List.of("LA;.m()V:3"), 3, 0));
        Assert.assertEquals(0, s0);
        Assert.assertEquals(1, s1);
        Assert.assertEquals(s0, a.allocate(new ProfileKey.MethodEntry("LA;.m()V")));
        String[] keys = a.freeze();
        Assert.assertArrayEquals(new String[]{"M|LA;.m()V", "C|LA;.m()V|LA;.m()V:3|3|0"}, keys);
    }

    @Test(expected = IllegalStateException.class)
    public void frozenAllocatorRejectsAllocation() {
        CounterSlotAllocator a = new CounterSlotAllocator();
        a.freeze();
        a.allocate(new ProfileKey.MethodEntry("LA;.m()V"));
    }

    @Test
    public void concurrentAllocationYieldsUniqueSlots() throws Exception {
        CounterSlotAllocator a = new CounterSlotAllocator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<int[]>> futures = IntStream.range(0, 8).mapToObj(t -> pool.submit(() -> IntStream.range(0, 1000)
                            .map(i -> a.allocate(new ProfileKey.MethodEntry("LT" + t + ";.m" + i + "()V"))).toArray())).toList();
            boolean[] seen = new boolean[8000];
            for (Future<int[]> f : futures) {
                for (int slot : f.get()) {
                    Assert.assertFalse("duplicate slot " + slot, seen[slot]);
                    seen[slot] = true;
                }
            }
            Assert.assertEquals(8000, a.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd substratevm && mx build 2>&1 | tail -3`
Expected: compilation error, `CounterSlotAllocator` not found.

- [ ] **Step 3: Write the allocator**

```java
package com.oracle.svm.hosted.crucible.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.svm.core.crucible.ProfileKey;

/** Build-time registry mapping profile keys to dense counter slots. Safe for parallel compilation. */
public final class CounterSlotAllocator {

    private final ConcurrentHashMap<String, Integer> slots = new ConcurrentHashMap<>();
    private final List<String> keysBySlot = new ArrayList<>();
    private volatile boolean frozen;

    public int allocate(ProfileKey key) {
        if (frozen) {
            throw new IllegalStateException("Counter slots are frozen; compilation is complete");
        }
        String encoded = key.encode();
        Integer existing = slots.get(encoded);
        if (existing != null) {
            return existing;
        }
        synchronized (keysBySlot) {
            return slots.computeIfAbsent(encoded, k -> {
                keysBySlot.add(k);
                return keysBySlot.size() - 1;
            });
        }
    }

    public int size() {
        synchronized (keysBySlot) {
            return keysBySlot.size();
        }
    }

    public String[] freeze() {
        frozen = true;
        synchronized (keysBySlot) {
            return keysBySlot.toArray(new String[0]);
        }
    }
}
```

- [ ] **Step 4: Build and run the tests**

Run: `cd substratevm && mx build 2>&1 | tail -2 && mx unittest CounterSlotAllocatorTest`
Expected: `OK (3 tests)`.

- [ ] **Step 5: Commit**

```bash
git add substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible substratevm/src/com.oracle.svm.crucible.test/src/com/oracle/svm/crucible/test/CounterSlotAllocatorTest.java
git commit -m "Add counter slot allocator"
```

---

### Task 5: `CrucibleInstrumentationPhase` and `CrucibleInstrumentFeature`

**Files:**
- Create: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible/instrument/CrucibleInstrumentationPhase.java`
- Create: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible/instrument/CrucibleInstrumentFeature.java`

**Interfaces:**
- Consumes: `CounterSlotAllocator.allocate/freeze`, `CrucibleProfileRuntime.INCREMENT/install`, `CrucibleProfileWriter.teardownHook()`, `ProfileKey.methodId/forPosition`, upstream `ProfilingUtilities.isNotForImplicitException(ControlSplitNode)`, `SubstrateCompilationDirectives.isDeoptTarget(ResolvedJavaMethod)`.
- Produces: the feature is auto-registered and active only when `-H:+CrucibleInstrument`.

- [ ] **Step 1: Write the phase**

```java
package com.oracle.svm.hosted.crucible.instrument;

import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.ProfileKey;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.pgo.ProfilingUtilities;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * Appended to the end of the high tier. Injects a counter increment after the start node (method
 * entry) and after every successor of every profiled control split.
 */
public final class CrucibleInstrumentationPhase extends BasePhase<HighTierContext> {

    private final CounterSlotAllocator allocator;

    public CrucibleInstrumentationPhase(CounterSlotAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null || SubstrateCompilationDirectives.isDeoptTarget(graph.method())) {
            return;
        }
        String rootId = ProfileKey.methodId(graph.method());
        insertIncrement(graph, graph.start(), allocator.allocate(new ProfileKey.MethodEntry(rootId)));

        for (ControlSplitNode split : graph.getNodes(ControlSplitNode.TYPE).snapshot()) {
            NodeSourcePosition pos = split.getNodeSourcePosition();
            if (pos == null || !ProfilingUtilities.isNotForImplicitException(split)) {
                continue;
            }
            int index = 0;
            for (AbstractBeginNode successor : split.successors().filter(AbstractBeginNode.class).snapshot()) {
                insertIncrement(graph, successor, allocator.allocate(ProfileKey.forPosition(pos, index)));
                index++;
            }
        }
    }

    private static void insertIncrement(StructuredGraph graph, FixedWithNextNode after, int slot) {
        ForeignCallNode call = graph.add(new ForeignCallNode(CrucibleProfileRuntime.INCREMENT, ConstantNode.forInt(slot, graph)));
        graph.addAfterFixed(after, call);
    }
}
```
Successor index must match what `PGOApplyProfilesPhase` uses in pass 2: it maps `successors[].key` to `ControlSplitNode.successors()` order, which is exactly the iteration order above. If `ControlSplitNode.TYPE` does not exist, use `graph.getNodes().filter(ControlSplitNode.class)`.

- [ ] **Step 2: Write the feature**

```java
package com.oracle.svm.hosted.crucible.instrument;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.crucible.CrucibleOptions;
import com.oracle.svm.core.crucible.CrucibleProfileRuntime;
import com.oracle.svm.core.crucible.CrucibleProfileWriter;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;

import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;

/** Wires the instrumentation pass into the image build when {@code -H:+CrucibleInstrument}. */
@AutomaticallyRegisteredFeature
public final class CrucibleInstrumentFeature implements InternalFeature {

    private final CounterSlotAllocator allocator = new CounterSlotAllocator();

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CrucibleOptions.CrucibleInstrument.getValue();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(CrucibleProfileRuntime.class, new CrucibleProfileRuntime());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        RuntimeSupport.getRuntimeSupport().addTearDownHook(CrucibleProfileWriter.teardownHook());
    }

    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(CrucibleProfileRuntime.INCREMENT);
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (hosted && !fallback) {
            suites.getHighTier().appendPhase(new CrucibleInstrumentationPhase(allocator));
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        String[] keys = allocator.freeze();
        CrucibleProfileRuntime.singleton().install(new long[keys.length], keys, SubstrateOptions.ImageBuildID.getValue());
    }
}
```
Imports: confirm `AutomaticallyRegisteredFeature` lives in `com.oracle.svm.core.feature` (`CounterFeature.java` imports it) and `SubstrateForeignCallsProvider` in `com.oracle.svm.core.graal.meta` (see `InternalFeature.registerForeignCalls`).

- [ ] **Step 3: Build**

Run: `cd substratevm && mx build 2>&1 | tail -3`
Expected: no errors.

- [ ] **Step 4: Build an instrumented HelloPGO image**

```bash
source crucible/env.sh
crucible/samples/build.sh -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument 2>&1 | tail -5
```
Expected: `Finished generating 'hellopgo'`. If the build fails inside the phase, the error text names the node or descriptor; the two likely causes and fixes:
  - "must not call interruptible method" from `increment`: add `@Uninterruptible(reason = "...", mayBeInlined = true)` to `CrucibleProfileRuntime.singleton()`.
  - a frame-state assertion on the `ForeignCallNode`: change `CallSideEffect.HAS_SIDE_EFFECT` to `CallSideEffect.NO_SIDE_EFFECT` in `CrucibleProfileRuntime.INCREMENT` (the killed `COUNTERS_LOCATION` still keeps the call alive).

- [ ] **Step 5: Run it and inspect the profile**

```bash
cd crucible/samples/out && rm -f crucible-profile.json && ./hellopgo && ls -l crucible-profile.json && python3 - <<'PY'
import json
p = json.load(open("crucible-profile.json"))
assert p["schemaVersion"] == 1 and p["producer"]["tool"] == "CrucibleVM", p["producer"]
step = [m for m in p["methods"] if m["id"].startswith("LHelloPGO;.step(")]
main = [m for m in p["methods"] if m["id"] == "LHelloPGO;.main([Ljava/lang/String;)V"]
assert main and main[0]["calls"] == 1, main
print("methods:", len(p["methods"]), "| step entries:", [ (m["calls"], len(m.get("conditionals", []))) for m in step])
# The i % 10 branch may be inlined into main; find any conditional with a ~9:1 split of 10M samples.
found = False
for m in p["methods"]:
    for c in m.get("conditionals", []):
        counts = sorted((s["count"] for s in c["successors"]), reverse=True)
        if len(counts) == 2 and counts[0] + counts[1] == 10_000_000 and counts[0] == 9_000_000:
            print("skewed branch:", m["id"], "bci", c["bci"], counts); found = True
assert found, "no 9M/1M conditional found"
print("OK")
PY
cd -
```
Expected: `sum=45000000 hot=9000000 cold=1000000`, a non-empty `crucible-profile.json`, and the script prints `skewed branch: … [9000000, 1000000]` then `OK`.

- [ ] **Step 6: Verify a non-instrumented build is unaffected**

```bash
source crucible/env.sh && crucible/samples/build.sh 2>&1 | tail -1 && rm -f crucible/samples/out/crucible-profile.json && ./crucible/samples/out/hellopgo && ls crucible/samples/out/crucible-profile.json 2>&1 | head -1
```
Expected: program output, then `ls: cannot access ...: No such file or directory`.

- [ ] **Step 7: Run all Crucible unit tests and commit**

```bash
cd substratevm && mx unittest ProfileKeyTest CrucibleProfileWriterTest CounterSlotAllocatorTest && cd ..
git add substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible
git commit -m "Add instrumentation phase and feature"
```

---

### Task 6: Profile verification script and documentation

**Files:**
- Create: `crucible/samples/verify-profile.py`
- Modify: `crucible/README.md`

**Interfaces:**
- Produces: `python3 crucible/samples/verify-profile.py <profile.json>` exits 0 iff the HelloPGO profile has the expected shape (reused by the M2 e2e gate).

- [ ] **Step 1: Write the script**

```bash
cat > crucible/samples/verify-profile.py <<'PY'
#!/usr/bin/env python3
"""Checks that a CrucibleVM profile produced by the HelloPGO sample has the expected shape."""
import json
import sys

def main(path):
    with open(path) as f:
        p = json.load(f)
    assert p["schemaVersion"] == 1, p.get("schemaVersion")
    assert p["producer"]["tool"] == "CrucibleVM", p["producer"]
    assert "methodCounts" in p["categories"] and "conditionalProfiles" in p["categories"], p["categories"]
    main_entries = [m for m in p["methods"] if m["id"] == "LHelloPGO;.main([Ljava/lang/String;)V"]
    assert len(main_entries) == 1 and main_entries[0]["calls"] == 1, main_entries
    skewed = [(m["id"], c["bci"]) for m in p["methods"] for c in m.get("conditionals", [])
              if sorted((s["count"] for s in c["successors"]), reverse=True) == [9_000_000, 1_000_000]]
    assert skewed, "no 9M/1M conditional found"
    print("profile OK:", len(p["methods"]), "methods; skewed branch at", skewed[0])

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "crucible-profile.json")
PY
chmod +x crucible/samples/verify-profile.py
python3 crucible/samples/verify-profile.py crucible/samples/out/crucible-profile.json
```
Expected: `profile OK: …` (rebuild the instrumented image first if `out/` was overwritten by Task 5 Step 6).

- [ ] **Step 2: Document usage in `crucible/README.md`**

Append:

```markdown
## Instrumented build (pass 1)

    source crucible/env.sh
    crucible/samples/build.sh -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument
    (cd crucible/samples/out && ./hellopgo)          # writes crucible-profile.json
    python3 crucible/samples/verify-profile.py crucible/samples/out/crucible-profile.json

Run-time override of the output path: `./hellopgo -XX:CrucibleProfileOutput=/tmp/run1.json`.
The profile is written from an isolate tear-down hook, so it is not produced on `SIGKILL` or a crash.
```

- [ ] **Step 3: Commit**

```bash
git add crucible/samples/verify-profile.py crucible/README.md
git commit -m "Add profile verification script and pass 1 documentation"
```

---

## Self-review

- Spec §3 options (`CrucibleInstrument`, `CrucibleProfileOutput`, packages): Task 1. §4.1 placement & deopt-target exclusion, §4.2 what is counted, §4.3 injection + concurrent allocator + `afterCompilation` freeze: Tasks 4–5. §5 runtime singleton, tear-down hook, hand-written streaming writer, write-failure warning: Tasks 2–3. §6 schema: Task 3 (exact-string test). §8 unit tests + sample verification: Tasks 1, 3, 4, 6. Mutual exclusion with `-H:CrucibleProfile` (§3) is deferred to M2 where that option is introduced.
- Names cross-checked: `ProfileKey.encode/decode/methodId/forPosition`, `CounterSlotAllocator.allocate/freeze/size`, `CrucibleProfileRuntime.INCREMENT/install/singleton/isPresent/keys/counters/imageBuildId`, `CrucibleProfileWriter.write/teardownHook` are used with the same signatures in every task.
- Known risk with named fallbacks in Task 5 Step 4 (uninterruptible lookup; frame-state assertion).
