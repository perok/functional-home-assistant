# ADR 0010 — The live Pkl schema endpoint: `hass.pkl`/`dump.pkl` served over HTTP, resolved in-memory server-side

- **Status:** Accepted (Track A landed: entries migrated to the http imports)
- **Date:** 2026-07-13
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

See also the design plan `docs/plan-pkl-live-endpoint-and-deps.md` (the spike
evidence, the deferred `@fh-dashboard` project-dependency track, and the
step-by-step prototype log) and ADR [0006](0006-pkl-authoring-track.md) (Pkl as
the authoring language).

## Context

A dashboard entry pulls in two fh-owned Pkl artifacts:

- **`lib/hass.pkl`** — the hand-written HA domain schema (entity/area/floor
  classes). Static; ships with the module.
- **`lib/dump.pkl`** — the *typed* dump of THIS home's live entities/areas/floors
  (`PklDump.render(DataDump.fetch(...))`), regenerated from the live instance.
  Gitignored; per-home; changes as the home changes.

Both are tied to a **specific running home**: `dump.pkl` is that home's live
state, and `hass.pkl` is the schema version that home's server was built with.
Today an entry imports them by **relative file path** (`import "lib/dump.pkl"`),
which only works when the author sits in a checkout with a freshly-written dump
next to the entry. A remote author, or the `/edit` in-browser editor's pkl-lsp
process, has no such file — it needs the live home's *current* schema + dump.

We want **one uniform import text** that works in every consumer:

- the server evaluating its **own** entries at startup/reload,
- the `/edit` editor's pkl-lsp subprocess (for completion/diagnostics),
- a remote author pointing their editor at their home.

## Decision

**Serve the two artifacts over an HTTP route, and have the server resolve its own
copy of that route from memory instead of fetching itself.**

### 1. One import text: an HTTP URL

Entries and `lib/components.pkl` import the artifacts by a `http://…/system/pkl/…`
URL, not a relative file path. That single string is valid everywhere: it is a
real fetchable endpoint for external consumers, and the server short-circuits it
for its own eval (below).

### 2. The `/system/pkl/:name` route

`Server` serves `GET /system/pkl/{hass,dump}.pkl` as `text/plain` from a
`SystemPkl` provider (`SystemPkl.fromDisk(dashboardsDir)` reads
`lib/{hass,dump}.pkl`; `prepareDumps` has already written the live `dump.pkl`).
An unknown name is a 404. This is the endpoint pkl-lsp and remote authors fetch.

### 3. In-server interception breaks the self-eval cycle

The server cannot HTTP-fetch its own schema from itself during startup (the HTTP
server isn't up yet, and even later it would be a needless loopback). So on the
eval path a custom `org.pkl.core.module.ModuleKeyFactory`
(`SystemPkl.Factory`, prepended ahead of the built-in `http` factory) intercepts
any `…/system/pkl/…` URI **by path** (host-agnostic) and resolves it from the
same `SystemPkl` provider via `ResolvedModuleKeys.virtual` — pure in-memory, no
socket. `hasHierarchicalUris = true` lets the served `dump.pkl`'s own
`import "hass.pkl"` resolve to its `…/system/pkl/hass.pkl` sibling (intercepted
too). The evaluator's module allowlist is widened to admit `http:`
(`preconfigured()` allows `https:` only); the endpoint is plain http for the
prototype.

Consequences of interception-by-path: the embedded literal **host is
irrelevant** to the server (it matches on path), so the port/host the server
actually binds need not match the literal; and the **offline build/test paths
keep working** — no running HTTP server is needed to evaluate an entry, only a
`SystemPkl` provider.

### 4. Additive default; migration is deliberate

`PklBuild.eval` takes an **optional** `SystemPkl`. Without one it is exactly the
former `Evaluator.preconfigured()` (default; existing callers untouched). The
interception + route landed additively — the shipped entries still used relative
file imports — and flipping the imports to http is a separate step.

## The load-bearing constraint: one URI per module

**Pkl identifies a module by its resolved URI.** Two imports of byte-identical
`hass.pkl` text under two different URIs (`file:…/lib/hass.pkl` and
`http://…/system/pkl/hass.pkl`) produce **two distinct module instances with
distinct, non-interchangeable classes.** A `hass.LightEntity` from the file
instance is *not* the same type as one from the http instance; passing one where
the other is expected is a Pkl type error.

This makes the migration **all-or-nothing on the `hass.pkl` URI**:

- `dump.pkl` is served over http, so its `import "hass.pkl"` resolves to the
  **http** hass.
- Entries pass `dump.*` entities into `components.pkl` card factories, so
  `components.pkl` must import the **same** http hass — not `file:hass.pkl`.
- Any probe/entry that both declares `hass`-typed values and hands them to
  `components.pkl` must likewise import hass over **http**.

So `dump.pkl`'s (unchanged) sibling import decides the URI for *everything*:
once dump is http, hass is http across `components.pkl`, every entry, and every
test probe that mixes hass with components. There is no partial migration.

## The watch gap (and the local-dev note)

`PklBuild.importSet` computes the file-watch set from `Analyzer.importGraph` with
**file-only** module factories and a dummy HTTP client — so it silently drops
non-`file:` imports. After migration, `hass.pkl`/`dump.pkl` are http imports and
fall out of the analyzed set:

- **`dump.pkl`** *should* not be watched — it is regenerated from HA state, not
  hand-edited; its changes flow through the state pipeline (a build-time
  force-rerun of the dump is a separate follow-up, see the plan).
- **`hass.pkl`** *is* hand-edited and must still trigger a live reload, so
  `ServerApp`'s watch set adds its backing local file (`lib/hass.pkl`)
  **explicitly**. This explicit add is a deliberate seam for a future
  "local-dev only" toggle: watching the schema source is a checkout/dev-loop
  concern, irrelevant to a deployed add-on whose schema is fixed per image.

## Alternatives rejected

- **Keep relative file imports.** Fails the remote-author and pkl-lsp cases —
  those consumers have no local dump next to the entry.
- **Migrate `dump` only, keep `hass` as a file.** Impossible under the one-URI
  constraint: served-over-http `dump.pkl` resolves `hass.pkl` as an http sibling,
  and its entities then can't cross the file/http type boundary into
  `components.pkl`.
- **Fetch from self over the socket at startup.** A bootstrap cycle (HTTP server
  not yet up) and a pointless loopback; interception-by-path removes the need.
- **A published `hass.pkl` package.** Decouples the schema from the *live* home's
  version — the whole point is that the schema+dump track the running instance.
  (Package dependencies remain the right tool for shareable, non-live library
  code like `@fh-dashboard`; deferred, see the plan.)

## Canonical host (follow-up)

The prototype embeds `http://localhost:8080/system/pkl/…`. Because the server
matches by path, this literal only has to resolve for *external* consumers; the
follow-up unifies it on the single canonical URL from the PWA split-horizon
remote-access story (`docs/pwa-remote-access.md`), so a remote author points that
one name at their home exactly as the PWA does.
