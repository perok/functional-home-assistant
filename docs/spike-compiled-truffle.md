# Spike — compiled Truffle: what is available, and what it buys

A measurement record, not a decision. It answers four questions raised against
`plan-history-view`'s runtime section, one of which turned out to be a correction to that plan and
one of which falsifies the plan's main argument for a GraalVM base image.

Everything below is x86_64, 22 cores, OpenJDK 25.0.4 vs GraalVM CE 25.3.4.1 (JDK 25.0.4.1),
ECharts 5.6.0 SSR at 600×300, `pkl-core` 0.32.1. The spike is a plain `javac` program over
coursier classpaths; the only thing that varies between the GraalJS configurations is
`spawnIsolate` and which `java` runs it.

**Read the RSS figures as a shape, not a requirement.** These JVMs sized their heaps against 30 GB
of RAM, so the absolute numbers say nothing about a Pi.

## Correction: sbt-native-packager DOES work under sbt 2

The plan said native image was closed to us because `sbt-native-packager` has no sbt 2 build. That
was inferred from Maven coordinates — `_2.12_1.0` only, with `_3_2.0` and `_2.12_2.0` both 404 —
and inference was the wrong tool. Tested instead, on sbt **2.0.8**:

```
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
enablePlugins(GraalVMNativeImagePlugin)
```

loads, and its settings resolve — `GraalVMNativeImage/packageBin/target` evaluates, and the only
complaint is a `lintUnused` warning about ten Debian/RPM keys nothing in a scratch project reads.
sbt 2's compatibility layer carries the sbt 1 plugin. Whether `packageBin` then produces a binary
is an environment question (it needs `native-image` or Docker), not a plugin-availability one.

The plan's paragraph is corrected on this branch.

## The headline: Pkl never runs compiled, on any JDK

The plan's argument for a GraalVM base image over polyglot isolates was that only the former would
also speed up **Pkl**, which sits on the slow startup path. Measured, it does not — and the reason
is structural rather than a matter of degree.

**`pkl-core` ships `truffle-api` and nothing else.** Truffle 23.1 split the language API from the
optimizing runtime; `truffle-runtime` and `truffle-compiler` are separate artifacts, and
`pkl-core`'s dependency tree contains neither. So `Truffle.getRuntime().getName()` on a pkl-only
classpath reports **`Interpreted`** — *including on GraalVM CE*. The JDK is not the variable people
assume it is.

That part is fixable for free, and interestingly it fixes itself: GraalJS brings
`truffle-runtime` + `truffle-compiler`, so **adding the chart feature puts the optimizing runtime
on Pkl's classpath as a side effect**. With the app-shaped classpath (GraalJS + `pkl-core`), the
runtime reports `Interpreted` on stock OpenJDK and `GraalVM CE` on GraalVM.

And it still does not help:

| Pkl workload (fresh `Evaluator` each time) | stock OpenJDK 25 | GraalVM CE 25 |
|---|---:|---:|
| load `lib/components.pkl` + `hass.pkl` | 33 / 47 ms | 39 / 42 ms |
| `fib(30)` + 3M-element fold + 200k-element map/join | 290 / 352 ms | 322 / 383 ms |
| `fib(33)` | 596 / 639 ms | 572 / 574 ms |

(first / median)

A second, independent signal says the same thing and says it more precisely. With
`-Dpolyglot.engine.TraceCompilation=true` on GraalVM, the `fib(33)` evaluation logs **zero**
compilations. The control matters here, because a silent zero is exactly the kind of result a
broken harness produces: the same flag, same JVM, on the JS spike logs **245**. So the flag works,
and Pkl's call targets genuinely are never compiled.

Corroborating detail rather than proof: `org/pkl/core/runtime/VmUtils` is the one class in
`pkl-core` referencing `engine.WarnInterpreterOnly`, which it sets while building its `Engine` —
Pkl deliberately silences the fallback-runtime warning, which is what a language does when it has
decided the interpreter is where it lives on the JVM.

**Consequence for the plan: the GraalVM base image loses its distinguishing argument.** It would
speed up the charts and nothing else, which is precisely what the isolate does for less.

### Does anything in Pkl fit the isolate setup?

It is a well-formed Truffle language — `META-INF/services/com.oracle.truffle.api.provider.TruffleLanguageProvider`,
`VmLanguageProvider`, `VmLanguage` — and it is *visible* to the polyglot API: `Engine.getLanguages()`
returns `[pkl, js]`. But `Context.eval("pkl", …)` throws `UnsupportedOperationException: parse`.
Pkl registers as a language while declining the generic entry point; it is driven through
`org.pkl.core.Evaluator`, not through polyglot.

That closes the isolate route for Pkl on its own, and the packaging closes it twice over: a
polyglot isolate is a **prebuilt native library published by the language vendor**, and the
published set is JavaScript, Python and WebAssembly. There is no `pkl-isolate` and could not be one
without Oracle building it.

Pkl's own compiled story is native image, and it is not hypothetical — `pkl-cli-linux-aarch64`
0.32.1 is a **98.6 MB** native binary on Maven Central. So `pkl-core` is proven native-image
compatible by the people who maintain it, which is the one fact that makes route 3 below plausible
at all.

## The three runtimes, measured on the same workload

5 000 points, 300 warm renders, so each configuration reaches whatever peak it has:

| | stock, interpreted | stock + polyglot isolate | GraalVM CE, in-heap |
|---|---:|---:|---:|
| `Truffle.getRuntime()` | `Interpreted` | (isolated) | `GraalVM CE` |
| eval `echarts.min.js` (1 MB) | 988 ms | **307 ms** | 1381 ms |
| first render | 457 ms | **193 ms** | 907 ms |
| warm render, min | 58 ms | 16 ms | **11 ms** |
| warm render, median | 61 ms | 26 ms | **18 ms** |
| RSS | 622 MB | 956 MB | 1009 MB |

The two winners are in different columns, and which column is the real one is a question about our
workload rather than about the runtimes:

- **The isolate owns the cold path**, because its interpreter is already native code. It needs no
  warmup to be three times faster than an interpreted first render.
- **GraalVM in-heap owns the peak**, because there is no boundary at all — but it is the *worst*
  cold path of the three, 907 ms to the isolate's 193 ms, since it pays Graal compilation on top of
  interpretation before it pays off.

A chart render is rare and bursty: someone opens a more-info view. The engine is built lazily on
first chart and then lives for the life of the process, so renders after the first are warm — but
"warm" here is 26 ms against 18 ms, a difference nobody perceives, while the first chart after a
restart is 193 ms against 907 ms, which is the one a person actually waits for. **AOT wins where
the user is looking; JIT wins where they are not.**

## jlink: yes, still, and it keeps libgraal

GraalVM CE ships 85 `jmods` and `lib/libjvmcicompiler.so`. Running the add-on's **exact** module
list from `home-addon/Dockerfile` through GraalVM's `jlink`:

- it works, unchanged — no module additions were needed;
- the output is **94 MB**, and carries `lib/libjvmcicompiler.so` (43.6 MB) into the image;
- the jlinked runtime still reports `Truffle runtime: GraalVM CE`, and reproduces the full JDK's
  numbers (11.4 / 15.9 ms warm, 798 ms first render).

So the jlink stage survives the move; it just gets bigger, and most of the growth is the compiler
we moved for.

One build-time detail that will bite in the Dockerfile: GraalVM's `jlink --strip-debug` shells out
to **`objcopy`**, and fails with `Cannot run program "objcopy"` when binutils is absent. Either add
it to the builder stage or drop the flag. Also `--compress=2` is now deprecated in favour of
`--compress=zip-6`.

## Is going native worth it, for a long-running application?

Not measured — this machine has `native-image` (it ships with GraalVM CE) but no C toolchain, so
nothing could be linked. What the measurements above do support:

**The startup argument does not apply to us.** AOT's headline win is time-to-first-request, and
this process is meant to run for weeks. The add-on does restart on every jar drop
(`inotifywait` + restart in `run.sh`) and whenever HA restarts, but that is minutes of downtime per
month, not a per-request cost.

**The throughput argument runs the wrong way for the dashboard.** GraalVM CE has no PGO — that is
Oracle GraalVM — so an AOT build of the render loop gets whatever a profile-less static compiler
manages, against a C2 that has been watching the real workload. The render path is the part of this
server with genuine hot loops (`RenderBench` measures it in microseconds and the repo has tuned it
twice on profiler output), and it is exactly the part that AOT would make slower.

**The Truffle argument runs the right way**, and is the same effect as the isolate: in a native
image the interpreter is native code and there is no boundary, so charts get the isolate's cold
path *and* GraalVM's peak.

**The cost is a reachability problem across the whole dependency set**, not a build flag. The
runtime carries `dev.cel:cel` (protobuf-backed), `mustache.java`, smithy4s, circe, chimney,
cats-effect and http4s — the protobuf and Mustache reflection surfaces are where native-image
projects spend their weeks, and `pkl-core` ships **no** `META-INF/native-image` metadata in its jar
(Oracle configures it in Pkl's own build), so our Pkl embedding would need that config written
from scratch.

So: a large, ongoing build-complexity cost, to make the rare path faster and the hot path slower,
for a startup win we do not need. Native image is the wrong shape for this application — with the
honest caveat that the throughput claim is reasoning from the absence of PGO, not a measurement,
and that the memory win (the one thing nobody disputes about AOT) could matter on a Pi in a way
nothing here can show.

## Where that leaves the three routes

| | compiled charts | compiled Pkl | add-on image | cost |
|---|---|---|---|---|
| **Polyglot isolates** | yes, best cold | no | must leave musl | +15 MB jar, 143 MB unpacked, 2 lines of code |
| **GraalVM base image** | yes, best peak | **no** — measured | must leave musl | 94 MB jlink (+~40 MB), no code change |
| **Native image** | yes, both | yes | must leave musl | reachability config for the whole tree |

The musl row is not a tie-break, it is a shared entry fee: `libpolyglotisolate.so` links
`libc.so.6` with `GLIBC_2.x` symbols and names musl nowhere, a GraalVM JDK is glibc, and
native-image output is glibc unless built `--libc=musl`. `home-addon/Dockerfile` jlinks from
`eclipse-temurin:21-alpine` onto `ghcr.io/hassio-addons/base` — musl, proven by a musl-linked JRE
running there at all. Any of the three costs a glibc base.

**What changed against the plan**: the GraalVM base image was the route with the extra prize, and
the prize is not there. If we ever need compiled charts, polyglot isolates are the cheaper way to
get the number that is actually felt, and the decision to build the history view interpreted stands
either way.

## Reproducing

Nothing here is committed as a test; it was a scratch spike (`Spike.java`, `Coexist.java`,
`PklBench.java`, `Rt.java`) over four coursier classpaths:

```
org.graalvm.polyglot:js-community:25.3.4.1          # interpreted / in-heap
org.graalvm.polyglot:js-isolate-community:25.3.4.1  # isolate (pulls all 4 platforms; pick one)
org.pkl-lang:pkl-core:0.32.1
```

`Rt.java` is the one worth keeping in mind for any future question in this area: three lines
printing `Truffle.getRuntime().getName()`, which is the only reliable way to know which of the
three runtimes a given classpath and JDK actually gave you. Every wrong assumption in this spike
would have been caught by running it first.
