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
  dashboard.pkl      # THE entrypoint: every dashboard you serve — edit me
  lib/               # the shared Pkl card/theme library
```

Edit these files from the host (Samba, SSH, or the File editor add-on) — they
sit under the main `homeassistant/` config share as `fh-dashboards/`, so the
default File editor / Samba add-ons can reach them without extra config.

### Editing dashboards

- **`dashboard.pkl` is the one entrypoint.** Every dashboard is a key in its
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
  recovers the moment you fix it; the others keep serving. If `dashboard.pkl`
  itself will not evaluate, every dashboard shows that error — the file no
  longer says what they are — and one fix restores them all.
- `default = "<slug>"` in `dashboard.pkl` picks what `/` serves; with none, the
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

### Upgrading from per-file dashboards

Older versions made every top-level `*.pkl` a dashboard, with the filename as
its slug. Now there is one entrypoint and the slugs are its keys, so an
existing `dashboard.pkl` written the old way is no longer a valid entrypoint.
Nothing is migrated automatically (the file is yours) — the add-on serves a
diagnostic saying exactly this, and the fix is one wrapper:

```pkl
amends "@fh-dashboard/site.pkl"

default = "home"
dashboards {
  ["home"] = import("home.pkl")        // your old dashboard.pkl, renamed
  ["kitchen"] = import("kitchen.pkl")  // one line per file you had
}
```

Each of those files keeps its `amends "@fh-dashboard/entry.pkl"` header and its
`card` — only where they are NAMED has changed. If you had set the
`default_dashboard` option, put that slug in `default` here; the option is gone.

### Re-seeding

The starter is written only when there is no `dashboard.pkl` at all. To get a
fresh copy of it or an updated `lib/` after an add-on upgrade, move your files
elsewhere, empty the directory, and restart.

What you get: `dashboard.pkl` (the starter entrypoint), `lib/` (the authoring
library that ships with the add-on — don't edit it, it is replaced on upgrade),
your regenerated entity dump, and `PklProject`, which binds the
`@fh-dashboard` and `@fh-home` names your dashboards import.

## Options

| Option | Description |
|---|---|
| `watch_registry` | Rebuild the entity dump automatically on HA registry changes (default `true`). The swap is validated first and the previous dump is kept as a dated backup. |

## Direct port (optional)

The dashboard is also available on host port 8080 if you map it in the
add-on's network configuration. **The direct port is unauthenticated** and the
server drives Home Assistant with its own token — leave it disabled unless
your LAN is trusted.
