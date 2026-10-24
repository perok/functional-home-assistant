# Plan — `site.c`: the components, configured once in `site.pkl`

The device work (#541, `docs/plan-device-progress.md` on #627) needs settings
that every card sees and that a user extends in one place: which words mean
"time left" in their integrations. Neither the dump nor the library can import
the workspace, so the settings cannot live there. Instead, the components
become a value that `site.pkl` amends, and every dashboard takes them from
`site.pkl`.

This PR adds that seat and moves the starter site, demos and docs onto it. It
adds no setting: the first, `devices`, comes with E3.

## The shape

```pkl
// @fh-dashboard/site.pkl
hidden c: components = import("components.pkl")

// site.pkl
amends "@fh-dashboard/site.pkl"

dashboards {
  ["home"] { card = c.title("Home") }      // c is inherited: no import
  ["kitchen"] = import("kitchen.pkl")
}

// kitchen.pkl
amends "@fh-dashboard/entry.pkl"
import "site.pkl"

local c = site.c
card = c.title("Kitchen")
```

From E3, `site.pkl` amends it: `c { devices { … } }`. Every function on `c`
then sees the amended value, and the facade passes it to the family modules.

## Already shown, on pkl-core 0.32.1 and pkl-lsp 0.8.0

Tested on a copy of the library, with workspace files importing it by path:

- a function of an amended `c` sees the amended property;
- `kitchen.pkl` importing the `site.pkl` that imports it evaluates, and
  `Analyzer.importGraph` handles the cycle and lists both files;
- pkl-lsp:
  - reports no false errors in either file;
  - completes `c.` and `site.c.` (the whole facade), and the properties
    inside `c { }`;
  - shows `components` on hover;
  - flags an unknown member and a wrong type;
- an existing `site.pkl` that still has
  `import "@fh-dashboard/components.pkl" as c` keeps working: its import
  shadows the inherited `c`. User files are never rewritten (ADR 0010), so
  this matters.

## To prove here, in the package form a real workspace uses

- The server builds and hot-reloads a site whose dashboard imports `site.pkl`.
  Editing `site.pkl` repaints a dashboard in its own file.
- The eval cache (`SiteEvalCache`) keys and invalidates correctly across the
  cycle.
- pkl-lsp, over a bootstrapped workspace with `@fh-dashboard` as a package:
  the same checks as above.
- `fh push` of a dashboard file that imports `site.pkl` (`scripts/fh.test.scala`).
- The `/edit` editor opens and saves both files.

## Changes

- `lib/site.pkl`: `hidden c`, documented as the components every dashboard
  uses.
- `site_default.pkl` (the starter): drops its `components.pkl` import and uses
  the inherited `c`.
- `pkl-demo.pkl`, `pkl-tabs.pkl`, `pkl-if.pkl`: `import "site.pkl"` and
  `local c = site.c`. They are meant to sit beside a workspace's `site.pkl`.
- Test fixtures that import `components.pkl` directly stay as they are. That
  form keeps working, and they configure nothing.

## Docs

- ADR 0021: `site.pkl` owns `c`. A dashboard file takes it from `site.pkl`,
  and why the import cycle is fine.
- ADR 0015: the facade is reached through `site.c`. A direct import still
  works but sees no site settings.
- `docs/writing-dashboards.md`: how a dashboard file gets `c`.
- `docs/writing-components.md` and ADR 0010, where they show the import.
- The workspace scaffold's comments in `AddonBootstrap`, where they show it.

## Tests

- `PklBuildSuite`: a site with a dashboard in its own file that uses `site.c`
  builds. The same with an old `site.pkl` that imports `components.pkl` as `c`.
- A server suite: editing `site.pkl` repaints a dashboard defined in another
  file.
- `scripts/fh.test.scala`: pushing such a file.
- The late-binding test comes with E3's first setting.
