# 0006 — Pkl as a second authoring language (typed dashboards alongside jsonnet)

Date: 2026-07-03. Status: **Accepted** (MVP shipped).

## Context

Dashboards are authored in jsonnet: untyped, no editor completion beyond
top-level fields (a limitation `components.libsonnet` itself laments), and
sharing a component library means copying `.libsonnet` files. An investigation
(spikes run in-process against this repo's JDK) showed
[Pkl](https://pkl-lang.org) offers what jsonnet structurally cannot:

- **compile-time checking** — typed card classes, constrained types
  (`hass.Entity(sliderSpec.containsKey(domain))` makes "slider on a sensor" a
  build error whose carets point at both the contract and the offending
  dashboard line);
- **editor support** — official LSP, VS Code and IntelliJ plugins with
  completion/diagnostics over classes AND over a *generated typed dump*
  (`dump.entities.<key>` dot-completes as a `LightEntity`);
- **a real package system** — versioned packages via `package://` imports, the
  sharing story for the component library;
- **a tiny integration surface** — `org.pkl-lang:pkl-core` (3.3 MB, plain Java
  dep, JDK 17+) evaluates a module to JSON text that feeds the existing circe
  pipeline unchanged.

## Decision

Pkl is **additive**, not a replacement. Jsonnet remains the primary track; the
two coexist in the same dashboards dir and are dispatched by file extension.

1. **`SourceEval` seam** (`fh.view.build.SourceEval`): owns
   `Result(value: Json, imports: Set[os.Path])` and dispatches `.jsonnet` →
   `JsonnetBuild` / `.pkl` → `PklBuild`. Everything downstream
   (`normalizeChildren`, `hoistInlineSurfaces`, decode, validate, Renderer,
   Server) is source-agnostic and unchanged. `literalLocator` moved here and
   greps both languages' sources.
2. **`lib/` convention**: Pkl library modules (`hass.pkl`, `components.pkl`,
   `theme.pkl`, `tokens.pkl`, generated `dump.pkl`) live in `dashboards/lib/`;
   top-level `*.pkl` files are dashboard entries (with jsonnet the
   `.libsonnet` extension made this distinction; Pkl has one extension, so a
   directory convention replaces it). Discovery is non-recursive, and a slug
   claimed by both a `.jsonnet` and a `.pkl` file is a startup error.
3. **Typed dump**: `PklDump` renders the same transformed [`DataDump`] JSON
   that becomes `dump.libsonnet` as a typed `lib/dump.pkl` — every
   floor/area/entity a named property typed against the hand-written
   `lib/hass.pkl` schema (entity class picked by domain; `GenericEntity`
   fallback), plus per-domain lists per area for iteration. Both dumps are
   written on every fetch (server startup and `BuildApp`); both are
   gitignored. The `hass.pkl` schema is hand-curated per domain (HA's
   attribute shapes exist only in its developer docs, not machine-readably);
   it types only what the dump template actually extracts.
4. **The output contract is the existing one**: Pkl components emit
   `{kind: "component", card, slots, children}` nodes and a
   `{cards, theme, card}` top level. A slot is a bare string (literal) or the
   `SlotSource` object form. **Literal slots must be JSON strings, never
   numbers** (the slot decoder rejects numbers) — numeric config like the
   slider's `min`/`max` is stringified, exactly as the jsonnet builder does
   (`'' + override`).
5. **Cards are classes** ("class-as-builder"): hidden typed properties are the
   authoring surface (`new c.EntityCard { entity = dump.entities.x }`), and
   the class derives the slots. Generic cards keep their jsonnet semantics —
   the `sliderSpec` domain table survives as a typed
   `Mapping<String, SliderSpec>` — but a **static** entity resolves its spec
   at BUILD time from `entity.domain` (jsonnet resolves via runtime JSONata
   `$lookup($domain)`, which is only *required* for dynamic groups, deferred
   below).

## Deliberately skipped (the MVP → parity roadmap)

The pipeline already tolerates every absence: the hoist is idempotent on
marker-free input, `Dynamic` nodes simply don't appear, optional slots stay
absent. Each row names the ADR that owns the feature's design.

| Deferred from the Pkl track | Where it lives today | Owning ADR |
|---|---|---|
| tabs + cookie bake tier (`bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`) | components.libsonnet `tabs` | 0005 |
| popups/surfaces: `openPopup`/`closePopup`/`popup` card, `inlineSurfaces` + `@@NODE_ID@@` hoist | components.libsonnet; `DashboardBuild.hoistInlineSurfaces` | 0002 |
| dynamic groups: `d.group`/`d.when`/`d.case`, Predicate builders, `$self` sentinel | components.libsonnet `dynamic` | 0003 |
| `exprOf` multi-entity slots (`Slot.entityId` exists in the output class, never set) | components.libsonnet `exprOf` | 0001/0004 |
| runtime `$lookup($domain)` slider config (needed only for dynamic/mixed groups) | components.libsonnet `sliderSpec` lookups | 0001 update |
| `class` slot on row/col; `active` (tab) slot on button (templates keep the sections) | components.libsonnet containers/button | — |
| cover/fan/… slider domains (add typed rows to `sliderSpec`) | — | — |
| tabs/popup CSS in the Pkl theme (`theme.pkl` carries only what its component set emits) | theme.libsonnet | — |

## Recorded tradeoffs / follow-ups

- **Import watching is a conservative superset**: `PklBuild` reports every
  `*.pkl` under the dashboards dir as imports, not the entry's precise
  transitive set. Behaviorally identical today (the watcher re-evaluates all
  entries on any change); the precise pkl 0.27+ `pkl:analyze`/importGraph API
  is the follow-up.
- **A fresh `Evaluator.preconfigured()` per eval** (~0.5 s cold, per entry per
  reload). Fine at current scale; reuse an evaluator (or restrict re-eval to
  affected entries) if reload latency grows with the dump.
- **`BuildApp` stays hardcoded to `dashboard.jsonnet`**; a `DASHBOARD_ENTRY`
  env var is the obvious extension when a Pkl artifact build is wanted.
- Pkl authoring gotchas (spike-verified, honored in `lib/`): `override` is a
  reserved word; module properties referenced from class bodies must be
  `const` (`const local` for helpers); amending a null-defaulted `Listing` is
  an error — assign `= new Listing {...}`; a doc comment in an entry (no
  `module` clause) attaches to the first import, which Pkl rejects; alias an
  import whose name collides with a property being defined
  (`tokens = tokens.light` self-recurses).
- Generated-code safety in `PklDump`: every identifier backticked, strings
  escaped (backslash first also neutralizes `\(` interpolation), null
  `friendly_name` omitted, floor slugs guarded against the module's own
  `entities`/`areas`/`output` names.

## Update — 2026-07-03: parity reached (skip-list implemented)

`docs/plan-pkl-parity.md` executed on branch `pkl` (steps 1–6, one commit
each). **Every row of the skip-list above is now implemented**, and two of the
"Recorded tradeoffs / follow-ups" are resolved:

- `exprOf` (`Expr.entityId` threaded through label/value/secondary slots),
  `class` slot on row/col (authored as `cssClass` — `class` is a Pkl reserved
  word), cover/fan `sliderSpec` rows.
- Dynamic groups: `LayoutNode`/`Node`/`DynamicGroup` hierarchy, typed
  `Predicate` AST (`Cmp`/`And`/`Or`/`Not`, `PredicateOp` union type — a
  misspelled op is a build error, an improvement over jsonnet), predicate
  helpers (`cmp`/`pAnd`/`pOr`/`pNot`/`always`/`whenDomain`/`whenState`/
  `whenDeviceClass`/`attrLessThan`/`stateLessThan`/`lowBattery`),
  `dynCase`/`dynWhen`/`group`/`groupCases`, `hass.SELF` (`DynamicEntity`,
  `$self`) accepted by any leaf card, live `friendly_name ? : entity_id` label
  default.
- Slider three-tier config: author override → build-time spec (static entity)
  → runtime `$lookup($domain)` over the manifested domain map (dynamic
  `$self` entity) — the Pkl `sliderSpec` table is the single source for both
  tiers (`sliderDomainMap` manifests it as a JSONata object literal).
- Popups/surfaces: `SurfaceDef`, `inlineSurfaces` on `Node`+`Tap`,
  `const NODE_ID = "@@NODE_ID@@"` hoist token, `popup` card + `Popup` class,
  `closePopup`/`openPopup(surfaceId)`/`openPopupInline(body)`, popup CSS in
  `theme.pkl`.
- Tabs: `Tabs`/`Tab` classes (per-tab inline surfaces with
  `bakeInto`/`bakeAs`/`bakeIndex`, `defaultOpen` emitted null-not-false so
  omit-null keeps JSON parity), `Button.active`, cookie-writing onclick, tabs
  CSS, `pkl-tabs.pkl` entry. The ported `tabs` card template drops the stray
  `',` artifact present in components.libsonnet (a latent jsonnet bug, not
  reproduced).
- **Follow-ups resolved**: import watching now uses the precise transitive
  import graph via `org.pkl.core.Analyzer.importGraph` (the guessed
  `evaluateImportGraph` method does not exist), file-scheme-filtered under the
  dashboards dir, with the all-`*.pkl` superset kept as fallback; `BuildApp`
  reads `DASHBOARD_ENTRY` (default `dashboard.jsonnet`).

**Deliberate API deviations from jsonnet** (Pkl has no untyped
union-dispatch): `openPopup(id: String)` and `openPopupInline(body: Node)` are
two named functions where jsonnet's `openPopup` dispatches on string-vs-node;
the row/col class slot is authored `cssClass` (reserved word) though the
emitted slot key remains `"class"`.

New Pkl gotchas learned (beyond the list above): a bare identifier inside a
`new {}` amend binds to the object's OWN member, not the enclosing function
parameter — a same-named parameter self-recurses to a `StackOverflowError`
(rename the parameter); lambdas are invoked via `.apply(x)`; `Listing` →
`List` via `.toList()`; `for (i, t in listing)` generators yield
index+element.

Verified by `fh-datastar-view/testFull` = 76 green (PklBuildSuite 17 —
full-pipeline decode/hoist/validate mirrors of the jsonnet BuildPhaseSuite
tests). **Live browser verification of `/d/pkl-demo` (popups, dynamic groups,
dynamic slider) and `/d/pkl-tabs` (switching, highlight, cookie restore) is
still pending** — the HA instance was unreachable at implementation time; run
the plan's step-7 `dashboardServe` checklist when it is back.
