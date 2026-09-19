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
  unwanted. The version is written once, in `build.sbt`; the image does not
  repeat it but reads it back out of the assembled jar
  (`fh.view.runtime.JsIsolateFetch`), because a library and a jar that
  disagree run silently rather than failing.

Is the library loadable in the built image?

```sh
docker run --rm --entrypoint java ghcr.io/perok/fh-dashboard:latest \
  --enable-native-access=ALL-UNNAMED \
  -cp /opt/fh-dashboard.jar fh.view.runtime.JsIsolateCheck
```

It runs a line of JavaScript through the isolate and prints RSS before and
after, which is the only way to see memory a foreign library allocates
outside the JVM heap. CI runs it on both architectures.
