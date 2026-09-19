# 0021 — One entrypoint: dashboards are data

## Context

A dashboard used to be a FILE: the server listed every top-level `*.pkl` in the
workspace, took the filename as the slug, and evaluated each one. The set of
dashboards was therefore implicit in a directory listing, and three things
followed from that.

**There was nowhere to put anything that spans dashboards.** Which slug `/`
serves had to be an add-on option (`default_dashboard`), read from the
environment — a setting about the dashboards, kept somewhere the dashboards
could not see. Guest/ACL rules (issue #89) would have needed the same treatment,
and so would the next such setting. (They landed here instead — ADR 0023.)

**Membership was frozen at boot.** A new file was not discovered until a restart,
and a deleted one kept serving from memory forever. The reload path indexed a
fixed map by slug, so it could not have done otherwise (issue #141).

**A `*.pkl` beside your dashboards could not be an ordinary module.** Any helper
file you dropped in the directory became a dashboard — usually a broken one.

## The decision

**One entrypoint, `site.pkl`, whose value is a map of slug → dashboard.**
It amends the new `@fh-dashboard/site.pkl`:

```pkl
amends "@fh-dashboard/site.pkl"
import "@fh-dashboard/components.pkl" as c

default = "home"

dashboards {
  ["home"] { title = "Home"; card = (c.grid) { children { … } } }
  ["kitchen"] = import("kitchen.pkl")   // a module amending entry.pkl
}
```

`entry.pkl` — one dashboard — is unchanged. What changed is where a dashboard is
NAMED: the mapping key is the slug and the route, and a file is a dashboard only
because a key points at it. The FILE is `site.pkl`, matching the library module
it amends the way a dashboard file matches `entry.pkl`. That is not cosmetic:
calling it `dashboard.pkl` would name it after one of the things it contains,
and it is also what makes the upgrade a non-event (see Consequences). Two authoring forms, one type: amend a key into
existence (the mapping's default is an `entry`), or assign an imported module
that amends `entry.pkl`. Both were verified against the pinned pkl-core (0.32.1)
before the design was settled; the second needs the CALL form, `import("x.pkl")`,
since the declaration form does not parse in a value position.

The three costs above are paid at once. Site-wide settings have a home (`default`
and the `access` rule of ADR 0023). Membership is ordinary data that the reload
re-reads.
And an ordinary module beside the entrypoint is just a module.

Dashboards being data also makes them generable — a `for` over the dump's floors
gives a dashboard per floor — which was the other half of the ask and is not
expressible when a dashboard has to be a file somebody typed.

### Membership is live, and it had to be

With one file, the map's keys change on an ordinary edit. There is no version of
this change where membership can stay frozen: the reload path must handle a slug
that did not exist and a slug that no longer does. So `#141`'s add/remove-without-
restart is not a feature bolted on here, it is what the change forces.

The registry (`Server.LiveSite`) is a `SignallingRef` of slug → live state, and
its `.discrete` drives the per-slug recorders: `sharedPatchPublishers` reconciles
`toAdd` / `toCancel` against it, exactly as `ServerApp.watchSourcesWith` does for
the watched import set. Installing a slug IS starting its publisher; removing one
IS stopping it. That collapsed the two start paths that existed before (a startup
snapshot, plus a supervisor branch inside `push`) into one, which is why `push`
is now three lines and cannot forget to start a recorder.

**A pushed slug is never reclaimed.** Each live slug records where it came from
(`Server.Origin`: the entrypoint, with what it last evaluated to, or a push), so
the registry applies an evaluated site itself — `LiveSite.applySite` decides the
installs, the swaps and the removals from that record. A slug installed by
`POST /system/push/<slug>` (ADR 0010) is therefore outside what a reload may
remove by construction, rather than because the caller kept a second copy of the
site's membership and remembered to diff against that one. The same record is
what makes an unchanged dashboard cost nothing. `fh push`
can also send a whole evaluated site now, since `site.pkl` is the natural
file to push; that form is all-or-nothing, because a half-installed site is not a
state anybody asked for.

### Failure has two levels now

ADR 0018 stands: a failed dashboard is a live error state, not a skipped one.
What one entrypoint adds is that failure can happen at two levels, and they are
deliberately different:

- **One dashboard fails to decode or validate** (an unknown card, an uncompilable
  transform) → only that slug is `Failed`; the rest serve. This is why the site
  is decoded PER SLUG rather than as one `Map[String, Dashboard]`: a single
  circe decode would have made every dashboard hostage to the worst one.
- **The entrypoint will not EVALUATE** → nothing can be attributed to a slug, so
  every slug the site currently owns shows that error, and membership is left
  alone: the file no longer says what the dashboards are, so we keep what we
  know. One fix restores them all.
- **At boot, with nothing yet registered**, the failure is registered under a
  single slug, `dashboard`, which is also what `/` resolves to — so a workspace
  that has never built still serves its error page and the editor fix path,
  rather than 404ing.

The default slug is resolved per REQUEST against live membership (`default` if it
still names a dashboard, else the one named `dashboard`, else the first). Deleting
the default dashboard must not make `/` a 404, and only a request-time resolution
gets that right when membership moves under a running server.

## What this replaced

`DEFAULT_DASHBOARD` / the add-on's `default_dashboard` option is **gone**. Two
ways to name the default slug — one in the environment, one in the file that
declares the dashboards — is the parallel-mechanism smell the codebase avoids,
and the file is the one that can see what it is choosing between.

`/edit`'s file listing no longer carries a per-file `slug`, because that is not a
property a file has any more; it carries a `kind` (`entry` / `module` / `lib` /
`manifest`), and the editor's preview list comes from `GET /edit/dashboards`,
which asks the runtime what is actually being served.

## Alternatives rejected

**Free-standing dashboards alongside the entrypoint** (a `name.dashboard.pkl`
convention, or "any file not imported by the entrypoint"). It keeps the exact
ambiguity the change removes: two answers to "what is a dashboard", two discovery
paths to keep in step, and a helper module that is one careless filename away
from being served. Simplification was the point.

**Hoisting `theme` / `css` / `componentModules` to the site**, so one copy is
declared for every dashboard. Attractive and probably right, but those are
per-dashboard WIRE fields today: hoisting means a site-level default plus an
inheritance rule in both the Pkl layer and the decoder, and it changes every
dashboard's JSON. Deliberately deferred to its own change — repeating them costs
~29 KB against a ~1 MB card tree, so nothing forces it now.

## Consequences

- **Upgrading is a non-event, because of the NAME.** The entrypoint is
  `site.pkl`, not `dashboard.pkl`: it is the site, and naming it after one of
  the things it contains was the mistake the first draft made. So an existing
  workspace keeps its `dashboard.pkl` (and every other entry) as an ordinary
  module, gets a starter `site.pkl` seeded beside it, and boots. Nothing is
  moved or rewritten — they are the user's files — and serving one is a key:
  `dashboards { ["home"] = import("dashboard.pkl") }`. The boot log names the
  files it found so the user is not left inferring it from an instance that
  looks empty; `home-addon/DOCS.md` says the same.
- A `site.pkl` that is really a single dashboard (a rename gone wrong, a pasted
  body) still gets the decode diagnostic naming what it should be — the error
  page at `/` is where it lands.
- `sbt dashboardBuild` takes no argument any more: there is one entrypoint, and
  the artifact is the whole site.
- `DumpRefresh` validates the entrypoint rather than looping entry files; an
  entrypoint that will not evaluate against the new dump blocks the swap unless
  it does not evaluate against the current one either — the same "already broken
  does not veto" rule, one level up.
