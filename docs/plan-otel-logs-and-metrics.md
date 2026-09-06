# Plan — logs and metrics through OpenTelemetry

Work in flight, continuing [issue #75](https://github.com/perok/functional-home-assistant/issues/75).
Tracing landed in #320/#321; this file is the route from "spans exist" to "the three signals answer
a question together". Delete it when the last box below is done.

## Where #75 stopped

Three gaps, all found by pointing a collector at the running server:

- **Logs do not leave the process.** `TracedLogger` puts `trace_id`/`span_id` on the console line,
  and that is the whole correlation story — good enough for the add-on's Log tab, useless to a log
  backend. Worse, `TracedLogger` is used in exactly ONE place (`Server`); the other ten call sites
  construct `Slf4jLogger.getLogger[IO]` directly, so most lines carry no span at all.
- **There are no metrics of our own.** `OtelMetrics` gives the conventional `http.server.*` /
  `http.client.*` and nothing else. Every question about what the runtime is DOING — how many
  sessions, how much SSE traffic, how big a page is, how fast the feed arrives — is answerable only
  by reading spans, which sampling makes approximate and which cost more than a counter.
- **http4s logs through an unconfigured slf4j.** Ember takes a `Logger[F]` and nothing passes one,
  so its lines miss the context every other line will now carry.

## The decision: otel4s' `LoggerProvider`, not the logback appender

`TracedLogger`'s scaladoc argues against `opentelemetry-logback-appender`: it reads OpenTelemetry
Java's `Context.current()`, which is thread-local, and otel4s keeps the current span in an `IOLocal`
that never populates it. A cats-effect fiber moves between threads, so bridging means a
`makeCurrent()`/`close()` pair around every call. Records would ship with EMPTY trace context and
the ids would survive only as MDC-derived string attributes — correlation by regex rather than by
the field the backend already knows.

That comment named `LoggerProvider` as where this belongs, and the API has since shipped:
`Otel4s[F].loggerProvider` is in otel4s **1.1.0**, implemented in `otel4s-oteljava-logs`, which
`otel4s-oteljava` already depends on. No new dependency and no version bump. `Logger.currentContext`
is read per call in `F`, so a record carries the real ids in the LogRecord's own fields.

The same choice answers the http4s question, which is why it is one piece of work and not two: what
we build is a `LoggerFactory[IO]`, and a factory is what http4s wants.

## The shape

One `LoggerFactory[IO]` built from `Telemetry.Otel`, handing out loggers that fan out to **both**
legs:

- **slf4j** — stdout, the add-on's Log tab, the pattern in `logback.xml`. Unchanged, including the
  `trace_id`/`span_id` in the structured context, because that is how the Log tab correlates.
- **otel4s** — a `LogRecordBuilder` per line: severity, body, exception, the structured context as
  attributes, `withContext(currentContext)` for the trace link.

`LoggerProvider.noop` when no endpoint is configured, so the overwhelmingly common install still
pays nothing — the same switch `Telemetry` already makes for the other two signals.

`ha-api` takes the factory as a parameter rather than gaining an otel4s dependency; log4cats is
already on its classpath.

## Boxes

### 1. The factory

Replace `TracedLogger` with the fan-out factory and thread it from `ServerApp` to every site that
builds a logger today: `Server`, `HaFeed`, `AuthSessions`, `AssetCache`, `LspBridge`,
`DashboardBuild`, `HAWSApiLowLevel`. `FHError` and `BuildApp` are static/CLI and stay on plain
slf4j.

### 2. http4s

`EmberServerBuilder.withLogger`, and the given `LoggerFactory[IO]` for the middlewares that ask for
one. `logback.xml` keeps `org.http4s` at WARN — that ceiling is about volume on an SSE stream and
has nothing to do with where the lines go.

### 3. Instruments

Off values the code already computes, so none of these is a new measurement:

| Instrument | Kind | Where |
| --- | --- | --- |
| `fh.sessions.live` | UpDownCounter | `Sessions.register` / the reaper |
| `fh.sse.frames` | Counter | the per-session write loop |
| `fh.sse.patch.bytes` | Counter | same |
| `fh.page.nodes` | Histogram | `Server`'s walk — a span attribute today, so sampling-dependent |
| `fh.ha.entities` | Counter | `HaFeed.pump`, same batch size the span carries |
| `fh.dashboard.eval.duration` | Histogram | `prepareRenderers`, boot AND every registry refresh |
| `fh.renderer.cache` (`result` attr) | Counter | `Renderer.identityCache` |

### 4. Two small ones

- `println` → logs at `ServerApp.scala:320` and `:504`. (`DecoderWithWarnMissing` is deliberately
  left alone.)
- Drop `JAVA_OTEL` / `-Dotel.java.global-autoconfigure.enabled=true` from `run.sh`. It is dead:
  that flag gates `GlobalOpenTelemetry.get()`, and `OtelJava.autoConfigured` builds its own SDK —
  verified by autoconfiguration running, and failing on an exporter setting, without it.

## Not doing

- **Making a bad `OTEL_*` variable non-fatal.** Autoconfigure throws at `ServerApp.scala:67` and
  kills boot. That is wanted: a misconfigured collector should be loud, not silently absent.
- **On-demand attach for a live instance.** Traces are push-only and no collector can pull them, so
  "attach and start collecting" means either a sampler switch or `parentbased_always_off` plus a
  `traceparent` on the request you care about. Worth its own issue; not this PR.

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
`fh.page.nodes` agreeing with the `fh.nodes` attribute on the span.
