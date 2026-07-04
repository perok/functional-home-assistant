# 0006 — Pkl as a second authoring language (typed dashboards alongside jsonnet)

- **Status:** Accepted; full jsonnet parity implemented
- **Date:** 2026-07-03 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Dashboards are authored in jsonnet: untyped, no editor completion beyond
top-level fields, and sharing a component library means copying `.libsonnet`
files. An investigation (spikes run in-process against this repo's JDK) showed
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
  dep, JDK 17+) evaluates a module in-process; the result feeds the existing
  circe pipeline unchanged.

## Decision

Pkl is **additive**, not a replacement. Jsonnet remains the primary track; the
two coexist in the same dashboards dir and are dispatched by file extension.

1. **`SourceEval` seam** (`fh.view.build.SourceEval`): owns
   `Result(value: Json, imports: Set[os.Path])` and dispatches `.jsonnet` →
   `JsonnetBuild` / `.pkl` → `PklBuild`. Everything downstream
   (`normalizeChildren`, `hoistInlineSurfaces`, decode, validate, Renderer,
   Server) is source-agnostic and unchanged. `literalLocator` lives here and
   greps both languages' sources.
2. **JSON rendering is backend-side.** `PklBuild` evaluates the entry to a
   `PModule` and renders its exports itself (pkl-core's Java
   `ValueRenderers.json` with `omitNullProperties = true`, so absent optional
   fields decode as `None`) — an entry module needs **no**
   `output { renderer = ... }` block; like a jsonnet entry, it just *is* its
   data, and the omit-null semantics the slot decoder relies on are enforced in
   one place.
3. **`lib/` convention**: Pkl library modules (`hass.pkl`, `components.pkl`,
   `theme.pkl`, `tokens.pkl`, generated `dump.pkl`) live in `dashboards/lib/`;
   top-level `*.pkl` files are dashboard entries (with jsonnet the `.libsonnet`
   extension made this distinction; Pkl has one extension, so a directory
   convention replaces it). Discovery is non-recursive, and a slug claimed by
   both a `.jsonnet` and a `.pkl` file is a startup error.
4. **Typed dump**: `PklDump` renders the same transformed `DataDump` JSON that
   becomes `dump.libsonnet` as a typed `lib/dump.pkl` — every
   floor/area/entity a named property typed against the hand-written
   `lib/hass.pkl` schema (entity class picked by domain; `GenericEntity`
   fallback), plus per-domain lists per area. Both dumps are written on every
   fetch (server startup and `BuildApp`); both are gitignored. `hass.pkl` is
   hand-curated per domain (HA's attribute shapes exist only in its developer
   docs, not machine-readably); it types only what the dump extracts.
5. **The output contract is the existing one**: Pkl components emit
   `{kind: "component", card, slots, children}` nodes and a
   `{cards, theme, card, surfaces?}` top level. A slot is a bare string
   (literal) or the `SlotSource` object form. **Literal slots must be JSON
   strings, never numbers** (the slot decoder rejects numbers) — numeric config
   like the slider's `min`/`max` is stringified, exactly as the jsonnet builder
   does.
6. **Cards are classes** ("class-as-builder"): hidden typed properties are the
   authoring surface (`new c.EntityCard { entity = dump.entities.x }`), and the
   class derives the slots.

## Feature coverage (full jsonnet parity)

Implemented on the Pkl track, mirroring the jsonnet semantics (owning ADRs in
parentheses):

- Containers/sectionTitle/entityCard/button/slider; `expr`, and `exprOf`
  multi-entity slots (0001/0004); `cssClass` slot on row/col.
- `serviceTap`/`toggleTap`/`navigate`; popups/surfaces — `SurfaceDef`,
  `inlineSurfaces` on `Node`+`Tap`, the `@@NODE_ID@@` hoist token, `popup`
  card + `Popup` class, `closePopup`/`openPopup(surfaceId)`/
  `openPopupInline(body)`, popup CSS in `theme.pkl` (0002).
- Tabs: `Tabs`/`Tab` classes (per-tab inline surfaces with
  `bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`), `Button.active`,
  cookie-writing onclick, tabs CSS (0002/0005).
- Dynamic groups: typed `Predicate` AST (`Cmp`/`And`/`Or`/`Not`; the
  `PredicateOp` union type makes a misspelled op a build error — an
  improvement over jsonnet), the predicate helpers, `dynCase`/`dynWhen`/
  `group`/`groupCases`, `hass.SELF` (`$self`) accepted by any leaf card, live
  `friendly_name ? : entity_id` label default (0003).
- Slider three-tier config: author override → build-time spec (static entity)
  → runtime `$lookup($domain)` over the manifested domain map (dynamic `$self`
  entity); one typed `sliderSpec` table (incl. cover/fan rows) is the single
  source for both tiers.

**Deliberate API deviations from jsonnet** (Pkl has no untyped
union-dispatch): `openPopup(id: String)` and `openPopupInline(body: Node)` are
two named functions where jsonnet's `openPopup` dispatches on string-vs-node;
the row/col class slot is authored `cssClass` (`class` is a Pkl reserved word)
though the emitted slot key remains `"class"`.

## Recorded tradeoffs / follow-ups

- **Import watching is precise**: `PklBuild` computes the entry's transitive
  import set via pkl-core's static analyzer (`Analyzer.importGraph`),
  file-scheme-filtered under the dashboards dir, with the conservative
  all-`*.pkl` superset as fallback on any analysis failure.
- **A fresh `Evaluator.preconfigured()` per eval** (~0.5 s cold, per entry per
  reload). Fine at current scale; reuse an evaluator (or restrict re-eval to
  affected entries) if reload latency grows with the dump.
- `BuildApp` reads `DASHBOARD_ENTRY` (default `dashboard.jsonnet`) to build a
  Pkl artifact.
- Generated-code safety in `PklDump`: every identifier backticked, strings
  escaped (backslash first also neutralizes `\(` interpolation), null
  `friendly_name` omitted, floor slugs guarded against the module's own
  `entities`/`areas` names.

## Pkl authoring gotchas (spike- and implementation-verified)

- `override` and `class` are reserved words (hence `cssClass`).
- Module properties referenced from class bodies must be `const`
  (`const local` for helpers).
- Amending a null-defaulted `Listing` is an error — assign `= new Listing {…}`.
- A doc comment in an entry (no `module` clause) attaches to the first import,
  which Pkl rejects — use plain `//` comments in entries.
- Alias an import whose name collides with a property being defined
  (`tokens = tokens.light` self-recurses).
- A bare identifier inside a `new {}` amend binds to the object's OWN member,
  not the enclosing function parameter — a same-named parameter self-recurses
  to a `StackOverflowError` (rename the parameter).
- Lambdas are invoked via `.apply(x)`; `Listing` → `List` via `.toList()`;
  `for (i, t in listing)` yields index+element.

## Verification

`fh-datastar-view/testFull` green (PklBuildSuite carries full-pipeline
decode/hoist/validate mirrors of the jsonnet BuildPhaseSuite tests). **Live
browser verification of `/d/pkl-demo` (popups, dynamic groups, dynamic slider)
and `/d/pkl-tabs` (switching, highlight, cookie restore) is still pending** —
the HA instance was unreachable at implementation time; run the
`dashboardServe` checklist in `docs/plan-pkl-parity.md` step 7 when it is back.
