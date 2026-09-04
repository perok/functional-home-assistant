# FH Dashboard

Pkl-authored, live-updating dashboards for Home Assistant, rendered
server-side and patched over SSE ([Datastar](https://data-star.dev)).

## How to use

Install the add-on and start it. Open it from the **FH Dashboard** entry in
the sidebar (ingress — authenticated by Home Assistant).

On first start the add-on seeds a starter dashboard into a folder in your
Home Assistant config directory:

```
<ha config>/fh-dashboards/
  site.pkl           # THE entrypoint: every dashboard you serve — edit me
  lib/               # the shared Pkl card/theme library
```

Edit these files from the host (Samba, SSH, or the File editor add-on) — they
sit under the main `homeassistant/` config share as `fh-dashboards/`, so the
default File editor / Samba add-ons can reach them without extra config.

### Editing dashboards

- **`site.pkl` is the one entrypoint.** Every dashboard is a key in its
  `dashboards` map, and the key is the route: `["kitchen"]` serves at
  `/d/kitchen`. Any other `*.pkl` beside it is an ordinary module — it becomes
  a dashboard only when a key points at it:

  ```pkl
  dashboards {
    ["home"] { title = "Home"; card = ... }        // inline
    ["kitchen"] = import("kitchen.pkl")            // its own file
  }
  ```

  Being data, they can also be generated — a `for` over your floors gives you
  a dashboard per floor.
- **Every edit hot-reloads**, including ADDING or REMOVING a dashboard:
  connected browsers repaint over the live SSE stream, no restart needed.
- A dashboard that fails to build serves an error page naming the problem and
  recovers the moment you fix it; the others keep serving. If `site.pkl`
  itself will not evaluate, every dashboard shows that error — the file no
  longer says what they are — and one fix restores them all.
- `default = "<slug>"` in `site.pkl` picks what `/` serves; with none, the
  dashboard keyed `dashboard`, else the first one.
- `home/dump.pkl` is regenerated from your live entity registry on every
  startup — don't edit it; import it (`import "@fh-home/dump.pkl" as dump`) for
  typed references to your entities (`dump.entities.<name>`).
- **The dump also refreshes while running**: when the HA registry changes (a
  device/entity/area/floor is added, renamed or removed, or an integration is
  set up), the add-on rebuilds the dump, checks that every dashboard that
  builds today still builds against it, and only then swaps it in — the
  replaced dump is kept beside it as `dump.pkl.backup.<date>`. If the new dump
  *would* break a dashboard, the swap is skipped and a warning is logged; fix
  the dashboard and refresh again. Turn the automatic part off with the
  `watch_registry` option; an on-demand refresh is always available from the
  `/edit` editor (or `POST /system/dump/refresh`).

### Re-seeding

The starter is written only when there is no `site.pkl` at all. To get a
fresh copy of it or an updated `lib/` after an add-on upgrade, move your files
elsewhere, empty the directory, and restart.

What you get: `site.pkl` (the starter entrypoint), `lib/` (the authoring
library that ships with the add-on — don't edit it, it is replaced on upgrade),
your regenerated entity dump, and `PklProject`, which binds the
`@fh-dashboard` and `@fh-home` names your dashboards import.

## Options

| Option | Description |
|---|---|
| `watch_registry` | Rebuild the entity dump automatically on HA registry changes (default `true`). The swap is validated first and the previous dump is kept as a dated backup. |
| `max_heap` | JVM max heap as a `-Xmx` value — `"512M"` (the default), `"1G"`. A ceiling, not a reservation. Raise it if a large house or a big workspace runs out. |
| `min_heap` | JVM starting heap as a `-Xms` value — `"64M"` by default. This is the end that decides the idle footprint; raise it with `max_heap` if the collector is visibly growing and shrinking. |
| `memory_tracking` | Add the native-memory breakdown to `GET /system/diagnostics` (default `false`). Costs a few percent, and takes effect on restart — the JVM cannot start tracking while running. |
| `otlp_endpoint` | Send traces to an OpenTelemetry collector, e.g. `"http://192.168.1.50:4318"`. Empty (the default) means tracing is off and the OpenTelemetry SDK is never started. |

## Memory

The add-on is a JVM, so what the supervisor reports is its heap plus the
runtime's own overhead — metaspace, JIT code cache, GC structures, thread
stacks. That overhead is a fixed cost of running Scala on a JVM, not a leak.

Both ends of the heap are set to numbers rather than to fractions of the
machine: it starts at 64 MB and grows only as the workload needs, up to
`max_heap` (512 MB by default). So the figure follows the dashboards you run
rather than the size of the box you run them on, and the garbage collector
hands memory back once a burst is over. If the add-on restarts with an
OutOfMemoryError in the log, `max_heap` is the thing to raise.

### Seeing where it actually goes

`GET /system/diagnostics` reports it, as JSON. It needs a Home Assistant
admin, like the rest of `/system`, and it reports sizes and counts only —
never dashboard content or who is signed in.

```jsonc
{
  "container": {
    "current": 412844032,   // what the supervisor's percentage is computed from
    "max": "max",           // add-ons get no memory limit; see below
    "anon": 331739136,      // memory the add-on actually allocated
    "file": 81104896        // page cache it is charged for but did not allocate
  },
  "jvm": {
    "heap":    { "used": 48234496, "committed": 67108864, "max": 536870912 },
    "nonHeap": { "used": 91234816, "committed": 96468992, "max": null },
    "pools":   { "Metaspace": {}, "Compressed Class Space": {}, "CodeHeap ...": {} },
    "gc":      [ { "name": "Copy", "count": 41, "ms": 388 } ],
    "threads": 34,
    "uptimeMs": 903114
  },
  "nmt": null               // the full NMT summary when memory_tracking is on
}
```

Two fields answer most questions on their own. **`container.file`** is page
cache — the add-on is charged for it in the figure the UI shows, but it is not
the JVM's doing, so subtract it before concluding anything. And
**`container.max`** is the literal `"max"`: the supervisor puts no memory limit
on an add-on, which is exactly why the heap is given a number here instead of
a percentage of "available" memory.

For the native breakdown that the pools do not cover — GC structures, thread
stacks — set `memory_tracking: true`, restart, and read the `nmt` field.

### When it is stuck rather than large

Two more admin endpoints, both plain text, for the other kind of problem:

- **`GET /system/diagnostics/threads`** — a JVM thread dump, lock information
  included. What shows a deadlock, or a pool with every thread blocked on the
  same monitor.
- **`GET /system/diagnostics/fibers`** — a cats-effect fiber dump. The thread
  dump *cannot* replace this: almost all of the server's work runs as fibers
  multiplexed over a handful of carrier threads, so a thread dump taken while a
  dashboard is stuck shows an idle worker pool and says nothing about which
  fiber is parked. This is the one that names it.

They are separate from the report above because they are large, meant to be
read rather than parsed, and because taking a thread dump pauses every thread —
not a price to pay for asking how much memory is in use.

### If you would rather use a terminal

The image ships `jcmd`, `jmap`, `jstat` and Flight Recorder, so with the SSH
add-on you can go straight at the process. PID 1 is the base image's init, so
ask `jcmd -l` for the JVM's:

```sh
C=$(docker ps --format '{{.Names}}' | grep fh_dashboard)
docker exec "$C" jcmd -l                      # -> "<pid> /opt/fh-dashboard.jar"
docker exec "$C" jcmd <pid> GC.heap_info
```

For a slow page open rather than a large one, record a profile into the
add-on's `/data` and copy it out:

```sh
docker exec "$C" jcmd <pid> JFR.start settings=profile duration=60s \
  filename=/data/fh.jfr
docker cp "$C":/data/fh.jfr .
```

## Tracing (optional)

`GET /system/diagnostics` says how much the add-on is using. It does not say
where a slow *page open* went, because the phases a dashboard request goes
through — reading the entity store, minting the session, and the walk that
renders and writes the document — take their time separately.

Set `otlp_endpoint` to a collector and each page open reports as a trace:

- `dashboard.page` — the request, tagged with the slug.
- `dashboard.page.store` — reading the live entity state.
- `dashboard.page.walk` — the render and the write, tagged with the number of
  nodes painted. This is usually the one worth looking at: the document is
  rendered *as the response body is streamed*, so its cost is invisible to
  anything timing the handler.

Log lines written while serving carry the `trace_id` and `span_id` of the span
they happened in, so a slow trace and the warning explaining it can be matched
up.

### If you have no collector

You need one container and no configuration. On any machine on the LAN:

```sh
docker run -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm
```

Then set `otlp_endpoint` to `http://<that machine>:4318` and open Grafana on
port 3000 — traces land in Tempo. The image bundles Grafana, Tempo, Loki and
Prometheus behind an OpenTelemetry collector and needs no setup of its own.

Note that 4317/4318 are the collector's **receiving** ports: the add-on pushes
to them. Nothing scrapes the add-on for traces, and no OpenTelemetry component
can — a trace is a stream of completed spans rather than a current value, so
there is no pull protocol for it. (Metrics are the exception, and Prometheus
scraping is how they would be collected if we ever export any.)

### What it costs

**With no endpoint set, nothing.** The OpenTelemetry SDK is never constructed,
so the spans are no-op calls and the exporter classes are never loaded.

**With an endpoint set but nothing listening, still nothing that grows.** The
SDK's batch processor holds a fixed-size queue of 2048 spans and drops on
overflow rather than blocking or growing — so an unreachable or switched-off
collector costs dropped spans and a warning, never memory. If you stop the
collector, you can leave the endpoint set.

Everything else about the exporter — protocol, headers, sampling, extra
resource attributes — is configured with the standard `OTEL_*` environment
variables rather than an option per setting.

## Direct port (optional)

The dashboard is also available on host port 8080 if you map it in the
add-on's network configuration. **The direct port is unauthenticated** and the
server drives Home Assistant with its own token — leave it disabled unless
your LAN is trusted.
