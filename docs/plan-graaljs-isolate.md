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

## Out of scope

The history view and any chart card; native image; a GraalVM JDK base image; Pkl performance.

## Open questions

- **Everything above is x86_64 on a 32 GB machine, and the target is aarch64 on 4 GB.** Oracle's
  isolate holding flat across a 17× workload change is the best available evidence that it
  transfers, but it is inference. The add-on already jlinks `jcmd`, JFR and NMT for exactly this;
  a Pi run is the deciding evidence and should happen on this branch, before the history view
  builds on it.
- Whether `-XX:+UseCompactObjectHeaders` helps. It did nothing measurable in the chart benchmark,
  but that benchmark barely uses the JVM heap — the place it would act is the app's own object
  graph, which needs `RenderBench` or a Pi run to answer.
- Behaviour under concurrent renders. One engine is shared for the life of the process; nothing
  here measured two charts rendering at once.
