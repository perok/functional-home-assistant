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

If the isolate cannot start, charts are drawn interpreted instead (the same
SVG, slower) and the log says so with a `no GraalJS isolate` warning.
