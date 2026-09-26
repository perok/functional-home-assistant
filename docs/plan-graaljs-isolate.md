# Plan — GraalJS in a polyglot isolate, and the runtime that carries it

Server-side JavaScript is the prerequisite for chart rendering. **This branch lands the runtime
and packaging decisions only**, off `main`, so the history view can build on a settled base
instead of carrying a base-image migration and a JDK bump in the same pull request.

The measurement record behind every number here is `docs/spike-compiled-truffle.md`, which arrives
with the history-view branch. The decisive figures are repeated below so this plan stands alone.

## The decision: an Oracle polyglot isolate, with the library outside the jar

Four ways to run ECharts SSR, measured under **this add-on's own JVM flags** (`-Xms64M -Xmx512M
-XX:+UseSerialGC`), 300 points, 300 renders, median of three runs:

| | warm median | RSS | anonymous |
|---|---:|---:|---:|
| interpreted, in-heap | 21 ms | 322 MB | 295 MB |
| community isolate | 10 ms | 421 MB | 315 MB |
| **Oracle isolate** | **10 ms** | **152 MB** | **80 MB** |
| GraalVM JDK, compiled in-heap | 11 ms | 785 MB | 637 MB |

The isolate is not the expensive option it was assumed to be — **it is cheaper in memory than
interpreting**, because the guest heap and ECharts' AST live in the isolate's compact native heap
instead of the JVM's. Against the interpreted alternative it is 2× faster *and* 170 MB smaller,
which is the unusual case where there is no trade to weigh. Given issue #237, the memory column is
the one that decides this.

Oracle over community is a memory decision, not a speed one — they render identically. Oracle's
isolate holds **flat at 99 → 106 MB** anonymous as the series grows 17×, where community goes
328 → 469 MB; three Oracle runs agreed to within 1 MB. That predictability is what makes the route
fit a 4 GB Pi.

**The GraalVM JDK base image is rejected**, not deferred: it is the worst option measured, 5× the
isolate's RSS for a render time nobody can distinguish, and its one distinguishing argument (Pkl)
does not exist — see below. Native image is rejected on reachability cost and because it would
slow the non-chart render loop.

## What actually goes on the classpath

Two classpaths, and the difference between them is the whole packaging design.

**The fat jar** carries four declared coordinates and nothing architecture-specific:

```
org.graalvm.polyglot:polyglot      org.graalvm.sdk:nativebridge
org.graalvm.truffle:truffle-api    org.graalvm.sdk:jniutils
```

Seven jars resolved, 18 MB — but **almost none of it is new**: `pkl-core` already brings polyglot
and truffle-api, at 25.0.1, which these evict. So the real change to the shipped jar is that Pkl
now evaluates on Truffle 25.3.4.1 (789 tests green, including the whole Pkl suite), plus three
small jars. No `js-language`, no `truffle-runtime`, no `truffle-compiler` — nothing that would
pull an in-process Truffle runtime or libgraal into the JVM.

**The add-on adds one more jar at launch**: `js-isolate-linux-<arch>`, staged into the image per
architecture and named on `-cp`, not packaged. It has to be there and it cannot be there: it is
what REGISTERS that a JS isolate exists — without it an engine reports "No native isolate library
is available for the requested language(s) [js]" however the resources are staged (measured) —
while its 159 MB payload is exactly what must not be in an architecture-independent jar. Naming
it on the launcher's classpath satisfies both.

That is also why `run.sh` uses `-cp` and a main class rather than `-jar`, which ignores `-cp`.

The engine is then built with nothing configured in code:

```scala
Engine.newBuilder("js").spawnIsolate(true).build()
```

`spawnIsolate` goes on `Engine.Builder`, **not** `Context.Builder` — on a shared engine the
context-level call is silently ineffective. Contexts are built against that engine with
`HostAccess.SCOPED`. It does not exist on `Engine.Builder` before the 25.3 line (25.0.4 does not
compile against it), so this route pins polyglot to 25.3.x rather than the 25.0.x LTS-aligned one.

### The host runs the FALLBACK Truffle runtime, and that is correct

Truffle calls the host's runtime "fallback" here, which reads like a misconfiguration and is not:
`truffle-runtime` is deliberately absent, because it drags libgraal into the JVM for ~186 MB of
anonymous memory. **Guest compilation happens inside the isolate**, which is a native-image VM
with its own compiler, so the host's runtime is irrelevant to JavaScript speed.

Measured on the SHIPPED classpath, same loop, 200M iterations:

| | throughput | fallback warning |
|---|---:|---|
| isolate (what we ship) | **501 M ops/sec** | none |
| in-heap, host fallback runtime | 15.9 M ops/sec | `[engine] WARNING: ... fallback runtime that does not support runtime compilation` |

31×, and Truffle does not consider our engine to be on a fallback runtime at all. `libtruffleattach`
is only the host↔isolate bridge that this configuration needs — a consequence of the host runtime,
not a symptom.

**That in-heap row is a deliberate alternative, not a failure mode we could slip into.** Measured
on the shipped classpath: with the isolate jar present, the isolate is used whether or not
`spawnIsolate(true)` is set (dropping it costs `HostAccess.SCOPED` and earns a method-scoping
warning, not 31× — 178 M ops/sec, still compiled); with the jar absent, `js` is not a language at
all — `A language with id 'js' is not available. Available languages are: [pkl]`. There is no
in-heap JavaScript on this classpath to fall back TO. So both ways of losing the isolate are loud,
and `spawnIsolate(true)` stays because it states the intent and sharpens the error, not because
it is what selects the isolate.

Two things follow. **Do not reach for `Engine.supportsCompilation()` as a health check**: it
reports the HOST runtime and returns `false` in both columns above, so printing it would say
"no compilation" about an engine doing 501 M ops/sec. And **Pkl does run interpreted** in this
JVM, unchanged by this branch — `pkl-core` already brought Truffle without the optimized runtime
and suppresses the warning itself. The spike settled that: its hot `FunctionNode` path bails out
of compilation even WITH an optimized runtime, so the 186 MB would buy very little.

Three things the JVM needs that the classpath does not say:

- **`--enable-native-access=ALL-UNNAMED`**. Truffle `System.load`s the isolate library; on JDK 25
  that is a four-line warning, and on a later JDK it is a hard failure.
- **`-Dpolyglot.engine.userResourceCache=/data/graal-cache`**, where Truffle may unpack its own
  native resources. Left at its default that is `~/.cache`, an image layer here, so every add-on
  update would redo the 161 MB.
- **`libz.so.1`**, which the library names in `DT_NEEDED` alongside glibc. Present in
  `debian-base:9.4.0` on both architectures (checked by unpacking the layers, since nothing here
  can run a container).

## The library is acquired and unpacked by the tools that own those jobs

The isolate library and the polyglot jars are two halves of one engine, and **a mismatch between
them is not reported**. Measured: a 25.2.4 `libpolyglotisolate.so` runs against 25.3.4.1 jars with
no warning, no error and correct output — you are simply running a GraalJS other than the one the
build declares. Worse, `Engine.getVersion()` reports the LIBRARY's version, so the engine holds
both numbers and warns about neither (`docs/issue-report-3-graalvm-polyglot-isolate.md`). That
trap is what shaped the packaging, and the answer to each half was to stop hand-rolling it —
plus `JsIsolateSuite`, which compares the two and fails.

**Acquisition is dependency resolution.** The two platform jars are ordinary `libraryDependencies`
in a hidden Ivy configuration (`js-isolate`, `.hide`), so coursier fetches, checksums and caches
them exactly like every other dependency, from the one `graalVmVersion` in `build.sbt`. `hide`
keeps them off compile, test and assembly classpaths — the fat jar never sees them.
`stageIsolateJars` copies both beside the add-on jar under **buildx's** architecture spelling
(`js-isolate-arm64.jar`, not `aarch64`, and no version), which is why the Dockerfile contains no
version, no coordinate, no URL and no architecture mapping. Nothing downloads inside the container.

**Unpacking is Truffle's own job, and is left to it.** This is the documented default — "on first
use, languages may unpack internal resources, such as standard libraries, into a cache folder" —
and `polyglot.engine.userResourceCache` is the documented, OS-agnostic way to choose *where*. So
the image stages one jar and sets one path, and that is the entire mechanism: no build stage, no
extraction code, no archive layout, and nothing architecture-specific at build time beyond a
`COPY`.

Acquisition has no installer to invoke either, by design: GraalVM retired `gu` in favour of
Maven, and the documentation's own instruction for this feature is to depend on the `-isolate`
artifacts from Maven Central. Declaring the dependency IS the bootstrap.

The cost is a one-time unpack: **161 MB, ~650 ms of CPU** on a warm NVMe box (three cold/warm
pairs: 1342/614, 1232/638, 1275/620 ms), so on slower storage it is however long 161 MB of writes
takes. It recurs only when a GraalVM bump changes the resource hashes. Because `JsIsolate.engine`
is a process-lifetime resource acquired at startup, that lands on add-on start, not on a user's
first chart.

Two things make that acceptable rather than merely cheap:

- **`/data` survives add-on updates**, which is the whole reason the path is chosen rather than
  left at `~/.cache` — an image layer, so every update would redo the work.
- **`backup_exclude` keeps it out of Home Assistant's backups.** A bare directory name prunes the
  subtree: the supervisor matches with `PurePath.match` (from the right) and securetar applies the
  filter to a directory *before* descending. The trap next to it is that `graal-cache/**` matches
  nothing at all, silently, because `match` does not treat `**` as recursive.

Two dead ends worth not re-walking:

- **`Engine.copyResources(Path, String...)` is exactly the right API, and is incomplete.** GraalVM
  documents it for "a read-only file system where the application cannot write resources during
  startup" and "strict startup-time requirements" — our case precisely — with
  `-Dpolyglot.engine.resourcePath` as the runtime counterpart. It runs and writes the isolate out,
  but omits `libtruffleattach`, and an engine pointed at its output then dies with "Polyglot
  isolates require libtruffleattach when running on HotSpot with the fallback Truffle runtime."
  That file IS registered the same way the isolate is — `truffle-api` ships
  `JDKSupportLibTruffleAttachResourceProvider` as an `InternalResourceProvider` — so this looks
  like an upstream gap rather than a misuse, and is worth reporting alongside the Pkl bailout.
  Lifting the one file out of `truffle-api` by hand makes the whole route work (verified).
  A related trap: `engine.resourcePath` is a system property, NOT an engine option — setting it on
  the builder throws "Could not find option with name".
- **`engine.IsolateLibrary` names the `.so` directly and needs no jar.** Truffle's own error text
  annotates it "(for testing purposes only)" and it requires `allowExperimentalOptions`. Rejected
  for a shipped appliance; it remains the fallback if the provider route ever breaks.

**Rejected: unpacking at build time.** It was implemented and measured before being backed out.
Baking the cache into the image removes the runtime unpack, but costs 161 MB of image, a
per-architecture build stage that has to RUN the isolate — under QEMU for aarch64, 32.6 s — and
the both-architecture PR builds that then follow from it. That is a lot of machinery to save a few
seconds once per GraalVM bump, against the grain of how Truffle is meant to be used.

## Multi-architecture, and what is actually architecture-specific

Three facts make this small:

1. **`config.yaml` already declares exactly `amd64` and `aarch64`**, which is exactly the set
   GraalVM publishes Linux isolates for. No architecture is dropped and no fallback path is
   needed.
2. **sbt stages both platforms' jars under buildx's own spelling**, so selecting one is
   `js-isolate-$TARGETARCH.jar` — no mapping anywhere in the Dockerfile.
3. **Nothing architecture-specific is EXECUTED during the build.** One jar is copied; jlink
   remains the only emulated stage. This was briefly not true — an earlier version unpacked the
   cache at build time, which meant running the isolate under QEMU — and reverting that also
   reverted the CI consequence below.

So `target/addon/fh-dashboard.jar` stays the architecture-independent bytecode its Dockerfile
comment promises — the same file in both images — and the only per-architecture byte is a jar
chosen by `COPY`.

Because the build no longer runs arch-specific code, the `image` job is back to two depths:
amd64 on a pull request, both on a push to main, where the isolate check then runs on aarch64
under emulation before any release build.

## The cache: unpacked once, at runtime, into /data

Truffle extracts its native resources on first use — 161 MB, keyed by a SHA-256 of each resource
— and `polyglot.engine.userResourceCache` chooses where. `/data`, because it is the add-on's
persistent volume: it survives restarts and updates, so the unpack recurs only when a GraalVM
bump changes the hashes. The default, `~/.cache`, is an image layer and would redo it on every
update.

Nothing prunes the copy a bump replaces, so `/data/graal-cache` grows by 161 MB per GraalVM
version. Deleting the directory is always safe — it is rebuilt on the next start — and that is
the escape valve if it ever matters. It is not worth code until it does.

`backup_exclude` keeps all of it out of Home Assistant's backups, along with the pkl and asset
caches, which were being backed up before this branch.

## Base image and JDK

The isolate library links `libc.so.6` with versioned `GLIBC_2.x` symbols and names musl nowhere,
so **`eclipse-temurin:21-alpine` must become a glibc builder**. That is forced by this change.

The JDK bump from 21 to the current release is *not* forced, and is worth doing on its own merits
while the base image line is being touched anyway. It is a separate risk, so it gets a separate
commit: a base-image change that keeps the app working is verifiable without any isolate in the
picture, and a bisect can separate "glibc + newer JDK broke something" from "the isolate did".

The isolate itself is indifferent — verified running unchanged on JDK 21 (145 MB RSS) and 25
(152 MB), so nothing here depends on the bump.

## Pkl gets nothing, and should stop shaping these decisions

Pkl was the argument for a GraalVM base image. Measured, it never runs compiled on any JDK,
Oracle GraalVM included — its call targets *are* compiled and then rejected by partial escape
analysis, a frame-materialization bailout in Pkl's own `FunctionNode` (five bailouts against a
JavaScript control's 573 successes on the same JVM). No base image reaches that; it is upstream
Pkl's to fix.

Worth keeping in proportion, too: loading the authoring library and an entity dump is ~40 ms. The
600 ms figure that made Pkl look like a startup problem is `fib(33)`, a synthetic microbenchmark.
**No Pkl work is in scope here.** Reporting the bailout upstream is a cheap follow-up, not part of
this branch.

## Licence

The Oracle isolate is published under the GraalVM Free Terms and Conditions; the community one is
MIT/UPL. GFTC permits what this add-on does — redistributing the unmodified program, including
bundled in a product, provided no fee is charged for it — and carries no expiry or termination
clause. Two obligations follow into the build:

- **the artifacts carry no licence file of their own** — checked: neither the isolate jar nor any
  of the seven polyglot jars contains a `LICENSE`, `NOTICE` or `THIRD_PARTY` entry, so there is
  nothing for the assembly merge strategy to preserve and the notice has to be written by hand;
- downstream users receive that component under GFTC rather than under this project's terms.

Both are stated in `home-addon/DOCS.md` (what a user of the add-on sees) and
`home-addon/README.md`.

Community remains a drop-in fallback at 3–4× the native memory if the licence is ever unwanted.

## Commit sequence

1. This plan.
2. Builder stage to glibc, JDK 21 → current. No functional change; the add-on must still start
   and serve.
3. Isolate dependency, engine construction, library staging in the Dockerfile, licence notes.

## What proves it

`ChartSuite` draws on the real isolate in `testFull`, and `JsIsolateSuite` fails on a library whose
version differs from the jars'. Nothing checks the IMAGE's staged library (right architecture,
links against the base image's glibc and zlib), deliberately: getting that wrong does not break
charts, because `JsIsolate.engineOrInHeap` falls back to the interpreter with a `no GraalJS
isolate` warning in the log. A main run inside the built image in CI did check it, and was dropped
as more build machinery than a slower-but-correct failure is worth.

## Reproducing the measurements

Every number here came from a scratch `javac` harness over coursier classpaths, not from
anything committed. The environment is disposable and has already been lost once, so this is the
recipe rather than a path.

```sh
cs fetch -p org.graalvm.js:js-isolate-linux-amd64:25.3.4.1 \
          org.graalvm.polyglot:polyglot:25.3.4.1        > cp-iso.txt   # the route
cs fetch -p org.graalvm.polyglot:js:25.3.4.1            > cp-interp.txt # interpreted baseline
curl -LO https://cdn.jsdelivr.net/npm/echarts@5.6.0/dist/echarts.min.js
```

One program, two builders. `Spike.java` renders an ECharts SVG N times through
`Context.newBuilder("js")` and prints eval / first-render / warm-median plus `Rss:` and
`Anonymous:` read from `/proc/self/smaps_rollup`. `SpikeI.java` is the same file with the engine
replaced:

```java
Engine.newBuilder("js").allowExperimentalOptions(true).spawnIsolate(true)
      .option("engine.IsolateLibrary", System.getProperty("isolib")).build()
```

and contexts built against it with `HostAccess.SCOPED`. ECharts needs a `setTimeout`/`clearTimeout`
shim defined before it is evaluated, and `animation: false` in the option object, or SSR throws
`ReferenceError: setTimeout is not defined`.

**Always run each configuration at least three times.** Single runs on a machine like this move by
up to 2× on cold-path numbers, and reading one produced three wrong conclusions that survived
until they were repeated. Pass the add-on's own flags — `-Xms64M -Xmx512M -XX:+UseSerialGC` —
because G1 reports systematically more anonymous memory than the add-on actually uses.

Two controls are worth re-running before trusting anything in this file:

- **The named library is load-bearing.** Point `engine.IsolateLibrary` at a path that does not
  exist: engine construction must throw from `PolyglotIsolateHostSupport.buildIsolatedEngine`. If
  it does not, the `.so` is coming from a jar on the classpath and the measurement is of something
  else.
- **Drift is silent.** A 25.2.4 library against 25.3.4.1 jars runs clean. That is the finding, not
  a broken setup.

Extracting the library without a container, which is also what the Dockerfile will do:

```sh
jar xf js-isolate-linux-amd64-25.3.4.1.jar \
  META-INF/resources/engine/js-isolate-linux-amd64/libvm/libpolyglotisolate.so
```

Its glibc floor can be read without running it — the highest `GLIBC_2.*` string in the ELF is
**2.15**, so any modern glibc satisfies it.

## Out of scope

The history view and any chart card; native image; a GraalVM JDK base image; Pkl performance.

## Open questions

- **Everything above is x86_64 on a 32 GB machine, and the target is aarch64 on 4 GB.** Oracle's
  isolate holding flat across a 17× workload change is the best available evidence that it
  transfers, but it is inference. CI now runs the isolate on aarch64, which proves it RUNS and
  says nothing about what it costs — that is QEMU, whose own translation buffers dominate the
  figures (298 MB anonymous before an engine is even built). A Pi run is still the deciding
  evidence and should happen on this branch, before the history view builds on it.

- **Nothing currently deployed can see the number that decides this.** The isolate's memory is a
  native heap inside a `dlopen`ed library, so it is invisible to every instrument the add-on has:
  the OTLP export carries http4s `http.server.*` metrics and traces but no JVM runtime metrics at
  all (no `runtime-telemetry` dependency), JVM heap gauges read MXBeans and would miss it anyway,
  and `-XX:NativeMemoryTracking` accounts for JVM-internal native allocation, not a foreign
  library's own heap. What does see it is RSS and anonymous from `/proc/self/smaps_rollup` — what
  every measurement in the spike used — and the supervisor's own per-add-on memory figure.
  Publishing those two as gauges through the existing `MeterProvider` is small, needs no new
  dependency, and stays free when telemetry is off; it would make the Pi answerable from a normal
  install rather than from benchmarks run on the device. Worth doing before the Pi run, not after.

- **The glibc move is verified by reading, not by building.** No container can be built in the
  agentbox — `unshare(CLONE_NEWUSER)` is denied by its seccomp profile, with `CapEff` empty and no
  docker socket, all of which flake.nix's README lists as deliberate security properties. What
  stands in for a build: reading ELF headers and registry manifests. That is how the library's
  glibc ceiling (2.15 amd64 / 2.17 aarch64, against Debian 13's 2.41), its `libz.so.1`
  requirement, and the absence of `curl` and `wget` from `eclipse-temurin:25-jdk` were each
  settled without running anything. The `image` CI job
  is what actually builds and now also RUNS it: amd64 on a pull request, both architectures on a
  push to main, reusing `ci`'s `addon-jar` artifact either way. On a release commit the arm64
  build happens twice, once here and once in `cd`; sharing a `type=gha` buildx cache between the
  two jobs would fix that if it ever grates.
- Whether `-XX:+UseCompactObjectHeaders` helps. It did nothing measurable in the chart benchmark,
  but that benchmark barely uses the JVM heap — the place it would act is the app's own object
  graph, which needs `RenderBench` or a Pi run to answer.
- Behaviour under concurrent renders. One engine is shared for the life of the process; nothing
  here measured two charts rendering at once.
