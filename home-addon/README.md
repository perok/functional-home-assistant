# FH Dashboard add-on

A Home Assistant add-on serving the [functional-home-assistant](https://github.com/perok/functional-home-assistant)
Datastar dashboard (`fh-datastar-view`): dashboards authored in
[Pkl](https://pkl-lang.org), rendered server-side, kept live with SSE patches.

- **Ingress**: appears in the HA sidebar, authenticated by HA.
- **User-editable**: dashboards are seeded to the add-on config dir on first
  start and hot-reload on edit. See [DOCS.md](DOCS.md).
- **Image**: `ghcr.io/perok/fh-dashboard` (amd64 + aarch64), built by GitHub
  Actions from [home-addon/Dockerfile](Dockerfile). Releasing = merging a
  `version:` bump in [config.yaml](config.yaml) to main; the workflow builds,
  publishes, and creates the matching `vX.Y.Z` tag.
- **GraalJS**: the image carries a GraalVM JavaScript isolate library, Oracle's
  build, under the [GraalVM Free Terms and Conditions](https://www.oracle.com/downloads/licenses/graal-free-license.html)
  — free to redistribute bundled in a product as long as nothing is charged
  for it, which is what this add-on does. The community build is a drop-in
  replacement under MIT/UPL at 3–4× the native memory, if the terms are ever
  unwanted. The version is written once, in `build.sbt`: sbt resolves both
  platforms' libraries as ordinary dependencies and stages them for the image
  build, so nothing downloads inside the container and the library cannot
  disagree with the jars it runs against — a mismatched pair runs silently
  rather than failing. Truffle unpacks its own native resources into
  `/data/graal-cache` the first time an engine is built (161 MB, once per
  GraalVM version); `backup_exclude` keeps that out of HA backups.

If the isolate cannot start, charts show an error label and the log says why
with a `no GraalJS isolate` warning. The image carries no in-heap JavaScript to
fall back to; only a development classpath does. Why this engine at all is
[ADR 0032](../docs/adr/0032-a-chart-is-bytes.md); how it is packaged is the
[Dockerfile](Dockerfile) and [run.sh](run.sh).

## The GraalJS cache

Nothing prunes the copy a GraalVM bump replaces, so `/data/graal-cache` grows
by 161 MB per version. Deleting it is always safe — the next start rebuilds it
(~650 ms of CPU on NVMe; however long 161 MB of writes takes elsewhere).

Two ways to skip the runtime unpack, both tried and not worth re-walking:

- **`Engine.copyResources`** is documented for exactly this (read-only file
  systems, strict startup), with `-Dpolyglot.engine.resourcePath` as its
  runtime half — a system property, not an engine option. It omits
  `libtruffleattach`, so an engine pointed at its output dies with "Polyglot
  isolates require libtruffleattach when running on HotSpot with the fallback
  Truffle runtime". Lifting that one file out of `truffle-api` by hand works;
  it looks like an upstream gap.
- **`engine.IsolateLibrary`** names the `.so` directly with no jar, but
  Truffle marks it "for testing purposes only" and it needs
  `allowExperimentalOptions`. It is the fallback if the jar route ever breaks.

## Reproducing the engine measurements

ADR 0032's numbers came from a scratch `javac` harness over coursier
classpaths, not from anything committed:

```sh
cs fetch -p org.graalvm.js:js-isolate-linux-amd64:25.3.4.1 \
          org.graalvm.polyglot:polyglot:25.3.4.1 > cp-iso.txt    # the isolate
cs fetch -p org.graalvm.polyglot:js:25.3.4.1     > cp-interp.txt # interpreted
curl -LO https://cdn.jsdelivr.net/npm/echarts@5.6.0/dist/echarts.min.js
```

Render an ECharts SVG N times through `Context.newBuilder("js")` (with the
`setTimeout` shim and `animation: false`, or SSR throws) and print eval,
first-render and warm-median times plus `Rss:` and `Anonymous:` from
`/proc/self/smaps_rollup`. For the isolate, build the engine with
`spawnIsolate(true)` and contexts with `HostAccess.SCOPED`.

- **Run each configuration at least three times**, with the add-on's own
  flags (`-Xms64M -Xmx512M -XX:+UseSerialGC`). Single runs moved by up to 2×
  and produced three wrong conclusions; G1 reports more anonymous memory than
  the add-on uses.
- **Check the library is the one you think.** Point `engine.IsolateLibrary` at
  a path that does not exist: construction must throw from
  `PolyglotIsolateHostSupport.buildIsolatedEngine`, or the `.so` is coming
  from somewhere else.
- The library's glibc floor reads straight off the ELF (highest `GLIBC_2.*`:
  2.15 amd64, 2.17 aarch64) — `jar xf` the isolate jar's
  `META-INF/resources/engine/js-isolate-linux-<arch>/libvm/libpolyglotisolate.so`.
