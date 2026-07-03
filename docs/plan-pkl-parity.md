# Plan: Pkl track → full jsonnet parity

Implements the ADR 0006 skip-list. Each step is sized for Opus 4.8 (medium
thinking): small scope, exact files, its own verification. Steps 1–5 are
**Pkl-only** (new authoring in `dashboards/lib/*.pkl` + demos + tests — the
backend pipeline is already source-agnostic: the `inlineSurfaces`/`@@NODE_ID@@`
hoist and the `Dashboard` decode work on evaluated JSON, so tabs/popups/dynamic
groups need ZERO Scala changes). Step 6 is the only Scala step. Jsonnet track
untouched throughout.

Baseline: branch `pkl`, commit `31f356c` (Pkl MVP; `testFull` = 68 green).

## Contract rules (verified; same as the MVP plan)

- Nodes: `{kind: "component", card, slots, children}`; dynamic groups:
  `{kind: "dynamic", query, cases}` — `kind` is the circe discriminator.
- Slots: bare string = literal (MUST be a string, never a number); object =
  `{entityId?, transform, default?, bypassUnavailable, reactive}`.
- Predicate JSON: `{kind:"cmp", property, op, value}` /
  `{kind:"and"|"or", items}` / `{kind:"not", item}`; `op` ∈
  `eq|ne|lt|lte|gt|gte`; `property` ∈ `"domain"|"state"|"attr:<name>"`.
  Case: `{when, card, slots}` (no `entity_id` slot — renderer injects it;
  `injectedDynamic = {id, entity_id}` so validate accepts it).
- `@@NODE_ID@@` is a literal token the build-phase hoist splices
  (`DashboardBuild.NodeIdToken`); surfaces lift to
  `surfaces["<idBase>_<localKey>"]`; `Surface.hostId` =
  `{bakeInto}_{bakeAs}` else `Dashboard.PopupHostId`.
- Pkl gotchas: `class`/`override` reserved; `const` for module refs from class
  bodies; assign (don't amend) null-defaulted Listings; filter a Mapping via a
  for-generator (`new Mapping { for (k, v in m) { when (k != "x") { [k] = v } } }`).

## Step 1 — Small parity gaps: `exprOf`, `class` slot, slider domains

Files: `dashboards/lib/components.pkl`, `PklBuildSuite.scala`.

- `Expr` gains `entityId: String? = null`; add
  `function exprOf(eo: hass.Entity, s: String): Expr` setting it. Thread
  `entityId` through `labelSlot`/`valueSlot`/`secondarySlot` into the emitted
  `Slot` (mirrors jsonnet `entityOf`, components.libsonnet:102-105 — labelSlot
  merges the whole descriptor; value/secondary copy `entityId` when present).
- Row/Column: optional `hidden cssClass: String? = null` (**`class` is a Pkl
  reserved word** — authoring name differs, slot key stays `"class"`):
  `slots { when (cssClass != null) { ["class"] = cssClass!! } }`. Templates
  already carry the `{{#class}}` sections.
- `sliderSpec`: add `["cover"]` (`cover/set_cover_position`, key `position`,
  1..100 → use 0..100, value `current_position`) and `["fan"]`
  (`fan/set_percentage`, key `percentage`, 0..100, value `percentage`).
- Tests: exprOf slot decodes with `entityId = Some(...)`; a Row with cssClass
  emits the literal `class` slot; a Slider on a cover resolves its spec.

Verify: `sbt 'fh-datastar-view/testOnly *PklBuildSuite'`.

## Step 2 — Dynamic groups (`d.*`, Predicate AST, `$self`)

Files: `lib/hass.pkl`, `lib/components.pkl`, `pkl-demo.pkl`,
`PklBuildSuite.scala`.

- **hass.pkl**: widen `EntityId` (`... || this == "$self"`); add
  `hidden isDynamic: Boolean = entity_id == "$self"` on `Entity`; add
  `class DynamicEntity extends Entity { entity_id = "$self"; domain = "dynamic" }`
  and `SELF: DynamicEntity = new {}` (the `d.matched` counterpart — typed, so
  any leaf card accepts it).
- **components.pkl — layout hierarchy restructure**:
  `abstract class LayoutNode { kind: String }`;
  `abstract class Node extends LayoutNode { kind: "component" = "component"; ... ; children: Listing<LayoutNode> = new {} }`;
  new `class DynamicGroup extends LayoutNode { kind: "dynamic" = "dynamic"; query: Predicate; cases: Listing<Case> }`.
  (Entries' `card: c.Node` annotations still hold; only `children` widens.)
- **Predicate AST** (typed — a misspelled op/property is now a build error):
  `typealias PredicateOp = "eq"|"ne"|"lt"|"lte"|"gt"|"gte"`; abstract
  `Predicate { kind: String }` with `Cmp {kind="cmp"; property; op: PredicateOp; value: Any}`,
  `And`/`Or {items: Listing<Predicate>}`, `Not {item: Predicate}`. Helper
  functions (port components.libsonnet:599-616): `cmp`/`pAnd`/`pOr`/`pNot`
  (**`and`/`or`/`not` may collide with Pkl keywords — check; jsonnet already
  renamed `not`→`pnot`**), `always` = cmp("domain","ne","__never__"),
  `whenDomain`/`whenState`/`whenDeviceClass`/`attrLessThan`/`stateLessThan`/
  `lowBattery(threshold)`.
- **case/when/group** (port :630-659): `class Case { when: Predicate; card: String; slots: Mapping<String, Slot|String> }`;
  `function dynCase(when, node: Node): Case` — slots = the node's slots minus
  `entity_id` via a Mapping for-generator; `function dynWhen(branches: Listing<Case>, fallback: Node?): Listing<Case>`
  (appends `dynCase(always, fallback)`); `function group(query: Predicate, card: Node): DynamicGroup`
  and `function groupCases(query, cases: Listing<Case>): DynamicGroup`.
  (Pkl functions can't dispatch on "node vs when-result" like jsonnet's
  `std.objectHas` — expose the two named forms instead.)
- **labelSlot** gains the `$self` branch (components.libsonnet:78-80): when
  `entity != null && entity.isDynamic && label == null` emit the LIVE default
  `new Slot { transform = "$attr.friendly_name ? $attr.friendly_name : $entity_id"; bypassUnavailable = false }`.
- **Demo**: add to pkl-demo a per-domain dispatch group
  (`group(whenState("on"), dynWhen(...))` light→Slider, fallback→EntityCard
  with toggleTap) + a `lowBattery(20)` single-card group — mirrors
  dashboard.jsonnet:143-172.
- Tests (mirror BuildPhaseSuite's `dynamics` collector): decoded Dashboard has
  2 `LayoutNode.Dynamic`; the dispatch group's cases decode with the right
  cards; case slots contain no `entity_id`; `validate() == Nil`.
- NOTE: a `$self` Slider still needs step 3 — keep the demo's dynamic slider
  case commented or land steps 2+3 together if cleaner.

Verify: `sbt 'fh-datastar-view/testOnly *PklBuildSuite'` + `*BuildPhaseSuite`.

## Step 3 — Slider runtime `$lookup($domain)` config tier

Files: `lib/components.pkl`, `PklBuildSuite.scala`.

Port the spike3 three-tier design (scratchpad `pkl-spike3/components.pkl`, may
not survive reboots — the shape):

- `sliderDomainMap(get: (SliderSpec) -> Any): String` manifests the table as a
  JSONata object literal (`{"light":"light/turn_on",...}`; strings quoted,
  ints bare — lambda field accessors, Pkl has no reflection-by-string);
  `sliderLookup(get)` wraps it in `$lookup(<map>, $domain)`. Both `const`.
- Slider: `hidden entity: hass.Entity(isDynamic || sliderSpec.containsKey(domain))`;
  `hidden spec: SliderSpec? = if (entity.isDynamic) null else sliderSpec[entity.domain]`;
  config slots = override → build-time spec value (stringified) → dynamic:
  `new Slot { transform = sliderLookup(...); reactive = false; bypassUnavailable = false }`
  (jsonnet sliderConfig :237-239); position slot dynamic form =
  `$lookup($attr, <sliderLookup(value)>)` (jsonnet sliderValueLookup :233).
- Tests: static slider output UNCHANGED (regression); `Slider { entity = hass.SELF }`
  emits `$lookup` transforms for action/key/min/max and the `$lookup($attr, ...)`
  position; a Slider on a sensor still fails the constraint.

Verify: `sbt 'fh-datastar-view/testOnly *PklBuildSuite'`.

## Step 4 — Popups / surfaces

Files: `lib/components.pkl`, `lib/theme.pkl`, `pkl-demo.pkl`,
`PklBuildSuite.scala`.

- `class SurfaceDef { content: LayoutNode; bakeInto: String? = null; bakeAs: String? = null; bakeIndex: Int? = null; defaultOpen: Boolean? = null }`
  (nullable Boolean so omitNullProperties drops it — the decoder defaults it).
- `Node` gains `inlineSurfaces: Mapping<String, SurfaceDef>? = null` (dropped
  from JSON when null; the hoist lifts it when present). `Tap` gains
  `inlineSurfaces: Mapping<String, SurfaceDef>? = null`; EntityCard/Button copy
  `tap.inlineSurfaces`/`action.inlineSurfaces` onto the node (jsonnet
  tapInline :186-188).
- `const NODE_ID = "@@NODE_ID@@"` (the hoist token).
- `popup` card: template ported verbatim from components.libsonnet:511
  (`<dialog open class="popup">` + ✕ posting `/sse/popup/close`), slots `[]`;
  `class Popup extends Node { card = "popup" }` + add `["popup"]` to `cards`.
- Taps: `function closePopup(): Tap` → onclick `@post('/sse/popup/close')`;
  `function openPopup(surfaceId: String): Tap` → onclick
  `@post('/sse/surface/open/<id>')`; `function openPopupInline(content: Node): Tap`
  → onclick `@post('/sse/surface/open/\(NODE_ID)_self')` + inlineSurfaces
  `["self"] = new SurfaceDef { content = new Popup { children { content } } }`
  (Pkl can't union-dispatch string|node in one function — two named forms,
  jsonnet :559-571).
- Registered surfaces: entries emit a top-level
  `surfaces: Mapping<String, SurfaceDef>` (pkl-demo: a `detail` popup with a
  closePopup button, mirroring dashboard.jsonnet:42-52 + open buttons for both
  the registered and the inline form).
- theme.pkl `styles`: add the `dialog.popup` + `.popup-close` CSS block
  (theme.libsonnet:75-85).
- Tests: after the FULL pipeline, `d.surfaces` contains the registered id AND
  a hoisted `c_..._self` id; the inline trigger's onclick references the
  spliced id (no `@@NODE_ID@@` left anywhere in the decoded JSON);
  `validate() == Nil`.

Verify: `sbt 'fh-datastar-view/testOnly *PklBuildSuite'`.

## Step 5 — Tabs

Files: `lib/components.pkl`, `lib/theme.pkl`, new `dashboards/pkl-tabs.pkl`,
`PklBuildSuite.scala`.

- Button gains `hidden active: String? = null` → literal `active` slot (its
  template already carries the `{{#active}}` sections).
- `tabs` card template ported from components.libsonnet:447-456 — **minus the
  stray `',` artifact on the `</div>',` line (:455), a latent jsonnet bug; do
  not reproduce it**. Slots `[]`. Add to `cards`.
- `class Tab { label: String; content: LayoutNode }`;
  `class Tabs extends Node { card = "tabs"; hidden tabs: Listing<Tab> }` with
  (per jsonnet build :467-499):
  - `inlineSurfaces` = for each i: `["t\(i)"] = SurfaceDef { content = tabs[i].content; bakeInto = NODE_ID; bakeAs = "panel"; bakeIndex = i; defaultOpen = (i == 0) }`
    — note jsonnet omits `defaultOpen` for i>0 vs emitting `false`: decoder
    default is false, so emitting `defaultOpen = if (i == 0) true else null` keeps parity;
  - `children` = for each i a `Button { label = tabs[i].label; active = "$tab_\(NODE_ID) == \(i)"; action = new Tap { onclick = "@post('/sse/surface/open/\(NODE_ID)_t\(i)'); $tab_\(NODE_ID) = \(i); document.cookie = 'fhui_\(NODE_ID)=\(i);path=/;max-age=31536000;samesite=lax'" } }`.
  - Iterate with index via `for (i, t in tabs)` in listing/mapping generators
    (Pkl's key-value generator over a Listing yields index+element — verify).
- theme.pkl `styles`: add the `.tabs`/`.tabbar`/`.tab`/`.tab.active`/
  `.tab-panel` block (theme.libsonnet:66-73).
- `pkl-tabs.pkl`: mirror tabs.jsonnet — a back-navigate button + 2 tabs
  (entity cards / expr cards), served at `/d/pkl-tabs`.
- Tests (mirror BuildPhaseSuite:161-255): 2 hoisted surfaces sharing
  `hostId`; ids match the `c_*` scheme; `bakeIndex` 0/1; exactly one
  `defaultOpen`; buttons' onclick contains the spliced surface id + cookie
  write; `validate() == Nil`.

Verify: `sbt 'fh-datastar-view/testOnly *PklBuildSuite'` + `*BuildPhaseSuite`.

## Step 6 — Infra (the only Scala step)

Files: `PklBuild.scala`, `BuildApp.scala`, `PklBuildSuite.scala`.

- **Precise import set**: replace the all-`*.pkl` superset with pkl-core's
  import-analysis API (pkl 0.27+; check `org.pkl.core.Evaluator` javadoc for
  the entry point — `evaluateImportGraph`/`pkl:analyze`). Keep the superset as
  the fallback when analysis fails, and keep `+ entry`. Filter the returned
  URIs to `file:` under dashboardsDir. Test: imports for a probe entry contain
  exactly the entry + its imports, NOT an unrelated `.pkl` in the dir.
- **BuildApp**: `DASHBOARD_ENTRY` env (default `"dashboard.jsonnet"`) —
  BuildApp reads env via cats-effect like ServerApp's `pathFromEnv`; a `.pkl`
  entry then produces `dashboard.json` through the same evaluate path.
- Optional (skip if noisy): share one `Evaluator` across the per-reload entry
  batch. NOT across reloads — the evaluator caches parsed modules and would
  serve stale sources.

Verify: `sbt 'fh-datastar-view/testFull'`.

## Step 7 — Live verification + docs

Files: `docs/adr/0006-pkl-authoring-track.md`, `CLAUDE.md`, memory.

- `sbt dashboardServe` against live HA: `/d/pkl-demo` — popup opens (registered
  + inline) and closes; dynamic groups render live members (toggle a light,
  watch membership); dynamic slider on a matched light works. `/d/pkl-tabs` —
  tabs switch, active highlight, cookie restore on reload, navigate back.
  Jsonnet dashboards unaffected. Reload latency sanity check (per-entry eval
  cost with the precise import set).
- ADR 0006: dated `## Update —` section marking each skip-list row done (and
  the import-graph follow-up resolved); note the two-function `openPopup`
  split and the `cssClass` naming (reserved word) as deliberate API deviations
  from jsonnet.
- CLAUDE.md: drop the "MVP subset / deferred" phrasing from the Pkl bullet.
- Commit(s) on the `pkl` branch, one per step or grouped sensibly.

## Risks

1. **The Node→LayoutNode restructure (step 2)** ripples through every
   `children` amend and the demos' `card: c.Node` annotations — run the whole
   suite after, not just the new tests.
2. **Mapping for-generators with `when`** (case slot filtering, tabs
   inlineSurfaces): syntax verified in principle, but exercise it in the first
   test of each step before building on it.
3. **`defaultOpen` false vs absent** (step 5): emit null-not-false so
   omitNullProperties keeps the JSON identical to jsonnet's.
4. **Cookie/signal strings in Pkl interpolation**: the tabs onclick mixes `$`
   (Datastar signal, literal) with `\(...)` (Pkl interpolation) — `$` is
   literal in Pkl strings, only `\(` interpolates; test asserts the exact
   onclick text.
5. **Import-graph API surface** (step 6) is the one externally-unverified
   detail — hence the superset fallback requirement.
