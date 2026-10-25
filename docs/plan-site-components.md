# Plan — a workspace `components.pkl`: the components, configured once

The device work (#541, `docs/plan-device-progress.md` on #627) needs settings
that every card sees and that a user extends in one place: which words mean
"time left" in their integrations. Neither the dump nor the library can import
the workspace, so the settings cannot live there.

Instead, the workspace gets its own `components.pkl`, which amends the
library's, and every dashboard file imports that one as `c`:

```pkl
// components.pkl, seeded once into the workspace, then yours
amends "@fh-dashboard/components.pkl"

// From E3: devices { progress { countdown { "finish in" } } }

// site.pkl
amends "@fh-dashboard/site.pkl"
import "components.pkl" as c

dashboards {
  ["home"] { card = (c.grid) { children { c.title("Home") } } }
  ["kitchen"] = import("kitchen.pkl")
}

// kitchen.pkl
amends "@fh-dashboard/entry.pkl"
import "components.pkl" as c

card = new c.Column { children { c.title("Kitchen") } }
```

There are two ways in:
- `import "components.pkl" as c` gets your settings;
- `import "@fh-dashboard/components.pkl" as c` gets the library's defaults.

Every existing workspace and test fixture uses the second, and keeps working
unchanged. `components.pkl` is only the name the scaffold seeds. A file of any
name that amends the library works the same way.

This PR adds the seeded file, its first setting (which control a light's
slider opens on), and moves the starter site, demos and docs onto it. E3 adds
the second, `devices`.

## The pattern: a setting gives the default, the card overrides it

Every setting follows the same rules, and ADR 0015 will state them:

- **One per setting:** a `hidden` property on the facade `components.pkl`, with
  the library's default. It is named after the builder it feeds.
- **The facade applies it.** The facade's function sets the card's own property
  from the setting. The card class and its family module never see the setting:
  a class body can only read `const` values, and the family modules must stay
  importable alone. The form a query uses (`hidden entitySlider = (e) ->
  entitySlider(e)`) calls the facade's method, so a query gets the setting too.
- **One card overrides it** with its builder or an amend, which comes after the
  facade's function. Pkl has no default parameters, so "a parameter to the call"
  is the builder.
- **It takes names, not values about one entity,** because it applies to every
  entity. The builder keeps its per-entity form.
- **Outside the setting:** `@fh-dashboard/components.pkl`, a family module, or
  `new c.EntitySlider { … }`. These get the library's default.

The first setting:

```pkl
// lib/components.pkl
/// The controls a light's slider opens on, tried in order (`.opensOn`).
hidden lightOpensOn: List<hass.LightControl> = List()
function entitySlider(a: sliderMod.SlideAxis): sliderMod.EntitySlider =
  let (ns = lightOpensOn)
    (sliderMod.entitySlider(a)) {
      opensOnAxes =
        if (a is hass.LightEntity)
          ns.map((n) -> a.axes.findOrNull((x) -> x.name == n)).filterNonNull() as List<hass.Axis>
        else List()
    }

// components.pkl, in the workspace
lightOpensOn = List("colourTemp", "colour")

// a dashboard
c.entitySlider(l)                          // opens on the light's mode
c.entitySlider(l).opensOn(List())          // this one opens on brightness
c.entitySlider(l).opensOn(List(l.colour))  // this one tries colour only
```

## Why not `site.c`

The first shape held the components on `site.pkl` (`hidden c`), and dashboards
took them back by importing `site.pkl`. Pkl's scoping broke it: nested code
can't see an inherited property, and pkl-lsp doesn't flag the mistake. The
measurements are in `docs/experiments.md`, and the rule is an ADR 0006
gotcha. An import is in scope at every depth, so this shape avoids the problem.

## Already shown, on pkl-core 0.32.1 and pkl-lsp 0.8.0

Tested on a copy of the library, with workspace files importing it by path:

- a setting in the workspace file reaches the library's functions in both
  `site.pkl` and a dashboard file (late binding);
- `lightOpensOn` above, on a copy of main's library, applies:
  - to `c.entitySlider(l)` at the top level, nested in a column, and through
    the form a query uses;
  - except where `.opensOn(…)` or an amend sets it on one card;
  - and not to the library import or `new …EntitySlider`, which keep
    brightness;
- `c.title`, `(c.grid) { … }` and `new c.Column` work at any depth;
- there is no import cycle: `site.pkl` and `kitchen.pkl` both import
  `components.pkl`;
- pkl-lsp:
  - completes the whole facade after `c.` at any depth;
  - completes the settings inside `components.pkl`;
  - flags an unknown member and a wrong type.

  It doesn't complete class names after `new c.`, but neither does today's
  direct import.

## To prove here, in the package form a real workspace uses

- The server rebuilds on a `components.pkl` edit and repaints every dashboard.
  It is in each build's imports, so the watcher and `SiteEvalCache` should
  pick it up already.
- pkl-lsp over a bootstrapped workspace, with `@fh-dashboard` as a package:
  the same checks as above.
- `fh push` of a dashboard file that imports `components.pkl`
  (`scripts/fh.test.scala`).
- The `/edit` editor opens and saves `components.pkl`.

## Changes

- `AddonBootstrap` seeds `components.pkl` when the workspace has none, and never
  overwrites it. An existing workspace gets the file on its next boot, and
  nothing imports it until the user does.
- `site_default.pkl` (the starter) imports `"components.pkl" as c`.
- `pkl-demo.pkl`, `pkl-tabs.pkl`, `pkl-if.pkl` do the same. They are meant to
  sit beside a workspace's `site.pkl`.
- `lib/components.pkl`: `lightOpensOn`, applied by `entitySlider` and its
  form for queries.
- Test fixtures that import `@fh-dashboard/components.pkl` stay as they are:
  they configure nothing.

## Docs

- ADR 0021: the workspace's `components.pkl` holds the settings every card
  sees. Explain why it is a separate file, not a property of `site.pkl`.
- ADR 0015: a dashboard author reaches the facade through the workspace file.
  The library import still works, with the defaults. Add the setting pattern
  above as the rule for every later setting.
- ADR 0033, where `opensOn` is described: the setting.
- ADR 0010: the seeded file is the user's, like `site.pkl` and `PklProject`.
- ADR 0006: the scope gotcha.
- `docs/writing-dashboards.md`: the two imports, when to use each, and
  `lightOpensOn` beside `.opensOn`.
- `docs/writing-components.md`, and the scaffold's comments in
  `AddonBootstrap`, where they show the import.

## Tests

- Bootstrap seeds `components.pkl` and leaves an existing one alone.
- A site whose dashboard file imports `components.pkl` builds, and both files
  are among the imports.
- A server suite: editing `components.pkl` repaints a dashboard defined in
  another file.
- `scripts/fh.test.scala`: pushing such a file.
- `components.test.pkl`: `lightOpensOn` through a module that amends the
  facade. Check the default, nested use, the query form, and the builder and
  amend overrides. Check that the library import and `new` ignore it.
