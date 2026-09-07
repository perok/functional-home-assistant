# Plan — logs and metrics through OpenTelemetry

Work in flight, continuing [issue #75](https://github.com/perok/functional-home-assistant/issues/75).
Tracing landed in #320/#321; this branch adds the other two signals. Delete this file when the
last box below is either done or refiled as its own issue.

## The decision: otel4s' `LoggerProvider`, not the logback appender

`opentelemetry-logback-appender` is the obvious way to ship log records and does not work here: it
reads OpenTelemetry Java's `Context.current()`, which is thread-local, and otel4s keeps the current
span in an `IOLocal` that never populates it. Records would arrive with an EMPTY trace context, and
the ids would survive only as MDC-derived string attributes — correlation by regex rather than by
the field a backend already knows. Bridging means a `makeCurrent()`/`close()` pair around every
call, which is the thread-local hazard again: a cats-effect fiber moves between threads freely.

`Otel4s[F].loggerProvider` has no such problem — `currentContext` is read per call, in `F`, from the
same `IOLocal` the tracer writes. It is in otel4s **1.1.0**, implemented in `otel4s-oteljava-logs`,
which `otel4s-oteljava` already depends on: no new dependency and no version bump.

The same choice answers the http4s question, which is why it is one piece of work and not two: what
this builds is a `LoggerFactory[IO]`, and a factory is what http4s takes.

## Landed

**`fh.view.telemetry`** is where these live — `Telemetry` (the providers and the endpoint switch),
`Logging`, `Meters`, and `Diagnostics`, which answers the same question locally when there is no
collector. They are what every other package takes a parameter of, so they are not part of
`runtime`'s own subject; `Meters.observeSessions` therefore takes the `IO[Long]` it reads rather
than the `Sessions` registry, which is what kept the dependency one-way.

**`Logging`** hands out loggers that fan out to both legs — slf4j (stdout, the add-on's Log tab,
unchanged, `trace_id`/`span_id` still in the line for reading by eye) and an otel4s
`LogRecordBuilder`. `LoggerProvider.noop` when no endpoint is configured, so the common install
still pays nothing. `Server`, `HaFeed`, `AssetCache`, `LspBridge`, `DashboardBuild` and
`SessionStore` take the factory; ember takes it through `withLogger`. `TracedLogger` is gone.

**`Meters`** — `fh.page.nodes`, `fh.ha.entities` and `fh.sessions.live`. Two of them are also span
attributes, which is not duplication: a span is sampled, so the attribute says what THIS page open
did and the instrument says what page opens do. Nothing here measures a duration — a collector
that derives latency from spans gives that for free, and the values these carry are the ones it
cannot: an attribute becomes a metric LABEL, so `fh.nodes=137` derives a series per node count
rather than a distribution of node counts.

`fh.sessions.live` is OBSERVED off the registry map rather than incremented in `register` and
decremented in `deregisterIf`. The map is already the truth, and a second copy of it disagrees the
first time a `conn` is re-registered — which a re-minted session does — as a drift nothing else
would ever report.

**Two small ones**: the boot `println`s are log lines, and `run.sh` no longer sets
`-Dotel.java.global-autoconfigure.enabled`. That flag gates `GlobalOpenTelemetry.get()`;
`OtelJava.autoConfigured` builds its own SDK, which autoconfigures with or without it — verified by
autoconfiguration running, and failing on an exporter setting, with the flag absent.

## Still open

- **`ha-api`'s transport logs to the console only.** `HAWSApiLowLevel` is an object whose five log
  calls sit in five different private helpers, so taking the factory means threading it through the
  whole transport — a refactor that does not belong in a telemetry change. Its error and warn lines
  are worth having, which is why this is written down rather than dropped.
- **`ServerApp`'s own helpers likewise.** `run` logs through the fan-out factory; `reloadSite`,
  `refreshOnce`, `revalidateOnce`, the pkl-lsp resolution and `bootstrap` still use the object's
  console logger. `bootstrap` genuinely has to — it runs before the SDK exists — but the others do
  not.
- **SSE frames and patch bytes.** What a live page costs after it has loaded is the obvious next
  instrument, and needs a look at the write loop first.
- **`Renderer.identityCache` hit rate.** Deliberately last: the cache is a `ConcurrentHashMap` in
  the pure render core, and counting through it means putting `IO` where there is none.

## Not doing

- **Making a bad `OTEL_*` variable non-fatal.** Autoconfigure throws at `ServerApp.scala:67` and
  kills boot. That is wanted: a misconfigured collector should be loud, not silently absent.
- **On-demand attach for a live instance.** Traces are push-only and no collector can pull them, so
  "attach and start collecting" means either a runtime sampler switch or `parentbased_always_off`
  plus a `traceparent` on the request you care about. Worth its own issue; not this PR.

## Verifying

`grafana/otel-lgtm` on the dev machine, receiving on 4318, with Tempo (3200) and Prometheus (9090)
published so the APIs are queryable and not only the Grafana UI:

```bash
docker run -d --name lgtm \
  -p 3000:3000 -p 4317:4317 -p 4318:4318 \
  -p 3200:3200 -p 9090:9090 -p 3100:3100 \
  grafana/otel-lgtm:latest

OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 OTEL_SERVICE_NAME=fh-dashboard sbt dashboardServe
```

The check that matters is not "data arrived" but **one page open, read three ways**: the request
span with its `dashboard.page.walk` child, the log lines from that open found by its trace id, and
`fh.page.nodes` agreeing with the `fh.nodes` attribute on the span. Nothing in the suite can stand
in for it — every test runs with the no-op providers, which is the property the suite pins instead.
