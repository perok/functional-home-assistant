# 0006 — Pkl as the dashboard authoring language (typed dashboards)

- **Status:** Accepted; Pkl is the only evaluated authoring language.
- **Date:** 2026-07-03 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Dashboards were originally authored in jsonnet: untyped, no editor completion
beyond top-level fields, and sharing a component library meant copying
`.libsonnet` files. An investigation (spikes run in-process against this repo's
JDK) showed [Pkl](https://pkl-lang.org) offers what jsonnet structurally cannot:

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

Pkl is **the** authoring language — the only one the backend evaluates. The
jsonnet sources (`components.libsonnet`, the `*.jsonnet` entries) remain on disk
only as **inert porting references** while the five real dashboards are ported
to Pkl by hand; the backend can no longer evaluate them and they are not
extended. Once the hand-port completes they are deleted.

1. **`SourceEval` seam** (`fh.view.build.SourceEval`): owns
   `Result(value: Json, imports: Set[os.Path])` and evaluates `.pkl` entries via
   `PklBuild` (a non-`.pkl` entry is an error). The seam is kept as a thin named
   boundary; everything downstream (`hoistInlineSurfaces`, decode, validate,
   Renderer, Server) is source-agnostic and unchanged. `literalLocator` lives
   here and greps the Pkl sources.
2. **JSON rendering is backend-side.** `PklBuild` evaluates the entry to a
   `PModule` and renders its exports itself (pkl-core's Java
   `ValueRenderers.json` with `omitNullProperties = true`, so absent optional
   fields decode as `None`) — an entry module needs **no**
   `output { renderer = ... }` block; it just *is* its data, and the omit-null
   semantics the slot decoder relies on are enforced in one place.
3. **`lib/` convention**: Pkl library modules (`hass.pkl`, `components.pkl`,
   the `theme.pkl` contract + its `theme-beer.pkl` implementation (the
   default and only shipped theme), `tokens.pkl`, the entry scaffold `entry.pkl`, and generated
   `dump.pkl`) live in `dashboards/lib/`; top-level `*.pkl` files are dashboard
   entries. A directory convention separates the two (Pkl has one file
   extension). Discovery (`ServerApp.discoverEntries`) scans `*.pkl` only and is
   non-recursive; the slug is the filename sans `.pkl`.
4. **Typed dump**: `PklDump` renders the transformed `DataDump` JSON as a typed
   `lib/dump.pkl` — every floor/area/entity a named property typed against the
   hand-written `lib/hass.pkl` schema (entity class picked by domain;
   `GenericEntity` fallback), plus per-domain lists per area. It is written on
   every fetch (server startup and `BuildApp`) and is gitignored. `hass.pkl` is
   hand-curated per domain (HA's attribute shapes exist only in its developer
   docs, not machine-readably); it types only what the dump extracts.
5. **The output contract is the existing one**: Pkl components emit
   `{kind: "component", card, slots, children}` nodes and a
   `{cards, theme, card, surfaces?}` top level. A slot is a bare string
   (literal) or the `SlotSource` object form. **Literal slots must be JSON
   strings, never numbers** (the slot decoder rejects numbers) — numeric config
   like the slider's `min`/`max` is stringified.
6. **Cards are classes with call-style factories.** The class is the
   ("class-as-builder") core: hidden typed properties are the authoring surface
   and the class derives the slots; `new c.EntityCard { entity = ... }` stays
   fully supported. On top of the classes, entity-first **factory methods** make
   the common case read as a call — `c.entityCard(e)`, `c.slider(e)` — with
   options applied by a **parenthesized amend** of the call result:
   `(c.entityCard(e)) { tap = ...; label = ... }` (the outer parens are
   mandatory; the parens-free form is a parse error). Each option is **also a
   fluent builder method** on the card class, so options can instead chain
   paren-free — `c.entityCard(e).tap(...).label(...)`: the method amends `this`
   and returns the same class, and because slots are late-bound off the hidden
   props the emitted node is **byte-identical** to the amend/`new` forms
   (spike-verified on pkl-core 0.31.1; guarded by the builder-vs-amend identity
   test + the wire snapshots). Methods and properties are separate namespaces, so
   `function tap(t)` coexists with the `hidden tap` prop; the builder covers the
   parameterized case `|>` mixins cannot (a mixin takes no arguments). `|>` is
   reserved for
   **additions** — a mixin like `tappable` chains on the end
   (`c.entityCard(x) |> c.tappable`) — never construction (methods aren't
   first-class, so there is deliberately no `x |> c.entityCard`). Text leaves get
   call helpers too: `c.title("…")`, `c.button(label, tap)`. Render positions in
   dynamic groups need a bare factory *value*, so each entity-first factory
   exists twice across Pkl's separate method/function namespaces: a
   `function entityCard(e)` AND a pure delegate
   `hidden entityCard: (hass.Entity) -> EntityCard = (e) -> entityCard(e)` — call
   syntax picks the method, a bare `c.entityCard` the value, no logic duplicated.
   Containers (`Row`/`Column`/`Tabs`/`Popup`) keep the `new` + `children {}`
   style.
7. **A card class also owns its template**: each concrete `Node` subclass
   carries a `hidden cardDef: CardDef` (Mustache template + declared slots),
   co-located with the logic that fills them, and the module's `cards` mapping
   is **derived by reflection** (`pkl:reflect` over the module's concrete
   `Node` subclasses, reading the class-level `card`/`cardDef` defaults via
   `reflect.Property.defaultValue`). Because `cardDef` is hidden it never
   emits into node JSON — the emitted top-level `cards` is identical to the
   old hand-maintained mapping, so the backend contract is untouched.
   Registration is automatic: a new card is one class, and forgetting the
   `cardDef` is an eval-time error naming the class. Entries do not repeat the
   `cards = c.cards` line — they `amends "lib/entry.pkl"`, the base scaffold
   that sets it (decision 9).
8. **Dynamic groups: Mapping branches + render lambdas.** A dynamic group is an
   amendable `DynamicGroup` (extends `LayoutNode`, `kind = "dynamic"`) whose
   branches are a **`Mapping<Predicate, (hass.Entity) -> Node>`** — one line per
   branch, `[predicate] = renderFn`. Author order is preserved (= first-match
   dispatch order), a structurally-equal duplicate predicate key is a build error
   naming the line, and a branch is replaceable by key when amending a base group
   (a Listing amend is append-only). An optional `render` fallback covers
   entities no branch matched (and is the only card when `branches` is empty).
   Each render fn is a **function of the matched entity** —
   `(e) -> (c.entityCard(e)) { … }`, or a bare factory value `c.slider` — so the
   author writes exactly where the entity flows. `hass.SELF`/`DynamicEntity` are
   now **internal only**: the derived `cases` listing feeds each branch through
   `const local caseOf`, which applies the lambda to `hass.SELF` and strips the
   build-time `entity_id` slot (the renderer injects the matched entity per
   match); the emitted `Case` (`when`/`card`/`slots`) JSON is byte-identical to
   before. This **replaced an earlier `group`/`groupCases`/`dynWhen`/`dynCase`
   function-nesting API** (removed pre-v1). Predicate combinators became **fluent methods** on
   `Predicate` (`domainIs("light").and(stateIs("on"))`, `.or(…)`, `.not()`), and
   the leaf helpers read in position — `domainIs`/`stateIs`/`deviceClassIs`/
   `stateBelow`/`attrBelow` (+ `always`, `lowBattery(n)`); the old
   `pAnd`/`pOr`/`pNot` Listing forms were deleted in favor of the methods.
   Rejected while designing this: `entity = SELF` as a class default (a forgotten
   entity silently emits `$self`, needing a backend guard); `caseOf`
   amend-injecting the entity (breaks the moment a branch mixes per-entity and
   static children); `Dyn*` subclasses (collide in the reflect-derived `cards`
   registry); explicit `dynSlider()` factories (just `SELF` renamed). Render
   lambdas won because a branch is a function of the matched entity and composes
   unchanged if cases ever grow from leaves to subtrees.
9. **Entries `amends "lib/entry.pkl"`.** `lib/entry.pkl` is the base module
   every entry amends: it carries the reflected card registry (`cards = c.cards`)
   and the shared `theme`, and declares the fields an entry fills — a required
   `card: c.Node` (the layout-tree root), and optional `title`/`surfaces` (and a
   `theme` override). So an entry opens with `amends "lib/entry.pkl"` and sets
   only `card`. Because `card` has no default, an entry that forgets it fails
   with "Tried to read property `card` but its value is undefined" whose caret
   points at `lib/entry.pkl` (Pkl reports a missing required property at the
   base definition, not the author's line — the module's own doc comment says
   so). `title` feeds the per-dashboard `<title>` (Server falls back to the slug
   when it is null; `omitNullProperties` drops it from the wire JSON when unset).

## Feature coverage

Implemented on the Pkl authoring surface (owning ADRs in parentheses):

- Containers (grid/row/column)/sectionTitle/entityCard/button/slider; `expr`,
  and `exprOf` multi-entity slots (0001/0004); `cssClass` slot on
  grid/row/col; the layout-cell builders on the `LayoutNode` base —
  `columns(n)`/`fullWidth()`/`centered()`/`cellClass` appending to the
  node-level `cell.classes` (the `fh-` layout contract; model + rationale in
  ADR 0007).
- `serviceTap`/`toggleTap`/`navigate`; popups/surfaces — `SurfaceDef`,
  `inlineSurfaces` on `Node`+`Tap`, the `@@NODE_ID@@` hoist token, `popup`
  card + `Popup` class, `closePopup`/`openPopup(surfaceId)`/
  `openPopupInline(body)`, popup CSS in the theme modules (0002). A registered
  popup surface amends into existence via `entry.pkl`'s `surfaces` mapping
  default (`PopupSurface`: hidden `body` Listing, auto-`Popup`-wrapped
  content) — no `new`, and the wrap rule is enforced by construction.
- Tabs: the `Tabs` class, keyed `tabs: Mapping<String, Listing<LayoutNode>>`
  (label → that tab's cards, Row-wrapped into per-tab inline surfaces with
  `bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`; the Listing-valued mapping
  default lets a tab body list cards with no `new`/`children`). The bar is
  BeerCSS-native markup: a `TabButton` card per tab (`.tabs > a` anchors,
  `data-class` active + cookie-writing onclick) — internal to `Tabs`, not an
  authoring surface (0002/0005).
- Comma-free container authoring: hidden amendable base instances `(c.row)`,
  `(c.column)`, `(c.popup)`, `(c.tabs)` — parens mandatory (Pkl requires them
  around any amend parent that isn't a `new` expression).
- Dynamic groups: typed `Predicate` AST (`Cmp`/`And`/`Or`/`Not`; the
  `PredicateOp` union type makes a misspelled op a build error), fluent
  predicate methods + leaf helpers, and the
  `DynamicGroup` Mapping-branch + render-lambda authoring model (decision 8);
  live `friendly_name ? : entity_id` label default (0003).
- Slider three-tier config: author override → build-time spec (static entity)
  → runtime `$lookup($domain)` over the manifested domain map (dynamic `$self`
  entity); one typed `sliderSpec` table (incl. cover/fan rows) is the single
  source for both tiers.

**Deliberate API shape** (Pkl has no untyped union-dispatch):
`openPopup(id: String)` and `openPopupInline(body: Node)` are two named
functions rather than one that dispatches on string-vs-node; the row/col class
slot is authored `cssClass` (`class` is a Pkl reserved word) though the emitted
slot key remains `"class"`.

## Recorded tradeoffs / follow-ups

- **Import watching is precise**: `PklBuild` computes the entry's transitive
  import set via pkl-core's static analyzer (`Analyzer.importGraph`),
  file-scheme-filtered under the dashboards dir, with the conservative
  all-`*.pkl` superset as fallback on any analysis failure.
- **A fresh `Evaluator.preconfigured()` per eval** (~0.5 s cold, per entry per
  reload). Fine at current scale; reuse an evaluator (or restrict re-eval to
  affected entries) if reload latency grows with the dump.
- `BuildApp` reads `DASHBOARD_ENTRY` (default `dashboard.pkl`) to build a
  Pkl artifact — the default errors until the `dashboard.pkl` port lands.
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
- `reflect.Property.defaultValue` returns the **evaluated** class-level
  default (spike-verified on pkl-core 0.31.1, incl. hidden properties and
  amended-object defaults) — so a reflected default like `cardDef` must be
  self-contained (an instance-property reference has no instance to resolve
  against). The reflect stdlib needs Paguro at runtime; it is a declared
  pkl-core dependency, so nothing extra to ship.
- Amending a **method-call result** requires outer parens —
  `(c.entityCard(e)) { … }`; the parens-free `c.entityCard(e) { … }` is a parse
  error (Pkl's own message suggests the parenthesized form). `|>` binds looser
  than call/amend, so a mixin chains after construction, including after an amend.
- **Methods aren't first-class values.** To pass a factory *as a value* (a
  `Mapping` branch's render fn) you need either an explicit `.apply` at the call
  site or the dual-name **method + function-value delegate** pattern
  (`function slider(e)` alongside `hidden slider = (e) -> slider(e)` — methods
  and properties are separate namespaces, so no recursion and no duplicated
  logic).
- **Function-valued module properties can't be exported** — a rendered module
  errors on them; mark them `hidden`. Harmless for `components.pkl`, a library
  that is imported, never rendered.
- A fluent method returning `new SomeClass { … this … }` needs `let (l = this)`
  first: a bare `this` inside the `new {}` body rebinds to the freshly-built
  object, not the receiver. The same guard applies to a **self-amending builder
  method** (`function tap(t) = let (self = this) (self) { tap = t }`): capture the
  receiver before the amend body, and name the parameter differently from the
  property it sets (a same-named param self-references in the amend).
- **`const` is transitive**: a `const` property (or any reference from a class
  body) may only call `const` functions — so helpers reached that way are
  `const`/`const local` all the way down (why `cmp`, and thus `always`, are
  `const`). `and`/`or`/`not` are legal method names — the operators are
  `&&`/`||`/`!`, so there is no collision.

## Verification

`fh-datastar-view/testFull` green (PklBuildSuite carries the full-pipeline
decode/hoist/validate coverage plus wire-format snapshots of the demo entries —
byte-identity checked, regenerated with `FH_UPDATE_SNAPSHOTS=1`). Live
verification against the running HA instance completed 2026-07-04 (headless,
over the SSE protocol): `/d/pkl-demo` renders real live values (state +
brightness-seeded slider signals match `/api/states`), the registered `detail`
popup and the inline popup both open (`#popups` inner-patch with the popup
chrome) and close (swap-to-empty), action POSTs actuate via WS `call_service`
and the state change flows back as dynamic-group re-renders; `/d/pkl-tabs`
bakes the default panel, and a `fhui_<id>` cookie on the first-paint GET bakes
the selected tab flash-free (ADR 0005); in-place navigate inner-patches
`#dashboard` and resets `#popups`; hot reload of an edited `.pkl` entry
repaints in ~0.5s (precise `Analyzer.importGraph` watch set). The visual-only
details (active-tab highlight styling,
dialog appearance) were confirmed by eye in the browser the same day — nothing
remains unverified.
