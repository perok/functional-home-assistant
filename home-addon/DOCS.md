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
  dashboard.pkl      # the starter entry — edit me
  lib/               # the shared Pkl card/theme library
```

Edit these files from the host (Samba, SSH, or the File editor add-on) — they
sit under the main `homeassistant/` config share as `fh-dashboards/`, so the
default File editor / Samba add-ons can reach them without extra config.

### Editing dashboards

- Every top-level `*.pkl` file in `fh-dashboards/` is a dashboard; the slug is
  the filename (`dashboard.pkl` → `/d/dashboard`).
- **Edits to existing files hot-reload**: connected browsers repaint over the
  live SSE stream, no restart needed. A file that fails to evaluate is logged
  and the previous version stays up.
- **A brand-new `*.pkl` entry file needs an add-on restart** — entries are
  discovered at startup.
- A dashboard that is broken at startup is skipped (and logged); the add-on
  only fails to start when *no* dashboard builds.
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

The seed is copied only when the dashboards directory is empty. To get a
fresh copy of the starter or an updated `lib/` after an add-on upgrade, move
your entries elsewhere, empty the directory, and restart.

What you get: your entries (`*.pkl`, starting with `dashboard.pkl`), `lib/` (the
authoring library that ships with the add-on — don't edit it, it is replaced on
upgrade), `home/` (your regenerated `dump.pkl`), and `PklProject`, which binds
the `@fh-dashboard` and `@fh-home` names your entries import.

## Options

| Option | Description |
|---|---|
| `default_dashboard` | Slug served at `/` (empty = `dashboard`, else the first slug). |
| `watch_registry` | Rebuild the entity dump automatically on HA registry changes (default `true`). The swap is validated first and the previous dump is kept as a dated backup. |

## Direct port (optional)

The dashboard is also available on host port 8080 if you map it in the
add-on's network configuration. **The direct port is unauthenticated** and the
server drives Home Assistant with its own token — leave it disabled unless
your LAN is trusted.
