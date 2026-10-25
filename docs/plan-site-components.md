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

This PR adds the seeded file and moves the starter site, demos and docs onto
it. It adds no setting: the first, `devices`, comes with E3.

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
- No library change.
- Test fixtures that import `@fh-dashboard/components.pkl` stay as they are:
  they configure nothing.

## Docs

- ADR 0021: the workspace's `components.pkl` holds the settings every card
  sees. Explain why it is a separate file, not a property of `site.pkl`.
- ADR 0015: a dashboard author reaches the facade through the workspace file.
  The library import still works, with the defaults.
- ADR 0010: the seeded file is the user's, like `site.pkl` and `PklProject`.
- ADR 0006: the scope gotcha.
- `docs/writing-dashboards.md`: the two imports, and when to use each.
- `docs/writing-components.md`, and the scaffold's comments in
  `AddonBootstrap`, where they show the import.

## Tests

- Bootstrap seeds `components.pkl` and leaves an existing one alone.
- A site whose dashboard file imports `components.pkl` builds, and both files
  are among the imports.
- A server suite: editing `components.pkl` repaints a dashboard defined in
  another file.
- `scripts/fh.test.scala`: pushing such a file.
- The late-binding test comes with E3's first setting.
