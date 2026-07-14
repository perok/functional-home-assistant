# Plan: Pkl live schema endpoint + `@fh-dashboard` dependency

**Status: Track A complete (steps 1–4). See ADR 0010.** The two enabling
pkl-core capabilities are spiked and confirmed on 0.31.1 (see "Spike evidence"
below). Entries + `lib/components.pkl` import `hass.pkl`/`dump.pkl` over the
`/system/pkl/` http URL; the server resolves them in-memory on its own eval path
and serves the route for external consumers (verified: full suite green with
snapshots unchanged; live `dashboardServe` evaluates all entries and serves the
route). Track B (`@fh-dashboard` project dependency) is designed and deferred.

## The idea

Today an entry pulls in the domain schema + live dump by **relative file
import** — `import "lib/dump.pkl" as dump`, and transitively (entry → components)
`import "hass.pkl"`. Those two artifacts are special:

- `lib/dump.pkl` is **generated from the live home** every build (`PklDump` from
  `DataDump.fetch`), gitignored — the most per-home, least shareable thing in the
  system.
- `lib/hass.pkl` is a hand-written schema, but it is a **contract owned by
  functional-home** (it must agree with what `PklDump`/`DataDump` emit).

The goal: let **every** entry — evaluated on the fh server, in the `/edit` editor,
or in a stranger's repo — write the **same import text** for these two, and have
the *environment* decide how it resolves. The uniform text is an http URL served
by the running home:

```pkl
import "http://<home>/system/pkl/hass.pkl"
import "http://<home>/system/pkl/dump.pkl" as dump
```

| Who evaluates | How `http://<home>/system/pkl/*` resolves | Network? |
|---|---|---|
| **fh server (self-eval, startup + reload)** | custom `ModuleKeyFactory` → in-memory content | none — no socket, no bootstrap cycle |
| **`/edit` pkl-lsp; remote author (VS Code, CLI)** | real HTTP GET to the running server's `/system/pkl/` route | yes |

The reusable **library** (`components`/`theme`/`tokens`/`entry`) is a different
concern and stays out of the http endpoint — it wants version-pinning and a
third-party ecosystem, so it becomes a Pkl **project dependency** `@fh-dashboard`
(Track B), mapped to the local `lib/` in this repo and to a published
`package://…` for outside authors.

## Why this shape (the two problems it solves)

1. **The self-import cycle.** The fh server evaluates its own entries in-process
   on startup (`ServerApp` → `DashboardBuild`). If an entry imported the dump over
   http from the server itself, startup would need the socket already listening —
   a bootstrap loop — and the **offline** build phase (`sbt dashboardBuild`) and
   the test suite (`PklBuildSuite`, fake dumps in temp dirs, no server) would break
   entirely. Interception resolves the server's own imports from memory, so the
   server never talks to itself and offline paths keep working.

2. **Local dev editors are first-class (hard requirement).** pkl-lsp (behind the
   `/edit` CodeMirror editor, and any external VS Code + Pkl extension) resolves
   imports for completion by **actually fetching** the URL — it cannot run fh's
   Java `ModuleKeyFactory`. So the `/system/pkl/` route must genuinely exist and
   serve live schema + a freshly-rendered dump. Interception is a server-side
   shortcut, **not** a replacement for the endpoint. Get the route right and every
   editor gets live, always-in-sync completion for free.

## Spike evidence (pkl-core 0.31.1, no version bump)

Both runnable spikes lived in the scratchpad; results recorded in the
`pkl-core-http-intercept-and-local-deps` memory.

- **Interception (Track A).** A custom `org.pkl.core.module.ModuleKeyFactory`
  registered ahead of `ModuleKeyFactories.http` intercepted
  `http://home/system/pkl/dump.pkl` and returned
  `ResolvedModuleKeys.virtual(key, uri, text, cached)` **with no http server
  running**. `ModuleKey.hasHierarchicalUris = true` made the relative
  `import "hass.pkl"` *inside* the dump resolve back through the same factory
  (`…/hass.pkl`). The only gate is the module allowlist: `preconfigured()` refuses
  `http:` (it allows `https:`); `setAllowedModules([… "http:" …])` opens it.

- **Local project dependency (Track B).** `new ProjectDependenciesResolver(
  Project.loadFromPath(p), PackageResolver.getInstance(sm, httpClient, cacheDir),
  writer).resolve()` produced a `PklProject.deps.json` for a purely-local dep
  (`"type":"local","path":"lib"`) with **no network and no pkl CLI**;
  `EvaluatorBuilder.applyFromProject` then evaluated
  `import "@fh-dashboard/components.pkl"` → `{kind=button, label=Toggle}`.

## Design decisions

### The canonical host

Entries must embed a **single literal authority** for the uniform text to be one
string. Server-side self-eval matches on **path** (`/system/pkl/…`) and ignores
the host, so the literal is irrelevant there. For editors/remote authors the
literal must resolve to *their* running home.

- **Prototype:** entries write `http://localhost:8080/system/pkl/…`. Server
  intercepts by path (host-agnostic); the `/edit` pkl-lsp subprocess resolves
  `localhost:8080` to the same process it runs beside.
- **Follow-up:** unify the literal on the **canonical single URL** already defined
  by the PWA split-horizon remote-access story (see
  `docs/pwa-remote-access.md` / the `datastar-pwa-remote-access` memory), so a
  remote author points that one name at their home the same way the PWA does.

### One content source, two consumers

`hass.pkl` text is the static file `lib/hass.pkl` read from disk. `dump.pkl` text
is `PklDump.render(DataDump.fetch(...))` — the **same** rendering that
`prepareDumps` writes to disk today. A small `SystemPkl` seam yields
`contentFor(path): Option[String]` for `/system/pkl/hass.pkl` (disk) and
`/system/pkl/dump.pkl` (latest rendered dump, held in a `Ref`, refreshed on the
same cadence as `prepareDumps`). The `/system/pkl/*` HTTP route and the
interception `ModuleKeyFactory` both read `SystemPkl` — never two sources of truth.

The emitted `import "hass.pkl"` line in `PklDump` is **unchanged**: as an http
sibling of `…/system/pkl/dump.pkl` it resolves to `…/system/pkl/hass.pkl` (spike-
proven), exactly as it resolves as a file sibling today.

### Additive first, migrate second

Interception is purely additive: `ModuleKeyFactories.file` still resolves relative
file imports, so nothing breaks until an entry *chooses* an http import.
Track A lands the infra without touching any entry's imports; migrating entries
to http (and the test harness + watch handling) is a deliberate later step.

## Track A — prototype steps

Verify each with `sbt 'fh-datastar-view/testFull'` (fake dumps, full pipeline, no
live HA). The wire-format snapshots are the safety net — none should move (evaluated
JSON is import-mechanism-independent).

1. ✅ **Interception infra (additive; no entry changes).**
   - `runtime`/`build`: a `SystemPkl` provider — `contentFor(path): Option[String]`
     for `/system/pkl/{hass,dump}.pkl` — backed by disk `lib/hass.pkl` + a dump
     `Ref`.
   - `build`: an `InterceptFactory(SystemPkl) extends ModuleKeyFactory` returning
     `ResolvedModuleKeys.virtual` for matching `http` URIs, `hasHierarchicalUris =
     true`.
   - `PklBuild.eval` gains an optional `SystemPkl`: when present, build a
     configured `EvaluatorBuilder` (intercept factory first, then
     `standardLibrary`/`file`/`http`, `setAllowedModules([… "http:" …])`); when
     absent, today's `Evaluator.preconfigured()` path (default, so existing callers
     and the suite are untouched).
   - Verify: a **new focused unit test** evaluates an entry that http-imports
     hass+dump against an in-memory `SystemPkl`, with no server — mirrors the
     spike. Existing suite stays green (no import changes yet).

2. ✅ **The `/system/pkl/*` route.** `GET /system/pkl/:name` in `Server.scala`
   (flat `HttpRoutes.of`) serves `SystemPkl` content as `text/plain`, 404 for an
   unknown name. Covered by a `ServerSuite` test.

3. ✅ **Wire `SystemPkl` into the runtime.** `ServerApp` builds
   `SystemPkl.fromDisk(dashboardsDir)` after `prepareDumps` has written the live
   `lib/dump.pkl`, and passes it to `Server.resource` (the route). Reads are
   by-name off disk, so no `Ref` to invalidate — chosen over a dump `Ref` for the
   prototype (see "One content source, two consumers"). Interception via
   `PklBuild.eval(system = …)` is available but only exercised once step 4 switches
   the imports to http.

4. ✅ **Migrate hass+dump imports to http (behind the demos).** Switch entries/lib to
   the http URLs; update the test harness so its fake dump is served via `SystemPkl`
   rather than a temp-dir file; handle the watch gap — the import analyzer
   (`PklBuild.importSet`) drops non-`file:` imports, so an intercepted URL's backing
   **local** file (`lib/hass.pkl`) must be re-added to the watch set (the live dump
   is not watched — its changes flow from HA state, correctly). Verify: full suite
   green; editing `lib/hass.pkl` still triggers reload.

### Known limitations / follow-ups (Track A)

- **Watch of intercepted-but-local files.** Handled in step 4: `ServerApp` adds
  `lib/hass.pkl` to the watch set **explicitly** (the analyzer drops the http
  import, so it would otherwise stop reloading on schema edits). This explicit add
  is the seam for a **future "local-dev only" toggle** — watching the schema
  source matters in a checkout/dev loop but is irrelevant to a deployed add-on
  whose schema is fixed per image; gate it there when the dev/prod split is wired.
- **TODO (later): force-rerun the dump.** `dump.pkl` is deliberately NOT watched
  (it is regenerated from HA state, not hand-edited). But there is no way today to
  ask the server to re-fetch + re-render the dump on demand (e.g. after adding a
  device in HA) short of a restart. Add a build-time force-rerun path (an endpoint
  or signal that re-runs `prepareDumps` and re-evaluates entries) as a follow-up.
- **Canonical host** unification with the PWA URL (see above) — deferred.
- **HTTPS/cert.** The prototype allows plain `http:` (decided). A production
  cross-network deployment over `https:` needs a cert reachable by pkl-lsp; out of
  scope here.

## Track B — `@fh-dashboard` project dependency (deferred)

Designed and spike-verified (local half), not started. When picked up:

- Add a `lib/PklProject` (package `fh-dashboard`, base uri + version) and a
  consumer `PklProject` mapping `["fh-dashboard"] = import("./lib/PklProject")`.
- Resolve deps **in-process** (spike B: `ProjectDependenciesResolver` +
  `PackageResolver.getInstance`, no pkl CLI) and write `PklProject.deps.json`
  (gitignored); `PklBuild` uses `applyFromProject`.
- Entries write `import "@fh-dashboard/…"`; the monorepo resolves it to local
  `lib/` (preserves hot-reload — a *remote* package would make card iteration a
  publish loop). Publishing to a `package://…` registry (remote half: network +
  checksum, unspiked) is a later, post-API-stability step. "Published + local
  fallback" is this **resolution mapping**, chosen per project — not runtime
  failover.

## Non-goals / discipline

- No commitment to a 1.0 card API. Track A is pure plumbing; Track B's local
  mapping means the day `@fh-dashboard` is published, only the `PklProject` mapping
  flips — entries don't change.
- Phase discipline is untouched: the dump remains **build-time** (fetched/rendered
  once per eval, never on the live hot path); interception just changes *where* the
  build-time evaluator reads it from.
