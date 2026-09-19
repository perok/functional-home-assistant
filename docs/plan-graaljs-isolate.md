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

The `js-isolate-*` jar is **not** a dependency. With the isolate library named explicitly, the
embedding side is seven jars and 18 MB:

```
org.graalvm.polyglot:polyglot        # + truffle-api, collections, nativeimage,
                                     #   nativebridge, jniutils, word
```

No `js-language`, no `truffle-runtime`, no `truffle-compiler` — nothing that would pull an
in-process Truffle runtime or libgraal into the JVM. Verified running in this configuration at
135 MB RSS.

The engine is built once and lives for the process:

```scala
Engine.newBuilder("js")
  .allowExperimentalOptions(true)
  .spawnIsolate(true)
  .option("engine.IsolateLibrary", <path staged into the image>)
  .build()
```

`spawnIsolate` goes on `Engine.Builder`, **not** `Context.Builder` — on a shared engine the
context-level call is silently ineffective. Contexts are built against that engine with
`HostAccess.SCOPED`.

`engine.IsolateLibrary` is an experimental option and Truffle prints a "do not use in production"
message for it. Taken deliberately: it is what keeps the fat jar architecture-independent, and the
alternative costs a 159 MB write per library version.

`spawnIsolate` does not exist on `Engine.Builder` before the 25.3 line — 25.0.4 does not compile
against it — so this route pins polyglot to 25.3.x rather than the 25.0.x LTS-aligned line.

## One version, in one place, because drift is silent

The isolate library and the polyglot jars are two halves of one engine, and **a mismatch between
them is not reported**. Measured: a 25.2.4 `libpolyglotisolate.so` runs against 25.3.4.1 jars with
no warning, no error and correct output — you are simply running a GraalJS other than the one the
build declares. The control says the named path really is what loaded: point `engine.IsolateLibrary`
at a path that does not exist and engine construction throws from
`PolyglotIsolateHostSupport.buildIsolatedEngine`.

This is the same shape as the version-lockstep trap in `docs/spike-compiled-truffle.md`, and it
rules out the obvious packaging shortcut: **the Dockerfile must not name a GraalVM version.**
Instead sbt stages the two platform jars into the build context from the single version already in
`build.sbt`, and the Dockerfile selects one by `TARGETARCH` and extracts it. No version string
outside `build.sbt`, so the two halves cannot drift apart in the first place.

The staged path inside the image carries no version either, so `engine.IsolateLibrary` is a
constant.

## Multi-architecture: easy, and cleaner than the default

Three facts make this small:

1. **`config.yaml` already declares exactly `amd64` and `aarch64`**, which is exactly the set
   GraalVM publishes Linux isolates for. No architecture is dropped and no fallback path is
   needed.
2. **The library sits at a fixed path inside the jar**, with no content hash in it:
   `META-INF/resources/engine/js-isolate-linux-<arch>/libvm/libpolyglotisolate.so`. Extracting it
   at build time is a `jar xf`, not a search.
3. **The Dockerfile already has a per-architecture stage** — the jlink stage — and buildx supplies
   `TARGETARCH` there. Only a name mapping is needed: buildx says `arm64`, GraalVM says `aarch64`.

So the architecture-specific 159 MB stays in the image layer that is already built per
architecture, and `target/addon/fh-dashboard.jar` remains the architecture-independent bytecode
its Dockerfile comment promises. The alternative — shipping both platform jars inside the fat jar
— would add ~140 MB to a jar that is copied into both images.

## The cache question, and what the README needs

With the library named explicitly there is **no 159 MB extraction at all**. Measured against an
empty cache directory, the runtime writes 96 KB — a 72 KB `libtruffleattach.so` and its directory.

Had we let it self-extract, the answer would still be "first boot only", because `/data` is the
add-on's persistent volume and survives updates — the same reason `FH_PKL_CACHE_DIR` points there.
But it would be first boot *per library version*: the cache path is keyed by a SHA-256 of the
library, so every add-on update that moves GraalVM re-extracts 159 MB and nothing prunes the old
copy.

That is the argument for naming the library rather than configuring a cache. What is left is a
larger image, which is a one-time pull and needs no note — anyone for whom it matters is reading
the image size already.

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

- the image must carry the GFTC text and Oracle's notices, so **the assembly merge strategy must
  not discard `META-INF` licence files**;
- downstream users receive that component under GFTC rather than under this project's licence,
  which the README should state.

Community remains a drop-in fallback at 3–4× the native memory if the licence is ever unwanted.

## Commit sequence

1. This plan.
2. Builder stage to glibc, JDK 21 → current. No functional change; the add-on must still start
   and serve.
3. Isolate dependency, engine construction, library extraction in the Dockerfile, README notes.

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
  transfers, but it is inference. A Pi run is the deciding evidence and should happen on this
  branch, before the history view builds on it.

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
  stands in for a build: the library's ELF names `GLIBC_2.15` as its ceiling and no `libstdc++`,
  Debian 13 ships glibc 2.41, and `debian-base:9.4.0` was confirmed a single multi-arch manifest
  through the GHCR API. The `image` CI job added on this branch is what actually builds it.
- Whether `-XX:+UseCompactObjectHeaders` helps. It did nothing measurable in the chart benchmark,
  but that benchmark barely uses the JVM heap — the place it would act is the app's own object
  graph, which needs `RenderBench` or a Pi run to answer.
- Behaviour under concurrent renders. One engine is shared for the life of the process; nothing
  here measured two charts rendering at once.
