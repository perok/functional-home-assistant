# FH Dashboard

Pkl-authored, live-updating dashboards for Home Assistant, rendered
server-side and patched over SSE ([Datastar](https://data-star.dev)).

## How to use

Install the add-on and start it. Open it from the **FH Dashboard** entry in
the sidebar (ingress — authenticated by Home Assistant).

On first start the add-on seeds a starter dashboard into its config
directory:

```
/addon_configs/<repo>_fh_dashboard/dashboards/
  dashboard.pkl      # the starter entry — edit me
  lib/               # the shared Pkl card/theme library
```

Edit these files from the host (Samba, SSH, or the File editor add-on).

### Editing dashboards

- Every top-level `*.pkl` file in `dashboards/` is a dashboard; the slug is
  the filename (`dashboard.pkl` → `/d/dashboard`).
- **Edits to existing files hot-reload**: connected browsers repaint over the
  live SSE stream, no restart needed. A file that fails to evaluate is logged
  and the previous version stays up.
- **A brand-new `*.pkl` entry file needs an add-on restart** — entries are
  discovered at startup.
- A dashboard that is broken at startup is skipped (and logged); the add-on
  only fails to start when *no* dashboard builds.
- `lib/dump.pkl` is regenerated from your live entity registry on every
  startup — don't edit it; import it (`import "lib/dump.pkl" as dump`) for
  typed references to your entities (`dump.entities.<name>`).

### Re-seeding

The seed is copied only when the `dashboards/` directory is empty. To get a
fresh copy of the starter or an updated `lib/` after an add-on upgrade, move
your entries elsewhere, empty the directory, and restart.

## Options

| Option | Description |
|---|---|
| `default_dashboard` | Slug served at `/` (empty = `dashboard`, else the first slug). |

## Direct port (optional)

The dashboard is also available on host port 8080 if you map it in the
add-on's network configuration. **The direct port is unauthenticated** and the
server drives Home Assistant with its own token — leave it disabled unless
your LAN is trusted.
