# TODO2.md execution plan — fh-datastar-view feature sprint (subagents, Opus 4.8 medium)

## Context

TODO2.md (committed e9c2e8b) is the cleaned-up backlog for `fh-datastar-view`. This plan
executes it. Scope confirmed with the user:

- **In**: both Quick wins (page title, `inArea`/`onFloor`), and from Worth doing: runtime
  discovery of new dashboard files, disconnected indicator, registry-change refresh, dynamic
  case containers, author-facing docs. From Bigger bets: **event coalescing only**.
- **Out** (user decision): "show if" conditional visibility (own design session + ADR later);
  the whole CSS/class pass-through item (belongs to the user's own `docs/plan-tw-theme.md`).

Execution mirrors the previous sprint: **one general-purpose subagent per step, `model:
opus`** (Opus 4.8, medium effort), run synchronously, one commit per step on a dedicated
sprint branch off `main` (the original `pkl` branch was merged via PR #2 on 2026-07-04),
`sbt -batch 'fh-datastar-view/testFull'` green after every code step (78 tests today).
Each step's TODO2.md checkbox is ticked in that step's commit.

Facts established by exploration (verified in code):

- `Dashboard` decodes via `ConfiguredDecoder` + `withDefaults` (Dashboard.scala:54-57, 338-344)
  — an optional field with a default needs no decoder work. `Server.page` (Server.scala:491-517)
  hardcodes `<title>Home Assistant</title>` at :508; its only caller `pageResponse` (:468-483)
  holds the `Renderer`.
- `Renderer.matches` (Renderer.scala:570-602) resolves Cmp properties `domain`/`state`/
  `attr:<name>` only; **`entity_id` falls into `case _ => ""`** — so an area helper that
  expands to entity-id comparisons needs a small runtime addition. Live `EntityState` carries
  no area/floor; the dump does (each entity has `area_id`/`floor_id`; areas/floors carry no
  entity lists).
- `HomeAssistantApi.event(event: Option[String])` (ha-api HomeAssistantApi.scala:121-123)
  **ignores its argument and hardcodes `subscribe_events(Some("state_changed"))`**; the
  underlying `subscribe_events` command accepts any event type. Registry refresh is unlocked
  by respecting the argument.
- Runtime discovery is structural: `rendererRefs` (ServerApp.scala:64-69) is a fixed
  `Map[String, SignallingRef[IO, Renderer]]`; `Server.resource` (Server.scala:527-548) builds
  `sharedTopics` once from `renderers.keySet` and backgrounds one merged publisher stream;
  `watchSources` (ServerApp.scala:186-239) watches only known entry files + imports (never the
  directory) and re-evaluates a fixed `entries` list.
- Dynamic cases are single leaves by construction: `DynamicCase(when, card, slots)` has no
  children (Dashboard.scala:151-155); `renderCase` (Renderer.scala:429-443) passes
  `childrenHtml = Nil`; subject inheritance lives only inside `renderTemplate`; both builders
  (`d.case` in components.libsonnet:630-638, `dynCase` in lib/components.pkl:514-524)
  deliberately drop children today.
- Datastar v1 emits a `datastar-fetch` lifecycle event (types `started`/`finished`/`error`/
  `retrying`/`retries-failed`) usable via `data-on:datastar-fetch__window` — exact event name
  to be confirmed against the pinned v1.0.2 bundle during implementation
  (sources: github.com/starfederation/datastar issues #583/#785, release notes).

## Ground rules for every subagent prompt

- `model: "opus"`, general-purpose, synchronous (`run_in_background: false`).
- Read the relevant ADRs (`docs/adr/`) before touching the module; ADRs are current-state
  docs — when a step changes a recorded decision, rewrite the paragraph in place.
- Never touch: `modules/home/src/main/scala/AppHome.scala`, `modules/home/src/main/scala/test.scala`,
  `TODO.md`, `docs/plan-tw-theme.md`, `.claude/`, `build.sbt.semanticdb`,
  `chat-log-pkl-parity-2026-07-03.txt`, `project/build.properties`. Never commit them.
- sbt batch syntax: `sbt -batch 'cmd1; cmd2'` as ONE quoted arg.
- Commit messages end with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01H1Pzti7eWTC69nqpQt8gc2`.
- Jsonnet/Pkl parity: any authoring-surface change lands in BOTH components.libsonnet and
  lib/components.pkl (+ hass.pkl/theme.pkl where relevant), with BuildPhaseSuite/PklBuildSuite
  mirrors.
- No live HA available — everything verified via tests/stubs; live items go on the manual
  checklist at the end.

## Steps (one subagent + one commit each, in order)

### S1. Page title (S)

- `Dashboard` gains `title: Option[String] = None` (no decoder change; `validate` untouched).
- Expose it on `Renderer` (e.g. `def title: Option[String]`), thread from `pageResponse` into
  `page(slug, body, stylesheets, title)`; render `title.getOrElse(slug)` (escaped) at :508.
- Authoring: **no Pkl class edit** — verified: there is no top-level dashboard class; Pkl
  entries are free-form modules declaring `cards`/`theme`/`card`/`surfaces` as bare
  properties, and `PklBuild` renders with `omitNullProperties`. Parity = set `title` as a
  module property on one demo `.pkl` entry; jsonnet likewise (free-form object) on
  dashboard.jsonnet.
- `page` needs a tiny `escapeHtml` helper (Server has none today).
- Tests (+4 → 82): two ServerSuite cases (authored title incl. escaping via `&`; fallback =
  slug — page GET renders synchronously, no SSE readiness wait needed); extend the existing
  BuildPhaseSuite/PklBuildSuite demo-decode tests to assert `title`.
- ADR 0002:156-158 describes the `Server.page()` shell — add the per-dashboard `<title>` to
  that paragraph in place. Tick the TODO2.md item.

### S2. `inArea` / `onFloor` predicate helpers (S–M)

Two halves:

1. **Runtime**: add `entity_id` to the Cmp property match in `Renderer.matches` (and to the
   `Predicate.Cmp` doc comment in Dashboard.scala:134-146). One new RendererSuite case.
2. **Authoring (build-time expansion)**: `inArea(dump, area)` / `onFloor(dump, floor)` helpers
   in the `dynamic` namespace (jsonnet) and as typed functions (Pkl): filter `dump.entities`
   by `area_id`/`floor_id`, emit `Or([Cmp(entity_id, eq, <id>), ...])` (empty set → the
   never-matching predicate, mirroring `always`'s inverse). The dump is passed in by the
   author (helpers stay pure; the dump import already exists in every dashboard).
   - Pkl: dump entities are typed (`lib/hass.pkl`); helper takes the module's entity listing —
     verify the cleanest typed signature against `lib/dump.pkl`'s generated shape (named
     properties + per-domain lists; may need a `Listing<Entity>`-accepting variant).
- Demo usage in one jsonnet + one pkl dashboard (composes with `And` — e.g. lights in an area).
- Tests: BuildPhaseSuite/PklBuildSuite decode the emitted predicate; RendererSuite matches
  entities through an entity_id Or-set.
- ADR 0003/0004 mention properties `domain`/`state`/`attr:` — update the property list in
  place. Tick the TODO2.md item.

### S3. Disconnected indicator (S–M)

- Contract: a reserved Datastar signal (e.g. `$fh_conn`, default connected) + a theme-owned
  indicator element. Backend emits the listener wiring in `Server.page` body tag (it already
  owns the Datastar bootstrap): `data-on:datastar-fetch__window` handler that sets the signal
  from `evt.detail.type` (`retrying`/`error`/`retries-failed` → disconnected; `started`/
  `finished` on the SSE stream → connected). Presentation stays authoring-side: both themes'
  `chrome` gain a small `data-show="$fh_conn == 'down'"`-style banner (class contract like
  `.fh-disconnected`, styled in theme `styles`).
- First implementation task: confirm the exact event name/payload in the pinned v1.0.2 bundle
  (fetch the CDN URL from `Server.DatastarCdn` and grep, or consult data-star.dev reference);
  if the event fires per-request rather than per-SSE-stream, scope the handler to the SSE
  element (`data-on:datastar-fetch` on `<body>`, checking `evt.detail` for the patch URL).
- Keep it minimal: no reconnect logic (Datastar retries itself); just the visual cue.
- Tests: ServerSuite asserts the page carries the handler attribute; theme chrome banner is
  covered by existing chrome validation (no `id="dashboard"` change). Live behavior goes on
  the manual checklist. Tick the TODO2.md item.

### S4. Registry-change refresh (M)

- **ha-api fix**: `HomeAssistantApi.event` passes its argument through to `subscribe_events`
  (today hardcoded `state_changed`). Audit callers: `StateStore.create` already passes
  `Some("state_changed")` explicitly, so behavior is preserved; fix any caller relying on the
  ignore-bug.
- **ServerApp**: extract the "re-evaluate all entries + hot-swap renderers + update importsRef"
  block from `watchSources`' reload loop into a reusable `rebuildAll` (S6 reuses it too).
  New background stream: subscribe to `entity_registry_updated`, `area_registry_updated`,
  `device_registry_updated`, `floor_registry_updated` (one `api.event(Some(...))` each, merged),
  debounce (registry edits come in bursts), then `DashboardBuild.prepareDumps(api, dir)` →
  `rebuildAll`. Failures log and keep serving (same policy as source-reload).
- Tests: ha-api-level — the subscribe command carries the requested event type (existing WS
  test seams); fh-datastar-view — `rebuildAll` extraction covered by existing reload tests;
  a stubbed registry-event → prepareDumps+rebuild trigger test if the StubApi seam allows
  (StateStore has `private[runtime]` test seams as precedent — add one rather than sleeping).
- Live verification is HA-blocked → manual checklist. Tick the TODO2.md item.

### S5. Dynamic case containers (M–L)

- **Model**: `DynamicCase` gains `children: List[LayoutNode] = Nil` (withDefaults ⇒ no
  decoder churn). Add `LayoutNode.SelfSentinel = "$self"` constant.
- **Rebinding = pure subtree rewrite before render** (do NOT thread self through
  renderTemplate): companions `private[runtime] def rebind(node: LayoutNode, entityId:
  String): LayoutNode` and `rebindSlots(slots, entityId)`. Rules: slot with `entityId ==
  Some("$self")` → `Some(matched)`; the `entity_id` slot with `literal == Some("$self")` →
  matched; recurse `Component.children`; STOP at nested `Dynamic`; never rewrite other
  literals (an author-typed "$self" label survives). Side effect: this FIXES the currently
  dead `exprOf(d.matched, …)` form (today it resolves against nonexistent entity "$self").
- **renderCase** becomes `renderCase(groupId, entityId, c, states, uiState)`:
  `rebindSlots(c.slots, entityId).updated("entity_id", SlotSource(literal = Some(entityId)))`;
  children rendered via the existing `render(rebind(child, entityId), List(i), id + "__",
  states, uiState)` → per-match-unique ids like `c_2_light_a__c_0` (the `idPrefix` param on
  `render` already exists — per-match prefixing is mandatory or matched siblings collide on
  DOM ids). `renderDynamic`/`render` thread `uiState` through. `renderTemplate`, slot
  resolution, and the identity cache stay untouched.
- **Reverse index unchanged** (group-level re-render covers children). Flag in ADR: a nested
  child slot reading a DIFFERENT static entity renders correctly but never triggers a group
  re-render — generalizes ADR 0003's existing invariant.
- **validate**: recurse case children with the static walk (errors prefixed `s"$nodeId: "`);
  reject nested `Dynamic` inside case children.
- **Builders**: jsonnet `d.case` keeps children via
  `[if std.objectHas(node,'children') && std.length(node.children) > 0 then 'children']:
  node.children`; Pkl `Case` gains `children: Listing<LayoutNode> = new {}`, `dynCase` copies
  `node.children`. `d.when`/`d.group`/`dynWhen`/`group`/`groupCases` unchanged.
- Before claiming zero visible diff, scan existing dashboards for `exprOf(d.matched)` uses.
- Tests: RendererSuite — container subtree per match with unique wrapper ids; `$self` slot
  rebind incl. the exprOf shape; literal `$self` in a non-entity_id slot NOT rewritten; a
  child slot pinned to its own static entity works (+ limitation comment). Validate
  rejections. BuildPhaseSuite jsonnet round-trip (`d.case(..., c.col([c.slider(d.matched),
  c.entityCard(d.matched)]))` → `cases.head.children.size == 2`); PklBuildSuite dynCase
  children mirror.
- ADR 0003 rewrites in place: model gains children + runtime-resolved `$self`; builder
  paragraph drops "drops the unused children"; "single leaf" → "single node (leaf or
  container subtree)"; invariant restated for subtrees; perf note scales by subtree size.
- Known limitation (out of scope, note in ADR): inline surfaces / `@@NODE_ID@@` inside case
  children are not hoisted (`hoistInlineSurfaces.walk` never recurses into cases) — consider
  a validate-time token check. Tick the TODO2.md item.

### S6. Runtime discovery of new dashboard files (L)

- **New file** `fh/view/runtime/DashboardRegistry.scala`: `final class DashboardRegistry
  private (ref: SignallingRef[IO, Map[String, Entry]])` where `Entry(renderer:
  SignallingRef[IO, Renderer], topic: Topic[IO, ServerSentEvent])`. Methods: `discrete` /
  `snapshot` / `get` / `contains` / `renderer` / `slugs`; race-safe `add(slug, renderer):
  IO[Either[String, Entry]]` (mint refs FIRST, then `ref.modify`; Left if the slug got
  claimed meanwhile); `swap(slug, renderer): IO[Boolean]`; companion `create(initial)`.
- **Server**: constructor becomes `(api, stateStore, registry, defaultSlug, sessions)` — no
  pre-built topic map. New combinator `perSlug[A](f: (String, Entry) => Stream[IO, A]):
  Stream[IO, A]` = `registry.discrete.mapAccumulate(Set.empty)((seen, m) => (seen ++
  m.keySet, (m -- seen).toList)).flatMap(Stream.emits).map(f.tupled).parJoinUnbounded` —
  starts one inner stream per slug, existing and future. `sharedPatchPublishers`, the
  connection's shared-topic merge (`evalFilter` on `session.slug` — must become a dynamic
  registry lookup, because navigate is an in-place body swap, NOT fresh HTML), and
  `reloadRepaints` (`.drop(1)` suppresses the repaint at add time) all route through
  `perSlug`. Routes move to IO-based registry lookups (404 body byte-identical to today).
  `Server.resource` no longer builds topics.
- **ServerApp**: `discoverEntriesLenient(dir): IO[(List[(String, String)], List[String])]` —
  startup keeps hard-failing on slug collisions; at runtime collisions are logged and the
  newcomer dropped. `watchedSet` gains the dashboards dir itself (the dir watch rides the
  existing reconcile/importsRef machinery). Reload re-discovers instead of iterating the
  fixed entries list → partition: existing slugs rebuild all-or-nothing (`swap`), fresh slugs
  build per-entry with `.attempt` → `add` (a broken new file never blocks existing reloads) →
  `importsRef.set` from the full rebuilt set.
- **Deletion**: a removed file leaves a zombie renderer until restart (logged, documented);
  re-creation hot-reloads through the existing path.
- Tests: migrate the existing shared-pass ServerSuite setup to `DashboardRegistry.create(
  Map(...))`; new "dashboard added at runtime gets routed page + topic + live publisher"
  (GET → 404, `registry.add`, SSE fragment arrives, GET → 200); new "colliding add rejected,
  original keeps serving".
- ADR 0002: one sentence rewritten — topics are minted per registry entry; publishers and
  subscriptions attach dynamically via `perSlug`.
- Risks (accepted): fs2 dir-watch may report recursively on some platforms (harmless under
  the debounce; correctness doesn't depend on it); `parJoinUnbounded` failure blast radius
  (one publisher crash kills the merged stream — same as today's merged publisher);
  add/publisher race window is harmless (first paint is direct-rendered).
  Tick the TODO2.md item.

### S7. Event coalescing under state_changed bursts (M)

- After S6 (publisher shape settles there). In the shared per-slug publisher
  (`Server.sharedPatchPublishers`): batch `stateStore.changes` with
  `groupWithin(maxBatch, window)` (window ~50–100ms — pick and record in ADR 0003), compute
  the affected node-id set across the whole batch (dedupe: a node touched by N changes in one
  window renders once), render + diff + publish once per batch. Per-session loops (open
  surfaces, bake owners) get the same batching treatment if trivially shareable; otherwise
  leave and note.
- Remove the FUTURE comment at the publisher; rewrite ADR 0003's future-work paragraph in
  place (coalescing is now current).
- Tests: ServerSuite — N rapid changes to entities feeding one node produce one render
  (extend the existing `CountingRenderer` spy test); a change to a different node in the same
  window still renders. No sleeps: drive time with munit-cats-effect + `TestControl` if the
  suite already patterns it, else the topic-subscriber await pattern from the B5 test.
- Tick the TODO2.md item.

### S8. Author-facing authoring guide (docs-only)

- `docs/authoring-guide.md`: one guide teaching usage (the ADRs record decisions; this teaches
  the API): dashboards & slugs, cards + slots (literal vs transform vs `exprOf`, defaults,
  `reactive:false`), JSONata context (`$state`/`$attr`/`$domain`/`$entity_id`), actions
  (serviceTap/toggleTap/navigate/popups), surfaces & tabs, dynamic groups (incl. the new case
  containers + `inArea`/`onFloor`), theming (tokens/styles/chrome + the disconnected-indicator
  contract), page `title`, what is static vs backend-rendered vs client-signal scriptable,
  and a jsonnet-vs-Pkl side-by-side quickstart (deviations from ADR 0006: `openPopup`/
  `openPopupInline`, `cssClass`).
- Written LAST so it documents S1–S7's additions. Link from docs/adr/README.md and the
  CLAUDE.md module section (one line each). Tick the TODO2.md item.

## Verification

- Per step: `sbt -batch 'fh-datastar-view/testFull'` green (78 + new tests); scalafmt.
- Registry refresh touches `ha-api` → also `sbt -batch 'ha-api/testFull; home/compile'` on S4
  (home depends on ha-api).
- **Manual checklist (HA-blocked, run when the server is reachable)** — extends the existing
  pkl-step-7 + B5 checklist already flagged in ADR 0006/memory:
  - S3: kill the server while a browser is connected → indicator appears; restart → clears.
  - S4: rename an entity / add an area in HA → dump refreshes, dashboards re-evaluate.
  - S6: drop a new `.pkl` file into dashboards/ while serving → `/d/<slug>` works, live
    patches flow; colliding slug → logged rejection, server keeps serving.
  - S1: browser tab shows the authored title.
- Memory update at the end (datastar-dashboard-status.md): sprint results + updated checklist.

## DONE: Live verification of the already-committed work (completed 2026-07-04)

All nine steps below passed (results recorded in ADR 0006's Verification section, commits
`da92e0d` + `8cbc2d4`; the visual browser items were user-confirmed the same day). Kept for
the record of what was checked.

The user asked to verify the committed work against the now-accessible live instance
(confirmed: `GET http://192.168.1.174:8123/api/` → 200 with the build.sbt token). This is
**not** the S1–S8 sprint (still deferred) — it is the HA-blocked checklist accumulated by the
pkl-parity sprint (`9858cca`…`c751aba`) and the cleanup/fan-out sprint (`e9c2e8b`…`6137329`),
recorded in ADR 0006's Verification section and memory `datastar-dashboard-status.md`.

Everything runs headless (curl); no code changes. Only gitignored artifacts get written
(dashboard.json, dump.libsonnet, lib/dump.pkl). One small docs commit at the end.

1. **Baseline**: `sbt -batch 'fh-datastar-view/testFull'` → expect 78 green.
2. **Build phase live**: `sbt -batch dashboardBuild` — exercises `prepareDumps` (B4) against
   live HA: DataDump fetch, `dump.libsonnet` + typed `lib/dump.pkl` regeneration, both-language
   evaluation, `dashboard.json` written.
3. **Serve**: `sbt dashboardServe` in background; wait for :8080.
4. **Page checks (curl GET)**: `/`, `/d/pkl-demo`, `/d/pkl-tabs`, `/d/tabs` (+ the floor
   dashboards) → 200; pkl-demo renders REAL live values (compare a grep'd entity state against
   `/api/states`); pkl-tabs has the baked default panel; repeat the pkl-tabs GET with
   `Cookie: fhui_<groupId>=1` → baked panel is tab 1 (ADR 0005 flash-free cookie restore).
5. **SSE + B5 fan-out**: open TWO concurrent `GET /sse/dashboard/dashboard/patch` streams;
   each gets its own first `patch-signals` conn event. Toggle a dashboard-referenced light via
   HA REST (`POST /api/services/light/toggle`) — record its state first, **restore it after**.
   Both streams must receive the same `datastar-patch-elements` fragment; dynamic-group
   fragments (state==on membership) should ride along.
6. **Interactivity over SSE (conn-correlated POSTs)**: using a stream's conn id —
   `POST /sse/surface/open/<registered popup id>` → patch into `popups-body` arrives;
   `POST /sse/popup/close` → swap-to-empty; `POST /sse/navigate/pkl-tabs` → body inner-patch.
   Also one dashboard-route actuation: `POST /sse/action/light/toggle/<id>` (WS call_service
   path), state change flows back on the stream; restore state.
7. **Live reload**: append+revert whitespace on `pkl-demo.pkl` → reload repaint patch arrives;
   sanity-check reload latency with the precise `Analyzer.importGraph` import set (step 6 of
   the pkl sprint; expect a few seconds, not a full-superset re-eval storm).
8. **Record results**: amend ADR 0006's Verification note (pending → verified, with date),
   update memory `datastar-dashboard-status.md` (clear the two PENDING blocks or note
   failures). One docs-only commit on `pkl` (ADR file only; standard trailers).
9. Browser-only leftovers (visual active-tab highlight, dialog appearance) can't be seen from
   curl — the underlying patches/signals are what's verified; note them for the user to eyeball
   at http://localhost:8080.

Anything that FAILS: stop, diagnose, report — do not patch code as part of this task without
flagging it first.

## Status

**Execution deferred by the user (2026-07-04); plan moved into the repo the same day.**
Plan is complete and approved-scope; no sprint code has been written or committed. The live
verification section above is DONE; `pkl` has since been merged to `main` (PR #2), so the
sprint starts from a fresh branch off `main`. When resuming: execute S1→S8 in order, one
Opus subagent + one commit per step, per the ground rules above (line references were
verified pre-merge and should be re-checked against the current tree). The S1 detailed plan
below was produced by a scoping subagent (read-only) and is ready to execute as-is.

## Appendix: S1 detailed plan (from scoping subagent a84f8e51ee38948e9)

Verified findings that refine the S1 step (already reflected above, kept here in full):

- `Configuration.default.withDefaults` (Dashboard.scala:54-57) means `title: Option[String]
  = None` needs no decoder change; circe maps JSON `null` → `None`.
- `Renderer` ctor param `dashboard` is not a `val` but readable from inside the class:
  `def title: Option[String] = dashboard.title` (place near `def stylesheets`, ~:246).
- `Server.pageResponse` (:468-483) holds the renderer and calls `page(slug, body,
  stylesheets)`; `page` (:491-517) hardcodes the title at :508. Add a `title:
  Option[String]` param and a tiny `escapeHtml` helper (`&` first, then `<`, `>`, `"`) —
  Server has none today.
- **No `components.pkl` class edit** — there is no top-level Dashboard class; Pkl entries
  (`pkl-demo.pkl`, `pkl-tabs.pkl`) are free-form modules with bare `cards`/`theme`/`card`/
  `surfaces` properties. Parity = add module property `title = "Pkl demo"` on pkl-demo.pkl;
  jsonnet gets `title: 'Home'` on dashboard.jsonnet. `PklBuild.eval` renders with
  `omitNullProperties` (PklBuild.scala:53), so absent titles are omitted — decode-safe.
- Test seams: page GET renders synchronously (no SSE readiness wait). New tests (+4 → 82):
  ServerSuite *authored title* (`Dashboard(..., title = Some("My Home & Away"))` → body
  contains `<title>My Home &amp; Away</title>`, proving escaping) and *fallback to slug*
  (`GET /d/kitchen` → `<title>kitchen</title>`); BuildPhaseSuite (~:130) asserts
  `d.title == Some("Home")`; PklBuildSuite (~:408) asserts `d.title == Some("Pkl demo")`.
  Existing count confirmed = 78 (Transform 14 + Server 8 + BuildPhase 11 + Renderer 28 +
  PklBuild 17).
- ADR 0002:150-158 describes the `Server.page()` document shell — extend that parenthetical
  to name the per-dashboard `<title>` (one-line in-place edit; no new ADR).
- Commit: subject `fh-datastar-view: per-dashboard page title`; `git add` only the ten
  touched files (Dashboard/Renderer/Server .scala, dashboard.jsonnet, pkl-demo.pkl, the
  three suites, ADR 0002, TODO2.md); never `git add -A` (working tree has off-limits files).
