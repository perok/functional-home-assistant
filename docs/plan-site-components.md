# Plan — a workspace `components.pkl`: the components, configured once

The device work (#541, `docs/plan-device-progress.md` on #627) needs defaults
that every card sees and that a user extends in one place: which words mean
"time left" in their integrations. Neither the dump nor the library can import
the workspace, so the defaults cannot live there.

Instead, the workspace gets its own `components.pkl`, which amends the
library's and sets `defaults`. Every dashboard file imports it as `c`:

```pkl
// components.pkl, seeded once into the workspace, then yours
amends "@fh-dashboard/components.pkl"

defaults {
  light { opensOn = List("colourTemp", "colour") }
}

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

card = new c.Column { children { c.entitySlider(dump.lights.lamp) } }
```

There are two ways in:
- `import "components.pkl" as c` gets your defaults;
- `import "@fh-dashboard/components.pkl" as c` gets the library's.

Every existing workspace and test fixture uses the second, and keeps working
unchanged. `components.pkl` is only the name the scaffold seeds. A file of any
name that amends the library works the same way.

This PR adds the seeded file and `defaults` with its first group, `light`. It
also moves the starter site, demos and docs onto it. E3 adds the `progress`
group. More candidates are tracked in #630.

## The pattern: `defaults` gives the default, the card overrides it

ADR 0015 will state these rules for every later default:

- **One class, grouped by card family.** `components/defaults.pkl` declares
  `Defaults`, which holds one group per family (`light: Light`). A default is
  named after the builder it feeds, so `defaults.light.opensOn` is the default
  for `.opensOn(…)`. The module imports nothing from the card modules, so they
  can import it without a cycle. It is also the one file to read for "what can
  I set".
- **A card that reads a default carries the whole object** (`hidden defaults:
  Defaults = new {}`). It works out its own property from it, next to the
  builder that overrides that property. Carrying the whole object lets the card
  pass it to whatever it builds itself, such as a popup.
- **The facade hands the object over.** The facade's `hidden defaults` is what
  the workspace amends. Its function for such a card sets the card's
  `defaults`. The form a query uses calls that function. A section that builds
  such a card passes the object on: `c.recipes` becomes an instance of a class
  (`new recipesMod.Recipes { defaults = module.defaults }`), with the same
  completion.
- **One card overrides it** with its builder or an amend. Pkl has no default
  parameters, so "a parameter to the call" is the builder.
- **A default takes names, not values about one entity,** because it applies to
  every entity. The builder keeps its per-entity form.
- **What doesn't see your defaults:** the library import, a family module
  called directly, and `new c.EntitySlider { … }`. These get the library's
  defaults. `new …EntitySlider { defaults = c.defaults }` passes them
  explicitly.

The first default:

```pkl
// lib/components/defaults.pkl
class Defaults {
  /// A light's slider.
  light: Light = new {}
}
class Light {
  /// The controls a light's slider opens on, tried in order (`.opensOn`).
  opensOn: List<hass.LightControl> = List()
}

// lib/components/slider.pkl, in EntitySlider
hidden defaults: defaultsMod.Defaults = new {}
hidden opensOnAxes: List<hass.Axis> =
  if (on is hass.LightEntity)
    let (l = on) defaults.light.opensOn.map((n) -> l.axes.findOrNull((x) -> x.name == n)).filterNonNull() as List<hass.Axis>
  else List()

// lib/components.pkl
hidden defaults: defaultsMod.Defaults = new {}
function entitySlider(a: sliderMod.SlideAxis): sliderMod.EntitySlider =
  let (d = defaults) (sliderMod.entitySlider(a)) { defaults = d }
hidden entitySlider: (hass.Entity) -> sliderMod.EntitySlider = (e) -> entitySlider(e)

// a dashboard
c.entitySlider(l)                          // opens on the light's mode
c.entitySlider(l).opensOn(List())          // this one opens on brightness
c.entitySlider(l).opensOn(List(l.colour))  // this one tries colour only
```

## Shapes not taken

- **`site.c`**, the components held on `site.pkl`. Nested code can't see an
  inherited property, and pkl-lsp doesn't flag the mistake. The measurements
  are in `docs/experiments.md`, and the rule is an ADR 0006 gotcha. An import is
  in scope at every depth.
- **One facade property per default, applied by the facade's function.** It
  only reaches cards built through `c.`. The library's own sections call the
  family modules directly. So `c.recipes.floorView` would ignore it, and so
  would the more-info popup's chart and the default tap an entity card sets up
  for itself. It also puts the default's logic in the facade, away from the
  builder.
- **Replacing a facade function from the workspace file.** Amends can't
  ("Method needs a `local` modifier… extend rather than amend"), and `extends`
  breaks pkl-lsp completion through the module (issue report 2).
- **A hook per card** (`Mixin<EntitySlider>`). General, but awkward for the
  common case. It can be added as a group later if someone needs it.
- **Passing defaults down the card tree from the site.** That would reach
  `new …` too, but every container kind would have to pass them along.

## Already shown, on pkl-core 0.32.1 and pkl-lsp 0.8.0

Tested on a copy of main's library, with workspace files importing it by path:

- `defaults.light.opensOn` set in the workspace file applies to
  `c.entitySlider(l)` at the top level, nested in a column, through the form a
  query uses, and through `c.recipes.floorView`;
- `.opensOn(…)` or an amend overrides it on one card;
- the library import, the library's recipe and `new …EntitySlider` keep
  brightness, and `new …EntitySlider { defaults = c.defaults }` takes them;
- `c.title`, `(c.grid) { … }` and `new c.Column` work at any depth;
- there is no import cycle: `site.pkl` and `kitchen.pkl` both import
  `components.pkl`;
- pkl-lsp:
  - completes the whole facade after `c.` at any depth, and `c.recipes.` and
    `c.defaults.` too;
  - completes the groups inside `defaults { }`, and a group's defaults
    inside it;
  - flags a misspelt default ("Unresolved property") and a value outside the
    union ("Type mismatch"), nested too;
  - shows the docs on hover.

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

- `lib/components/defaults.pkl`: `Defaults` and `Light`.
- `lib/components/slider.pkl`: `EntitySlider` carries `defaults`, and
  `opensOnAxes` is worked out from it.
- `lib/components.pkl`:
  - adds `hidden defaults`;
  - `entitySlider` and its form for queries hand `defaults` over;
  - `recipes` becomes an instance of a class that is given `defaults`.
- `lib/recipes.pkl`: a `Recipes` class with `defaults`, passed to the
  sliders `floorView` builds. `c.light.controls` and the more-info popup are
  left alone: their sliders have no main-control switch, so `opensOn` has no
  effect there.
- `AddonBootstrap` seeds `components.pkl` when the workspace has none, and never
  overwrites it. An existing workspace gets the file on its next boot, and
  nothing imports it until the user does.
- `site_default.pkl` (the starter) imports `"components.pkl" as c`.
- `pkl-demo.pkl`, `pkl-tabs.pkl`, `pkl-if.pkl` do the same. They are meant to
  sit beside a workspace's `site.pkl`.
- Test fixtures that import `@fh-dashboard/components.pkl` stay as they are:
  they configure nothing.

## Docs

- ADR 0015: a dashboard author reaches the facade through the workspace file.
  The library import still works, with the library's defaults. The `defaults`
  pattern above, as the rule for every later default.
- ADR 0021: the workspace's `components.pkl` holds the defaults every card
  sees. Explain why it is a separate file, not a property of `site.pkl`.
- ADR 0033, where `opensOn` is described: `defaults.light.opensOn`.
- ADR 0010: the seeded file is the user's, like `site.pkl` and `PklProject`.
- ADR 0006: the scope gotcha (already on this branch).
- `docs/writing-dashboards.md`: the two imports, when to use each, and
  `defaults.light.opensOn` beside `.opensOn`.
- `docs/writing-components.md`: a card that reads a default carries
  `defaults`. Also update the scaffold's comments in `AddonBootstrap` where
  they show the import.

## Tests

- `components.test.pkl`: `defaults.light.opensOn` through a module that amends
  the facade. Check the default, nested use, the query form, `c.recipes`, and
  the builder and amend overrides. Check that the library import and `new`
  ignore it.
- Bootstrap seeds `components.pkl` and leaves an existing one alone.
- A site whose dashboard file imports `components.pkl` builds, and both files
  are among the imports.
- A server suite: editing `components.pkl` repaints a dashboard defined in
  another file.
- `scripts/fh.test.scala`: pushing such a file.
