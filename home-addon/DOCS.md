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
| `memory_tracking` | Turn on Native Memory Tracking so the memory breakdown below is available (default `false`). Costs a few percent; takes effect on restart. |

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

To see where the memory actually goes, set `memory_tracking: true`, restart,
and ask the running process from the host. The container is named after the
repository it was installed from, so find it rather than guessing, and note
that PID 1 is the base image's init — `jcmd -l` gives you the JVM's:

```sh
C=$(docker ps --format '{{.Names}}' | grep fh_dashboard)
docker exec "$C" jcmd -l                      # -> "<pid> /opt/fh-dashboard.jar"
docker exec "$C" jcmd <pid> VM.native_memory summary
docker exec "$C" jcmd <pid> GC.heap_info
```

For a slow page open rather than a large one, record a profile into the
add-on's `/data` and copy it out:

```sh
docker exec "$C" jcmd <pid> JFR.start settings=profile duration=60s \
  filename=/data/fh.jfr
docker cp "$C":/data/fh.jfr .
```

## Direct port (optional)

The dashboard is also available on host port 8080 if you map it in the
add-on's network configuration. **The direct port is unauthenticated** and the
server drives Home Assistant with its own token — leave it disabled unless
your LAN is trusted.
