# Writing dashboards

This guide is for people who write dashboards in Pkl. To add a new kind of
card, see [Writing your own components](writing-components.md).

Your dashboards live in one folder. With the add-on that is
`<ha config>/fh-dashboards/`:

```
fh-dashboards/
  site.pkl     # every dashboard you serve: edit me
  PklProject   # binds the @fh-dashboard and @fh-home names your files import
  .fh/         # written by the server on every start: don't edit
```

The card library (`@fh-dashboard`) and your entities (`@fh-home`) are
packages, not files here. The library comes with the add-on, so an upgrade
brings the new one without touching your files.

## Dashboards

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
- `default = "<slug>"` in `site.pkl` picks what `/` serves; with none, the
  dashboard keyed `dashboard`, else the first one.
- **Every edit hot-reloads**, including adding or removing a dashboard:
  connected browsers repaint, no restart needed.
- A dashboard that fails to build serves an error page naming the problem and
  recovers the moment you fix it; the others keep serving. If `site.pkl`
  itself will not evaluate, every dashboard shows that error — the file no
  longer says what they are — and one fix restores them all.

## Your entities

`import "@fh-home/dump.pkl" as dump` gives typed references to everything in
your Home Assistant: `dump.entities.<name>`, `dump.lights.<name>`,
`dump.areas.<name>`, `dump.floors.<name>`. It is generated from your live
entity registry, so there is no file to edit.

It also refreshes while running. When the registry changes (a device, entity,
area or floor is added, renamed or removed, or an integration is set up), the
dump is rebuilt, every dashboard that builds today is checked against it, and
only then is it swapped in. If the new dump *would* break a dashboard, the swap
is skipped and a warning is logged; fix the dashboard and refresh again from
the `/edit` editor.

## What a button can act on

A tap names its own target. `c.tap.call(service, target)` runs any HA service,
and `c.tap.lights.on/off/toggle` are the light shortcuts. The target can be:

- **an entity**: `c.tap.lights.off(dump.lights.taklys)`
- **an area or a floor**: `c.tap.lights.off(dump.areas.stue)`. HA works out
  what is in it when you press, so a light added to the room later is
  included.
- **a set** from `q.from(…).where(…)`. The press acts on the members the set
  holds at that moment, in one call. A button that turns off only the lights
  that are on:

  ```pkl
  local lit = q.from(hass.lights(dump.areas.stue.all)).where(q.eq(q.stateProp, "on"))
  c.button("Stue av", c.tap.lights.off(lit))
  ```

  If the set is empty when you press, nothing is called and the press still
  counts as done. Every possible member must be in the service's domain, so a
  `light/*` service cannot take a switch; use a `homeassistant/*` service to
  mix domains. A set that could never hold anything fails the build.

The server only runs the calls a dashboard declares, and only for someone who
can view that dashboard. A set can only ever reach the entities you listed
when you built it.
