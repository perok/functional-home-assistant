# Plan: delete the jsonnet authoring track (fh-datastar-view)

**Status: in progress** — Phases 0–2 and 4 landed; Phase 3 (porting the five real dashboards +
tabs) is under way by hand. Key deviation from the original plan: the `.jsonnet`/`.libsonnet`
sources are **not deleted in lockstep** — they stay on disk as inert porting references (the
backend no longer evaluates them) until the hand-port finishes, then they go. See the per-phase
"landed" notes below.

Pkl becomes the only authoring language. The exit is a **porting project, not a
deletion**: five real dashboards (`dashboard`, `energy`, `inngangsetasje`,
`underetasje`, `overetasje`) plus `tabs` exist only in jsonnet; the Pkl track has only
the two demo entries (`pkl-demo`, `pkl-tabs`). Everything downstream of evaluation is
already shared, so the backend model does not change at any point in this plan.

What the deletion buys: one card library instead of two kept in lock-step
(`components.libsonnet` 662 lines duplicating `lib/components.pkl` 665), the
"both languages must emit identical wire format" constraint gone (new cards land once),
sjsonnet off the classpath, no 12k-line `dump.libsonnet` regenerated per startup, and
`BuildPhaseSuite`'s duplicate coverage folded into `PklBuildSuite`.

## Decisions (locked 2026-07-07)

1. **Ergonomics-first**: apply `docs/plan-pkl-authoring-ergonomics.md` BEFORE porting.
   The refactor changes the authoring API, not the wire format, so byte-parity checks
   against the jsonnet originals still work afterwards — and the five dashboards get
   authored once, in the final API, instead of ported in the verbose
   `new c.EntityCard {...}` style and rewritten later.
2. **Entry base module**: entries `amends "lib/entry.pkl"` (see Phase 2) — kills the
   `cards = c.cards; theme = themeMod.theme` scaffold every entry repeats, and gives the
   per-dashboard `title` (TODO2) a typed home.
3. **Bind default flips to `127.0.0.1`**: `/sse/action/*` drives HA with the server's
   token and no auth, so LAN exposure becomes opt-in via `HOST`. Independent one-liner;
   do it any time (see "Independent items").
4. `SourceEval` survives the deletion as a thin named seam over `PklBuild` (drop the
   extension dispatch); the source-agnostic boundary is worth ~20 lines.
5. The `fhui_` cookie-bleed fix (below) waits until AFTER the deletion so the tab
   template changes in one language, not two.

## Sequence

### Phase 0 — safety net (already in TODO2)

**LANDED.** Wire-format snapshot test in `PklBuildSuite`: snapshot the evaluated `{cards, card}`
JSON of the Pkl demo entries so every phase below is byte-identity-checked by
`sbt 'fh-datastar-view/testFull'`, not manual diffing. Snapshots live in
`src/test/resources/snapshots/`; regenerate deliberately with `FH_UPDATE_SNAPSHOTS=1`.

### Phase 1 — ergonomics refactor

**LANDED** (recorded in ADR 0006). Executed `docs/plan-pkl-authoring-ergonomics.md` as designed
(call-style factories, Mapping-branch dynamic groups, fluent predicates). Byte identity for
`pkl-demo`/`pkl-tabs` held.

### Phase 2 — pre-port enablers (Pkl lib additions)

**LANDED.** `lib/entry.pkl` base module (entries `amends` it; required `card`, optional
`title`/`surfaces`/`theme`; the missing-`card` error points at `entry.pkl` and its doc comment
explains where the real fix is), `c.floorView(floor)`, and the per-dashboard `<title>` backend
(optional `title` in the wire model, HTML-escaped; Server falls back to the slug).

- **`lib/entry.pkl`** — the entry base module:

  ```pkl
  // lib/entry.pkl
  module entry
  import "components.pkl" as c
  import "theme.pkl" as themeMod

  cards = c.cards
  theme = themeMod.theme
  title: String? = null          // per-dashboard <title>; server falls back to slug
  surfaces: Mapping<String, c.SurfaceDef> = new {}
  card: c.Node
  ```

  An entry then opens with `amends "lib/entry.pkl"` and sets only `card` (+ optional
  `surfaces`/`title`). An entry wanting a different theme overrides the field.
  **Spike first** (per the CLAUDE.md rule): where does the error point when an amending
  entry forgets required `card`? Also confirm `amends` + sibling imports resolve from a
  top-level entry into `lib/`. The `title` field needs the small backend half of the
  TODO2 title item (optional model field + `Server.page` uses it).
- **`c.floorView(floor)`** — the pattern three of the five dashboards hand-roll: one
  section per area that has lights (`area.area_name` title + a slider per light),
  driven by the typed dump's `Floor.areas` / `Area.lights` member lists (already
  generated — no dump change needed). Port of `overetasje.jsonnet`'s `areaView`.

### Phase 3 — port the six entries, one commit each

**IN PROGRESS — the user is hand-porting the five real dashboards + tabs to Pkl.** Deviation from
the original plan: the `.jsonnet` originals are **not** deleted alongside each `.pkl` — the
backend no longer evaluates them (Phase 4 removed the jsonnet track and, with it, the
slug-collision guard), so they stay on disk as inert porting references and are deleted once the
port completes. Discovery scans `*.pkl` only, so a `.pkl` port and its `.jsonnet` original can
coexist harmlessly.

Per entry:

1. Author the `.pkl` (final API, `amends "lib/entry.pkl"`), preserving the exact layout
   tree shape — node ids are position-derived, so same shape ⇒ same ids ⇒ stable
   `fhui_` cookies and stable SSE patch targets. (A deliberate shape change is allowed;
   it just resets tab cookies.)
2. Verify: evaluate the jsonnet original and the Pkl port against the same dump
   (BuildApp with `DASHBOARD_ENTRY`, or a test that evaluates both), then
   `python3 -m json.tool --sort-keys` + `diff` → empty. Note `pkl-demo`/`pkl-tabs`
   deliberately differ from their jsonnet counterparts in places (ADR 0006 deviations:
   `openPopup`/`openPopupInline` split, `cssClass`) — parity is against the entry being
   ported, not the demos.
3. Swap in one commit; `sbt 'fh-datastar-view/testFull'` green; browser check per
   ADR 0006 (best-effort if HA unreachable — never block).

Suggested order: `energy` (trivial) → `inngangsetasje`/`underetasje` (same shape) →
`overetasje` (uses `floorView`) → `tabs` → `dashboard` (largest; the default slug).
The demo entries `pkl-demo`/`pkl-tabs` fold into the ported real dashboards or are
deleted at the end of this phase — their coverage lives in `PklBuildSuite` fixtures,
not in the live entries.

### Phase 4 — deletion sweep

**LANDED (modified — code deleted, but the jsonnet *sources* stay on disk pending the hand-port).**
`JsonnetBuild` + the sjsonnet dependency deleted (os-lib, formerly transitive via sjsonnet, is now
a direct pin). `SourceEval` is a Pkl-only seam (extension dispatch dropped, seam kept).
`DashboardBuild.prepareDumps` writes only `lib/dump.pkl` — `dump.libsonnet` is no longer generated,
though its `.gitignore` line deliberately stays until the port finishes. `normalizeChildren`
deleted (Pkl's `Listing<LayoutNode>` always renders arrays). `discoverEntries` scans `*.pkl` only
(so cross-language slug collisions are impossible — the guard is gone). `BuildApp` defaults to
`dashboard.pkl` (which errors until that entry is ported). `BuildPhaseSuite` triaged (11→8 tests).
The `.libsonnet`/`.jsonnet` files themselves are NOT deleted here (see Phase 3).

- `JsonnetBuild.scala`; the sjsonnet dependency (`build.sbt:137`).
- `components.libsonnet`, `theme.libsonnet`, `tokens.libsonnet`; the `dump.libsonnet`
  write in `DashboardBuild.prepareDumps` (and `DataDump`'s jsonnet-shaped output if
  nothing else consumes it — `PklDump.render` takes the same fetch).
- `SourceEval`: drop the `.jsonnet` branch + extension dispatch (keep the seam,
  Decision 4); `literalLocator` drops the jsonnet extensions;
  `ServerApp.discoverEntries` scans `*.pkl` only.
- `DashboardBuild.normalizeChildren`: dead once jsonnet is gone — Pkl's
  `Listing<LayoutNode>` always renders arrays. Verify by grep + a failing-fixture
  check, then delete.
- `BuildPhaseSuite`: port any hoist/watch coverage not already duplicated in
  `PklBuildSuite` to Pkl or raw-JSON fixtures; delete the rest.
- `.gitignore` entries for `dump.libsonnet` / `dashboard.json` — keep `dashboard.json`,
  drop the libsonnet line.

### Phase 5 — docs / knowledge sweep

**LANDED.** ADR 0006 + the other ADRs, `docs/adr/README.md`, CLAUDE.md, and TODO2 reworded to
Pkl-only (jsonnet framed as inert porting references, not a second track). This plan's own status
notes updated.

- ADR 0006 rewritten in place: "Pkl is the authoring language", not "a second track".
  Check every other ADR for "jsonnet composition" phrasing (entity-card, surfaces,
  slot-model ADRs) and rewrite where the statement is about the authoring layer.
- CLAUDE.md dashboard section (workflow, key-files table, the "both languages" wire
  contract line becomes single-language).
- TODO2 items naming `components.libsonnet`; the agent memory file
  "presentation is jsonnet composition" gets the same correction.

## Independent items (runtime-only; no authoring impact)

Slot these around the spine freely — none touch the wire JSON.

- **Per-entity patches for dynamic groups (Tier 1)** — **DONE (Tier 1 AND Tier 2,
  2026-07-07).** The group is no longer the patch unit. As specced below, plus
  Tier 2 un-parked with a churn heuristic: membership add/remove is patched
  per-entity (`mode remove`; `before #successor`/`append #group`) when
  `churn < MaxChurnFraction (0.5) × rendered members` and the group is already
  established in the diff cache; at/above the boundary (e.g. 1-of-2, the last
  member) or post-reload, the whole group repaints and its child cache entries
  are pruned. Strict `<` so exactly-half repaints. The reload-race concern that
  parked Tier 2 is mitigated by the established-group gate (first change after a
  reload always repaints); the residual connect-gap insert race is documented in
  `Server.renderMembershipChange` — bounded, self-heals on the next repaint.
  Original spec (implemented, with `sharedChangedHtml` now `sharedPatches` and a
  `Patch` ADT at the diff boundary):
  - Wrap each dynamic case render in the static path's wrapper,
    `<div class="fh-cell" id="{groupId}_{sanitize(entityId)}">` — the id already
    exists logically in `Renderer.renderCase`, it just never reaches the DOM.
  - `dynamicAffected` already evaluates the query against `change.previous` and
    `change.current`; keep the two booleans instead of collapsing to "touched":
    `prev ∧ cur` (in-place member update — the hot path) re-renders ONE card and
    outer-morphs its child id; membership changes (`¬prev ∧ cur`, `prev ∧ ¬cur`)
    keep today's whole-group re-render. Case-branch switches are covered free —
    the child id doesn't encode the branch, so the morph swaps the card.
  - Both diff paths in `Server` (`sharedChangedHtml`, `changedPatches`) emit
    child-scoped fragments; when a group itself repaints, prune its child entries
    from the cache (staleness only causes a harmless re-send, but pruning keeps the
    caches coherent). `RendererSuite` HTML assertions gain the wrappers.
  - **Tier 2** (was parked; implemented as described in the DONE note above):
    true add/remove deltas (`mode remove`, `before #successor` / `append #group` —
    the successor is computable, children sort by entityId), gated by the churn
    heuristic + established-group check instead of "revisit if measured".
- **Bind default `127.0.0.1`** (Decision 3) — **DONE.** `ServerApp`'s `HOST` fallback flips from
  `0.0.0.0`; LAN exposure is now opt-in via `HOST`.
- **Vendor `datastar.js`**: serve the pinned bundle from ember (resources file + one
  route) instead of the jsdelivr CDN, so the dashboard survives an internet outage —
  precisely when local controls matter. `Server.DatastarCdn` becomes a local path;
  keep the version pin visible in the filename or a comment.
- **`entity_id` predicate + `c.inArea(area)`** — dynamic groups scoped to an area
  ("lights currently ON in the kitchen"): live state doesn't carry area membership, so
  add `"entity_id"` as a `Cmp` property in `Renderer.matches` (one line) plus a typed
  builder expanding at build time to `POr` of `entity_id eq` over the area's members
  from the typed dump. Supersedes the static half of the TODO2 "inArea/onFloor
  helpers" item (`Area.lights` already covers static composition).
- **`fhui_` cookie bleed fix** (after Phase 4, Decision 5): the cookie key is the
  position-derived node id and `Server.uiStateOf` reads every `fhui_*` cookie
  regardless of dashboard, so two dashboards with tabs at the same tree position share
  tab state. Fix: `fhui_<slug>_<id>` via a `@@SLUG@@` token spliced where the slug is
  known (`ServerApp` forces the slug at decode time), `uiStateOf` filters on the
  current slug's prefix. Small design pass on the splice point before implementing.

## Verification (every phase)

- `sbt 'fh-datastar-view/testFull'` green (no live HA needed) — the gate.
- Phases 1 & 3: sorted-JSON diff empty (ergonomics: demos before/after; ports: jsonnet
  original vs Pkl port).
- `sbt dashboardBuild` + browser walk-through per ADR 0006: best-effort when HA
  (`192.168.1.174:8123`) is reachable; never block.

## Out of scope

- The automations track, deployment story, and everything under TODO2's "Parked".
- Pkl `package://` publishing of the card library — post-deletion concern.
- Auth on the action endpoints beyond the bind-address flip — revisit with the
  deployment story (home-addon).
