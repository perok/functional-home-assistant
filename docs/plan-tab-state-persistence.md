# Plan: persist the active tab across reload/navigate via the cookie tier (fh-datastar-view)

Implements the first use of [ADR 0005](adr/0005-node-state-and-the-cookie-tier.md):
make a tabs group remember which tab was selected, restored **flash-free** on
reload and in-place navigate by reading a cookie on the GET and baking the
selected tab directly.

## Problem

A tabs group's active tab lives only in the `tab_<id>` Datastar signal and the
server-side open-set, both keyed to a `conn` minted per SSE stream. The first-paint
GET carries no signals, so it always bakes tab 0 (`Surface.defaultOpen` on the
first surface) and seeds `data-signals="{ tab_{{id}}: 0 }"`. Reload or navigate
therefore snaps back to tab 0.

## Approach (per ADR 0005)

Mirror the active tab into a **cookie** — the only client store the server reads on
the GET — keyed by the **bake-target component id** (which the server already
knows, so no state-name knowledge and no declared-`state` sugar is needed):

- cookie `fhui_<id> = <activeIndex>`.
- The tab click writes it inline (pure jsonnet, no Scala/JS helper).
- The server reads request cookies on the **GET**, **SSE connect**, and
  **navigate**, builds a `uiState: Map[componentId -> Int]`, and uses it to (a)
  bake the selected surface and seed `tab_<id>`, and (b) seed the open-set to the
  selected surface so it streams live. Same selection function in all paths ⇒
  consistent, flash-free.

The renderer stays pure: `uiState` is parsed in the HTTP layer (`Server`) and
passed in; default `Map.empty` reproduces today's behaviour (index 0).

## Changes by file

### `model/Dashboard.scala`
- `Surface`: add `bakeIndex: Option[Int] = None` — a surface's position within its
  `bakeInto` group, so the cookie value (parsed to an int at the point of use)
  selects a member without parsing surface-id suffixes. (`defaultOpen` stays for the
  fallback when no cookie / no match; it is effectively `bakeIndex == 0`.)
- Keep `ConfiguredDecoder` defaults.

### `runtime/Renderer.scala`
The `uiState` type is `Map[String, String]` (node id → **raw cookie value**), not
`Map[String, Int]`. A cookie value is a string on the wire; keeping it a string
keeps this cookie-read path domain-agnostic (a future collapsible-`bool` or
filter-`string` consumer needs no change to `uiStateOf`) and co-locates the
parse+clamp with the one place that knows the value is a tab index (the bake). The
integer-ness of a tab index is not a property of the transport.
- Factor the parse+clamp into one **pure** resolver, the single source of truth for
  both the chosen index and whether the cookie was malformed:
  `private def resolveActive(gid: String, uiState: Map[String, String]): (Int, Option[String])`
  — parse `uiState.get(gid)` with `.toIntOption`, keep it only if it indexes a real
  member of `gid`'s bake group, else fall back to the `defaultOpen` member's index,
  else `0`. The second element is `Some(warning)` **only when a value was present
  but off** (unparseable, or an int out of the group's range) — `None` when the
  cookie is absent (the normal case) or valid. The warning text names the node and
  the bad value, e.g. `s"ui-state cookie fhui_$gid='$raw' is not a valid tab index (0..${n-1}); using $fallback"`.
- `def selectedSurfaces(uiState: Map[String, String] = Map.empty): Set[String]`:
  group `dashboard.surfaces` by `bakeInto`; for each group pick the member at
  `resolveActive(gid, uiState)._1`. **Compile-safety across phases:** it *supersedes*
  the `val defaultOpenSurfaces` (`Renderer:144`), but that field has 4 callers (3 in
  `Server.scala`, 1 in `RendererSuite:622`). So **Phase 1 keeps** `defaultOpenSurfaces`
  as `= selectedSurfaces()` (name unchanged → all callers compile, behaviour identical);
  **Phase 2 deletes** it and switches the 4 callers to `selectedSurfaces(uiState)` /
  `selectedSurfaces()`.
- `render(Component)` (~`:289`): take a `uiState` param. When the component `id` has
  a bake group, use `resolveActive(id, uiState)._1` to bake that surface via
  `bakeAs -> html` **and** inject `"bakeIndex" -> idx.toString` (a backend-known
  structural var, like `id`).
- Add `def uiStateAnomalies(uiState: Map[String, String]): List[String]` — map
  `resolveActive` over the bake groups, collecting the `Some(warning)` messages. A
  **pure** function returning data (no logging), so the Renderer stays pure; the
  Server logs them (below).
- Thread `uiState` through `renderPage(states, uiState)`, `renderBody(states,
  uiState)`, and the internal `render(...)` recursion. All default to `Map.empty`.

### `runtime/Server.scala`
- Add a cookie parser: `def uiStateOf(req: Request[IO]): Map[String, String]` reading
  `req.cookies`, keeping `fhui_<id>` cookies and mapping `id -> rawValue` (no
  parsing here — the value is opaque to this layer). Interpretation and the
  untrusted-value **clamp** live in `resolveActive` (above), so a stale or
  hand-edited cookie can never bake a non-existent surface.
- After reading `uiState` at each entry point (GET page, SSE connect, navigate), log
  any anomalies: `renderer.uiStateAnomalies(uiState).traverse_(w => IO.println(s"[warn] $w"))`
  — this is the "print a warning if the user put something off in the cookie" step.
  Uses `IO.println` to match the module's existing diagnostic convention (no logging
  framework is in use); absent/valid cookies produce no output.
- `GET -> Root` / `GET -> /d/:slug`: capture `req`, pass `uiStateOf(req)` into
  `pageResponse(slug, uiState)` → `renderer.renderPage(states, uiState)`.
- `GET -> /sse/dashboard/:slug/patch`: capture `req`; in `sseStream`, seed
  `session.open.set(r.selectedSurfaces(uiState))` and **close over `uiState`** so
  the live-reload repaint (`:115-123`) reuses the same client's selection
  (`r.selectedSurfaces(uiState)` + `renderBody(st, uiState)`).
- `POST -> /sse/navigate/:slug`: read `uiStateOf(req)`, pass into `navigate`;
  there set `session.open.set(renderer.selectedSurfaces(uiState))` and
  `renderBody(states, uiState)`.
- Replace the three `r.defaultOpenSurfaces` call sites with
  `r.selectedSurfaces(uiState)`.

### `build/DashboardBuild.scala`
- `surfaceOf` field list: add `"bakeIndex"` so the builder's value is lifted onto
  the hoisted surface.

### `resources/dashboards/components.libsonnet` (the `tabs` card)
- Template seed: `data-signals="{ tab_{{id}}: 0 }"` → `data-signals="{ tab_{{id}}:
  {{bakeIndex}} }"` (renderer injects `bakeIndex`, default `0`).
- Builder: each inline surface gets `bakeIndex: i`.
- Tab button click expression — append the cookie write (resolves at build time
  since `NODE_ID` is spliced to the idBase):
  ```jsonnet
  '@post(\'/sse/surface/open/' + sid(i) + '\'); $' + sig + ' = ' + i
    + "; document.cookie = 'fhui_" + NODE_ID + "=" + i
    + ";path=/;max-age=31536000;samesite=lax'"
  ```
- The stale `TODO TODO an alternative to slots…` comment is already removed (this
  topic is now captured by ADR 0005, so the inline TODO is redundant).

## Tests (`RendererSuite`, `BuildPhaseSuite`)
- `selectedSurfaces(Map(tabsId -> "2"))` returns the index-2 surface of that group;
  empty map returns index 0 (parity with the old `defaultOpenSurfaces`).
- `render` of a `tabs` component with `uiState(id -> "1")` bakes surface `_t1`'s
  content into the panel host and injects `tab_<id>: 1`.
- **Clamp + warn:** `resolveActive(id, Map(id -> "99"))` and `Map(id -> "abc")`
  both return `(0, Some(_))` — falls back to index 0 **and** yields a warning; a
  valid `"1"` and an absent key both return `_ -> None` (no warning). `uiStateAnomalies`
  surfaces exactly the malformed entries.
- Build e2e (`BuildPhaseSuite`): hoisted tab surfaces carry `bakeIndex: 0..n`.
- Cookie round-trip in `ServerSuite` (it exists): a request with `Cookie: fhui_<id>=1`
  bakes/opens the index-1 surface; a malformed value logs and falls back to 0.

## Verification
- `sbt fh-datastar-view/testFull` (currently 50 green) after each phase — the gate.
- `sbt dashboardBuild` against live HA — surfaces hoist with `bakeIndex`.
  **Best-effort: skip + note if HA (`192.168.1.174:8123`) is unreachable; never block.**
- `sbt dashboardServe`, `/d/<tabs dashboard>`: select tab 2; **reload** → tab 2 is
  baked on first paint (no flash, active highlight correct, panel streams live);
  **navigate away and back** → restored; a second browser with no cookie still
  starts at tab 0; clearing the cookie resets to tab 0.
- Append a dated `## Update —` to ADR 0005 noting the tabs use landed, and refresh
  the memory status note.

## Phasing (each phase = one self-contained, green commit)
Tests live **with** the phase that introduces the behaviour (not deferred), so each
commit is independently green and complete. The gate after every phase is
`sbt fh-datastar-view/testFull` (needs no live HA). `sbt dashboardBuild` (live HA)
and the in-browser walk-through are **best-effort**: run if HA is reachable, else
note as pending — never block on them.

1. **Model + renderer plumbing (behaviour-identical).** `Surface.bakeIndex`;
   `resolveActive` + `selectedSurfaces`/`uiStateAnomalies`; thread `uiState`
   (default `Map.empty`) through `renderPage`/`renderBody`/`render`; inject
   `bakeIndex`; keep `defaultOpenSurfaces = selectedSurfaces()` (callers untouched);
   `surfaceOf` lifts `bakeIndex`; `tabs` template `{{bakeIndex}}` + builder
   `bakeIndex: i`; the already-pending `components.libsonnet` TODO removal rides
   here. Tests: the `selectedSurfaces`/`render`/`resolveActive`/build-e2e cases
   above (empty `uiState` ⇒ same output as today, so existing suites stay green).
   With `uiState` always empty so far, behaviour is identical.
2. **Cookie wiring (feature live).** `uiStateOf`; read it on GET / SSE connect /
   navigate; **delete `defaultOpenSurfaces`** and switch its 4 callers (3 in
   `Server.scala`, `RendererSuite:622`) to `selectedSurfaces(uiState)` /
   `selectedSurfaces()`; log `uiStateAnomalies` via `IO.println`; `tabs` click
   writes `fhui_<id>`. Tests: cookie round-trip / anomaly in `ServerSuite` +
   `RendererSuite` clamp cases.
3. **Close-out.** Dated `## Update —` on ADR 0005 (tabs use landed; in-browser
   verify pending if not yet done) + refresh the memory status note.

## Out of scope (deferred per ADR 0005, Decision 3)
- The declared-`state` sugar and the single-JSON-cookie + `theme.scripts` helper —
  build when a 3rd node-state consumer (collapsible sections, dynamic-group filter,
  theme override) lands. This plan uses per-key cookies written in jsonnet.
