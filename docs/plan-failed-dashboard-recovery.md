# Plan: a failed dashboard is a live error state, not a skipped entry (fh-datastar-view)

**Status: implemented** — landed on this branch in four commits (the `RendererState` ADT +
`LiveSlug` sweep, startup tolerance, the error-page/`Failed`-seam semantics, and the live-repair
`reloadEntries`); ADR 0018 records the decisions and `architecture-rendering-pipeline.md`
travels with the code.

## The four objectives

1. **A dashboard whose eval fails must keep being watched and rebuild live** (no restart to
   fix a typo).
2. **If ALL dashboards fail, the app must still start**, serve its HTTP routes, and wait for
   changes.
3. **If the configured default dashboard fails, it must remain the default** and show its
   error — not silently hand the root URL to another dashboard.
4. **Opening a dashboard with an eval/parse error must produce an HTML error response** when
   the request wants HTML.

## What is wrong today (`ServerApp.scala`, `Server.scala`)

- `prepareRenderers` (278) builds each entry `attempt`ed: failures are logged and **skipped**
  (no registry entry, no renderer ref, not re-watchable), and `IO.raiseWhen(built.isEmpty)`
  crashes boot when every entry fails — objectives 1, 2, 4.
- `run` (183) seeds renderer refs **only for built slugs**; `defaultSlugFrom` (372) picks the
  default from **built slugs only**, so a broken configured default is silently bypassed —
  objective 3.
- `watchSources` watches all entries, but `reloadEntries` (~504) does
  `rendererRefs.get(slug).traverse_(_.set(renderer))` — a failed slug has **no ref**, so a
  successful rebuild after a fix is **dropped**. That is the crux of objective 1.
- `pageResponse` (1307) 404s anything without a live renderer, so a failed-but-known slug is
  indistinguishable from an unknown one — objective 4.
- There is no test coverage of any of this (no suite exercises the skip-failed / all-fail
  startup path; `defaultSlugFrom` is only read by `ServerApp` + `EditorRoutes`).

## Decision (locked)

**Every discovered slug gets a first-class, permanently-registered live state** from boot:

```scala
// Server.scala, companion object, `private[runtime]`
sealed trait RendererState
object RendererState:
  case class Ready(renderer: Renderer) extends RendererState
  case class Failed(message: String)    extends RendererState
```

`LiveSlug.renderer` becomes `SignallingRef[IO, RendererState]`. A failed slug therefore has a
registry entry, a log, a doorbell, and a renderer ref from the first second — exactly like a
healthy one — and every transition (`Ready ⇄ Failed`) flows through the existing
`SignallingRef` reactivity: the watcher's reload just `set`s the ref, `publisherFor`,
`reloadRepaints`, `pull` and the page route read the state and act on it, and no slug is ever
"not registered" and then "re-registered" (no map mutation, no push-style minting on repair).

This is chosen over the alternatives:

- **Leave failed slugs out of the registry and "re-mint" them on first successful build.**
  "Minting" means constructing a fresh `LiveSlug` on demand — new renderer ref, new log, new
  doorbell, plus starting a new recorder fiber — which is exactly what `Server.push` (374)
  does for a brand-new slug. It only *sounds* small: the ref map is an immutable `Map` seeded
  at boot, so a late slug means threading a mutable map through `run` and `reloadEntries`;
  the error page needs a parallel slug→error structure with its own sync; and the registry
  would still need a way to know a slug is "known but failed" to distinguish its error page
  from a 404. Rejected.
- **`SignallingRef[IO, Option[Renderer]]` with the message stored elsewhere.** Rejected: the
  error page needs the failure message, and a side-table that must be kept in step with the
  ref is the drift this design is meant to remove. `Failed(message)` carries it on the value.

The chosen design is the **simplest** of the three: one ref per slug, registered at boot for
every discovered entry, and repair is a single `.set` on an existing ref — no LiveSlug
construction, no fiber start, no map mutation, no side table. The cost is the ref's value
type changing, a mechanical, compile-driven sweep of the ~8 `renderer` use sites in
`Server.scala` and the ~26 test ref constructions (see "The ripple" below; the Phase 1 sweep
can be driven by a Metals-generated scalafix rule).

### Eager failure where it belongs: the build phase

All of this leniency is the **runtime serve path** only. The build phase is untouched and
still fails eagerly: `BuildApp` (the `sbt dashboardBuild` tool) builds **one named entry** and
its eval errors propagate — there is no `.attempt` in `BuildApp.run` (`DashboardBuild.evaluate`
raises, `decode` raises a 400 on a model mismatch), and no artifact is written. That is
correct and stays: `dashboardBuild` is a CI/inspection gate where a broken dashboard must fail
the step. "A broken dashboard is a first-class error state" applies to a *running* instance,
where crashing the boot is useless — the whole point is to keep the editor alive so the
author can fix the source and have it recover live.

The `entries.isEmpty` boot failure stays (an empty dashboards dir has nothing to watch and
nothing to serve); only **"every entry failed to build"** stops being fatal.

## Design

### The `Failed` check stops at the top; it does not leak

The one thing the design must not do is thread `RendererState` through the machinery — that
would be the "every method checks for Ready" failure mode. It does not. The rule:

- **One converter, `rendererOf: RendererState => Option[Renderer]`** (on the ADT's companion).
  Every read site that needs a renderer goes through it: `Failed` collapses to `None`, which
  is a shape the codebase already models **everywhere** — `rendererFor` returns
  `Option[Renderer]`, `withSession`/`nodeDebug`/`sseStream` already handle `None` with
  `traverse_`/404, and `pull`'s "silent frame" branch already produces `Nil` for nothing-owed.
  So the bulk of `Server.scala` never learns `RendererState` exists.
- **Exactly five seams match on the state itself**, and all of them are already the
  top-level "stop things here" points — a route entry, the recorder fiber, the reload
  watcher, the page route. Everything below them (`recordFrame`, `Patches.resume`,
  `renderBodyTraced`, `renderPageTraced`, `swapHost`, `openSurface`, the pull computation)
  keeps taking a concrete `Renderer` and is unchanged.

| Seam | What it does with the state |
|---|---|
| `rendererFor` (266) — the shared `RendererState → Option[Renderer]` read | via `rendererOf`; `Failed(_)` → `None`, so `withSession`/`nodeDebug` treat a failed slug exactly as an unknown one (no-op / 404) |
| `publisherFor` (306) — the recorder fiber | match **once** at the top of the `switchMap` arm: `Ready(r)` → record with `r` (today's body, unchanged); `Failed(_)` → `Stream.empty` (nothing recorded, doorbell frozen). Log rotation stays arm>0, so a `Failed`→`Ready` transition rotates the log identity and every old cursor is invalid → reconnect repaints (the existing "a change in the switch window is harmless, every connection repaints on reload" argument covers the gap) |
| `openingPatches` (781) — the connect-time opening block | via `rendererOf`; `None` → `List(reloadPatch)`, no claim bookkeeping. Defensive — a failed slug's error page mints no session/conn, so this is only reachable by a bookmarked SSE URL or a slug that went `Failed` mid-session, and reload is the right answer either way |
| `reloadRepaints` (927) — the live-reload watcher | match on the (previous, current) pair: any `Failed` involved → `reloadPatch` (Ready→Failed: go to the error page; Failed→Ready: go to the live dashboard — the repaint path cannot target `#dashboard` on a page that has no dashboard). Ready⇄Ready keeps today's headHash/styleHash logic |
| `pageResponse` (1312) — the page route | the ONE place that needs the message: `Ready(r)` → today's path; `Failed(msg)` → the error page below. `liveFor = None` (unknown slug) → `NotFound()` unchanged |

Reads that never see the state at all, because `rendererOf` already collapsed it to the
`Option` they were written for: `sseStream`'s open-set seeding (509/519), `pull` (678 —
`None` → `Nil`, the existing silent-frame case), `push` (389 — sets `Ready`, a push always
succeeds). `withSession` and `nodeDebug` ride `rendererFor`.

### The error page (`pageResponse`, `Failed(msg)` branch)

A self-contained HTML document (no renderer, so no theme, no Datastar, no SSE, no session/conn
minting, no cursor):

- `<base href>` honoring the ingress prefix (same as `page`).
- Title `Dashboard <slug>`; body: the slug, the failure message, a short "fix the source in
  the editor — the dashboard reloads automatically" hint, and a link to
  `/edit/file/<slug>.pkl` (the `EditorRoutes` write path, so the broken default case is also
  directly fixable).
- `Content-Type: text/html`. Non-HTML consumers keep today's behavior: `/sse/...` connects and
  streams nothing but keepalives; `nodeDebug` and the action routes see a failed slug as
  absent (404 / NoContent) exactly as they see an unknown one.

### Startup and the reload loop (`ServerApp.scala`)

- `prepareRenderers`: keep `entries.isEmpty` fatal; **delete** `IO.raiseWhen(built.isEmpty)`;
  collect the failures into `failed: List[(String, String)]`; return
  `Prepared(entries, built, failed)`. (Doc comment: "skipping … only zero buildable is fatal"
  rewritten.)
- `run`:
  - `rendererRefs` seeded for **every** entry — `built` → `RendererState.Ready(r)`,
    `failed` → `RendererState.Failed(msg)`. (Construction moves from `built.traverse` to
    `prepared.entries` + both lists, or a single helper.)
  - `defaultSlugFrom(configured, all, built)` — **new signature**: the configured default wins
    if it is any discovered entry, **even a failed one** (objective 3); otherwise built
    `dashboard`, then entry `dashboard`, then first built, then first entry.
  - `AssetCache.build` list (CDN + built renderers' stylesheets/scripts) is unchanged — with
    zero built it still builds, from the CDN entry alone.
- `reloadEntries`: after re-eval, **set the ref for every entry** — `Right((renderer, _))` →
  `Ready(renderer)`, `Left(err)` → `Failed(err.getMessage)` — replacing the
  `rendererRefs.get(slug).traverse_` drop. Update `importsRef` from the entries that currently
  build (a repaired entry's import set joins the watch graph, so later edits to *its* imports
  fire too). Log wording: "reload failed (keeping previous)" → "dashboard is now broken"
  / "recovered", since there is no longer a silent previous renderer to keep.
- Known edge, accepted: the loose `file:` imports of an entry that has **never** built are not
  in the watch set, so editing only them (not the entry file, not the manifest) will not fire
  the reload until the entry builds once. The entry file itself and the `PklProject` manifest
  are always watched, so the common cases fire.

### The ripple

- `Server.scala`: `LiveSlug.renderer` type + the 8 use sites in the table, `Server.resource`
  / `fromFeed` signatures, and the `RendererState` ADT definition.
- `ServerApp.scala`: `liveServer` signature, `prepareRenderers`, `run`, `defaultSlugFrom`,
  `reloadEntries`, doc comments.
- Tests (all `SignallingRef[IO].of(Renderer.create(...))` → `Server.RendererState.Ready(...)`,
  plus any direct `renderer.get/set` assertions): `TestServer.scala` (`resource` 257,
  `fromWorkspace` 316, `served` 345), `ServerHarness.scala` (472), and the suites:
  `StateSurfaceSuite`, `SignalSlotSuite`, `DynamicGroupSuite` (137, 524),
  `AckedResumeSuite` (114), `SharedPassSuite` (77), `ServerRoutesSuite` (146, 177, 496, 538,
  592), `LiveStreamSuite` (323), `ResumeSuite` (201, 430), `SessionLifecycleSuite` (47, 116,
  184, 236, 362, 436, 495, 542, 629, 697, 741). Compile-driven: the compiler names every site.
- `EditorRoutes`: no change — `defaultSlug` is only injected into the editor config JSON (121),
  and editing a broken default is exactly the intended fix path. A test asserts it.

## Sequence (each phase = one green commit)

### Phase 1 — the state ADT, mechanically (no behavior change)

Add `RendererState`; change `LiveSlug.renderer`; sweep all sites + tests to
`Ready(...)`. At this phase every seeded ref is `Ready`, so behavior is identical; the
compiler proves completeness. Run the sweep with a Metals-generated scalafix rule
(`generate-scalafix-rule` then `run-scalafix-rule` on the module) for the mechanical parts —
e.g. wrapping every `SignallingRef[IO].of(Renderer.create(...))` / `.set(Renderer...)` in
`Server.RendererState.Ready(...)` — and let the compiler name the remaining type-level sites.
Verify: `sbt 'fh-datastar-view/testFull'`.

### Phase 2 — startup tolerates total failure; the default stays the configured one (objectives 2, 3)

`prepareRenderers` drops the all-fail raise and returns `failed`; `run` seeds every entry and
`defaultSlugFrom` takes the new signature. A test asserts the default-selection table
(configured-failed-wins, configured-missing-falls-back, no-configuration-prefers-built
`dashboard`, all-fail picks the first entry). Verify: `testFull` + new `ServerAppSuite`
below.

### Phase 3 — `Failed` semantics in the hot paths + the error page (objective 4)

`rendererOf` + the five state seams (`rendererFor`, `publisherFor`, `openingPatches`,
`reloadRepaints`, `pageResponse`) per the table; the error page; `sseStream`/`pull` read via
`rendererOf` with no new logic. New tests below. Verify: `testFull`.

### Phase 4 — live repair without restart (objective 1)

`reloadEntries` sets every ref; widen it to `private[runtime]` so the repair test can drive it
(the same seam `prepareRenderers`/`liveServer` already are). New tests below. Verify:
`testFull`.

### Phase 5 — docs (same commit as the code)

- `docs/architecture-rendering-pipeline.md`: the Boot pseudo-code (per slug: one
  `RendererState` in a `SignallingRef`; a failed entry is registered, watched, served as an
  error page, and rebuilds live on edit), the `GET /d/:slug` branch, and the §7 `LiveSlug`
  table row.
- New ADR `0018` ("a failed dashboard is a live error state") + its bullet in
  `docs/adr/README.md`.

## Tests

New suite `src/test/scala/fh/view/runtime/FailedDashboardSuite.scala` (or per-phase homes):

1. **Phase 2 / objective 2** — `prepareRenderers` on a workspace where every entry fails:
   returns `Prepared(entries, Nil, failed)` without raising; and the full
   `ServerApp.liveServer` over all-failed refs: `GET /` and `GET /d/:slug` → 200 HTML error
   page (slug + message in the body), `GET /d/unknown` → 404, editor + `/system/pkl/` routes
   still answer. (Drive the all-failed workspace through `TestServer.fromWorkspace`-style
   staging: the real eval path, all entries broken.)
2. **Phase 3 / objective 4** — content-type + body of the error page; `nodeDebug`/action
   routes on a failed slug behave as for an unknown one; `reloadRepaints`/`openingPatches`
   emit a `reload` patch across a Ready⇄Failed transition (a connected session is sent the
   reload patch when its slug breaks live).
3. **Phase 4 / objective 1** — register `Failed`; run `reloadEntries` with a fixed source;
   the ref is `Ready` and `GET /d/:slug` serves the dashboard — no restart. Reverse: a live
   dashboard whose source breaks becomes `Failed` and serves the error page. (Drive
   `reloadEntries` directly via the widened seam; optionally extend `TestServer` to run the
   real watcher for an end-to-end edit→repair assertion.)
4. **Objective 3** — the `defaultSlugFrom` table (pure, no IO).

Existing suites are untouched behaviorally — the Phase 1 sweep is type-only — so the wire
snapshots (`PklBuildSuite`) and visual gates are not expected to move.

## Verification

- After every phase: `sbt 'fh-datastar-view/testFull'`.
- Manual, per the module CLAUDE.md: `sbt dashboardServe` against the local workspace with a
  deliberately broken entry and a broken `DEFAULT_DASHBOARD`, then confirm in the browser
  (ADR 0006: visual changes need eyes) that the error page renders, that fixing the source
  repaints without a restart, and that all-fail still boots to the editor.
- `docs/architecture-rendering-pipeline.md` and the ADR travel with the code (module
  CLAUDE.md rule).

## Out of scope

- Picking a different default on failure (a "default candidates" list) — the requirement is
  that the configured default stays, and the escape hatch is unsetting `DEFAULT_DASHBOARD`.
- Retry/backoff of the build — the source watcher already re-evals on every edit.
- Watching for **brand-new** entry files (discovery runs once at boot); not changed here.
- Removing a failed slug from the registry (push-registry reclamation, TODO2) — untouched.
