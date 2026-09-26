# Issue report 3 — GraalVM polyglot isolates: two gaps found while packaging one

- **Against:** `org.graalvm.polyglot:polyglot`, `org.graalvm.truffle:truffle-api`,
  `org.graalvm.js:js-isolate-linux-{amd64,aarch64}` **25.3.4.1**, JDK 25, Linux, HotSpot with
  the fallback Truffle runtime (no `truffle-runtime` on the classpath — deliberate, see
  `docs/adr/0032-a-chart-is-bytes.md`)
- **Status:** neither filed upstream — this is the write-up to file from. A web search found no
  existing report of either; the only nearby `libtruffleattach` result is a different,
  multi-classloader problem.
- **Our workarounds:** issue 1 is avoided by not pre-unpacking at all (Truffle's own first-use
  extraction into a cache we relocate); issue 2 is avoided by resolving both halves from one
  version string, and now *detected* by `fh.view.runtime.JsIsolateSuite`.

Both were found while packaging GraalJS for a Home Assistant add-on. Neither blocks us. Both
would cost someone else a day.

## Issue 1 — `Engine.copyResources` omits `libtruffleattach`, so its output cannot be used

`Engine.copyResources` is documented for exactly the deployment we were attempting: *"a read-only
file system where the application cannot write resources during startup"* and *"strict startup-time
requirements"*, with `-Dpolyglot.engine.resourcePath` as the runtime counterpart. It writes the
isolate out and reports success, but the result is not usable.

### Repro

Classpath: `polyglot` + `truffle-api` + `nativebridge` + `jniutils` + `js-isolate-linux-amd64`,
all 25.3.4.1.

```java
System.out.println(Engine.copyResources(Path.of("out")));   // prints: true
```

Produces:

```
out/engine/js-isolate-linux-amd64/libpolyglotisolate.so
out/engine/js-isolate-linux-amd64/external_isolate/launcher
```

Then:

```sh
java -Dpolyglot.engine.resourcePath=out ...
```

```java
Engine.newBuilder("js").spawnIsolate(true).build();
```

```
java.lang.IllegalStateException: Polyglot isolates require libtruffleattach when running on
HotSpot with the fallback Truffle runtime. The TruffleAttach library at
'out/engine/libtruffleattach/bin/libtruffleattach.so' could not be loaded: …
```

### Why this looks like a gap rather than misuse

`libtruffleattach` is registered through **the same mechanism as the isolate**. `truffle-api`
ships, in `META-INF/services/com.oracle.truffle.api.provider.InternalResourceProvider`:

```
com.oracle.truffle.polyglot.JDKSupportLibTruffleAttachResourceProvider
```

and carries the payload for every platform, `linux/aarch64` included:

```
META-INF/resources/engine/libtruffleattach/linux/amd64/bin/libtruffleattach.so
META-INF/resources/engine/libtruffleattach/linux/aarch64/bin/libtruffleattach.so
```

So a registered internal resource is skipped by the API whose job is to copy internal resources,
and the engine then demands the file it was not given.

A second, possibly related observation: both providers report `getComponentId() == "engine"`
(readable with `javap -c`), but passing that id is rejected —

```java
Engine.copyResources(Path.of("out"), "engine");
// IllegalArgumentException: Components with ids engine are not installed.
//                           Installed components are: debugger, sandbox.
```

— while the no-argument form copies the isolate and not `libtruffleattach`. Whatever set the
no-arg form iterates is not the set of registered providers.

### Workaround, which works

Extract the one file out of `truffle-api` into the layout `resourcePath` expects:

```
META-INF/resources/engine/libtruffleattach/linux/<arch>/bin/libtruffleattach.so
  ->  <dir>/engine/libtruffleattach/bin/libtruffleattach.so
```

With that in place, `-Dpolyglot.engine.resourcePath=<dir>` starts, runs JavaScript, and writes
nothing to the user resource cache. Verified.

### Why we did not ship the workaround

It requires reaching into another artifact at a path that is an implementation detail, which is
what the rest of our packaging exists to avoid. We let Truffle extract at first use instead, and
relocate the cache — cheap for us because the write target survives updates.

## Issue 2 — an isolate library that does not match the polyglot jars runs silently

Truffle version-checks polyglot artifacts against each other and complains loudly when they
disagree. The **isolate library** escapes that check.

### Repro

Take the 25.3.4.1 jars and a 25.2.4 `libpolyglotisolate.so`:

```java
Engine e = Engine.newBuilder("js").allowExperimentalOptions(true).spawnIsolate(true)
    .option("engine.IsolateLibrary", "<25.2.4>/libpolyglotisolate.so").build();
Context c = Context.newBuilder("js").engine(e).allowHostAccess(HostAccess.SCOPED).build();
System.out.println(c.eval("js", "[1,2,3].map(x=>x*2).join()").asString());
System.out.println(e.getVersion());
```

```
2,4,6
25.2.4
```

No warning, no error, correct output — on a build that declares 25.3.4.1 everywhere else. The
control says the named path really is what loaded: point `engine.IsolateLibrary` at a
non-existent file and engine construction throws from
`PolyglotIsolateHostSupport.buildIsolatedEngine`.

### What makes it worth reporting

**The information to detect it is already there and is not used.** `Engine.getVersion()` returns
`25.2.4` — the library's version — while the jars carry theirs on the classpath at
`META-INF/graalvm/org.graalvm.polyglot/version`. Truffle has both numbers at engine-construction
time and says nothing, having warned about the equivalent jar-to-jar mismatch.

A warning at engine construction, matching the one for mismatched polyglot artifacts, would be
enough.

### Our detection, which anyone can copy

`JsIsolateSuite` compares the two and fails:

```
GraalJS drift: polyglot jars are 25.3.4.1, the isolate library is 25.2.4
```

## Not worth filing: the Pkl compilation bailout

For completeness, since it comes up in the same measurements. Pkl's call targets compile and are
then rejected by partial escape analysis — a frame-materialization bailout in Pkl's own
`FunctionNode` (`SourceStackTraceBailoutException: Object of type FrameWithoutBoxing should not
be materialized`), five bailouts against a JavaScript control's 573 successes on the same JVM.
It belongs to apple/pkl, not to GraalVM.

We are not filing it, because it does not cost us anything worth the words: Pkl runs at startup
and on dashboard edits, never on the render hot path, and loading the authoring library plus an
entity dump is ~40 ms. The measurement that made it look expensive was `fib(33)`, a synthetic
microbenchmark. What the spike measured is summarised in ADR 0032.

The same reasoning would NOT apply to CEL, which does sit on the render hot path — but CEL is
`dev.cel`, plain Java with no Truffle or GraalVM code or dependencies at all (checked), so none
of this touches it.
