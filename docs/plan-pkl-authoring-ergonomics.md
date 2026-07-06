# Pkl authoring ergonomics: pipe-based card sugar + dynamic-group redesign (no more `hass.SELF`)

## Context

Two readability complaints about the Pkl authoring track (`modules/fh-datastar-view/src/main/resources/dashboards/`):

1. **Leaf cards are verbose**: every card is `new c.EntityCard { entity = dump.entities.x; ... }` — `new` + class name + braces even for the trivial case. The user asked whether "extending data with the pipe operator" could read better.
2. **Dynamic groups are noisy and leak the self trick**: `c.groupCases(q, c.dynWhen(new Listing { c.dynCase(p, new c.Slider { entity = hass.SELF }) }, fallbackNode))` — deeply nested function calls, explicit `new Listing`, and authors must know the `hass.SELF` placeholder-entity convention.

Constraint that makes this cheap: Pkl is the way forward (jsonnet divergence explicitly OK), and the **wire format must not change** — the backend (`Dashboard`/`Renderer`) never sees `$self` at all (`dynCase` strips the `entity_id` slot; slots without `entityId` inherit the matched entity at render time). So this is a pure authoring-surface refactor: emitted JSON for both entries stays byte-identical.

**Verified feasible on pkl-core 0.31.1 (our pin)**:
- `|>` pipe operator exists (`PipeNode.class` in the 0.31.1 jar; documented in the language reference: `x |> f` ≡ `f.apply(x)`, chains well).
- stdlib `typealias Mixin<Type> = (Type) -> Type` (base.pkl:2367) — a mixin is built with **object syntax** (`new Mixin<EntityCard> { tap = toggleTap }`) and applied with `|>`.
- **Function amending**: `(someFn) { prop = v }` produces a new function that applies `someFn` then amends the result — so a card *factory* can be amended with card options inline.

## Design

### A. Pipeable card factories (additive sugar; classes stay)

Add function-valued module properties to `lib/components.pkl` for the entity-first leaves, plus tiny call-style helpers for the text leaves:

```pkl
/// `dump.entities.x |> entityCard`, options via function amending:
/// `dump.entities.x |> (entityCard) { tap = toggleTap; label = "Office" }`
entityCard: (hass.Entity) -> EntityCard = (e) -> new EntityCard { entity = e }
slider: (hass.Entity) -> Slider = (e) -> new Slider { entity = e }

/// `x |> entityCard |> tappable` — the common toggle-on-tap mixin.
tappable: Mixin<EntityCard> = new { tap = toggleTap }

function title(t: String): SectionTitle = new { text = t }
function button(l: String, a: Tap): Button = new { label = l; action = a }
```

Authoring before/after (pkl-demo.pkl):

```pkl
// before
new c.EntityCard {
  entity = dump.entities.sensor_ams_1a4e_u1
  value = c.expr(#"$round($number($state), 1) & " V""#)
  tap = c.serviceTap("homeassistant/toggle")
}
// after
dump.entities.sensor_ams_1a4e_u1 |> (c.entityCard) {
  value = c.expr(#"$round($number($state), 1) & " V""#)
  tap = c.serviceTap("homeassistant/toggle")
}
// and the trivial case collapses to one line:
dump.entities.`sensor_ams_1a4e_q` |> c.entityCard
```

`new c.X { ... }` remains fully supported (it's the same classes underneath — the reflect-derived `cards` registry is untouched). Containers (`Column`/`Row`/`Tabs`/`Popup`) keep the `new` + `children {}` style — that's already idiomatic Pkl and pipes don't improve it.

### B. Dynamic groups: class-as-builder + render functions (SELF internalized)

Replace the `group`/`groupCases`/`dynWhen`/`dynCase` function-nesting API with an amendable `DynamicGroup` whose cases are **derived** from author-facing hidden props. The key move: a case's card is authored as a **render function `(hass.Entity) -> Node`** — and the Part-A factories *are already such functions*, so the common cases need no lambda at all:

```pkl
class Branch {
  matches: Predicate
  render: (hass.Entity) -> Node
}

class DynamicGroup extends LayoutNode {
  kind: "dynamic" = "dynamic"
  query: Predicate
  hidden branches: Listing<Branch> = new {}
  /// The card for entities no branch matched (the only card when there are no branches).
  hidden render: ((hass.Entity) -> Node)? = null
  cases: Listing<Case>(!isEmpty) = new Listing {
    for (b in branches) { caseOf(b.matches, b.render) }
    when (render != null) { caseOf(always, render!!) }
  }
}
```

`caseOf` is the current `dynCase` made `const local`, applying the render function to `hass.SELF` internally (`caseOf(p, r) = <dynCase body over r.apply(hass.SELF)>`). Authors never see the sentinel; the emitted `Case` JSON (`when`/`card`/`slots`, entity_id stripped) is unchanged. `hass.SELF`/`DynamicEntity` stay in hass.pkl (internals + `labelSlot`'s `isDynamic` live-label default depend on them) but drop out of the documented authoring surface.

Entry before/after (pkl-demo.pkl):

```pkl
// before
c.groupCases(c.whenState("on"), c.dynWhen(new Listing {
  c.dynCase(c.whenDomain("light"), new c.Slider { entity = hass.SELF })
}, new c.EntityCard {
  entity = hass.SELF
  tap = c.toggleTap
  label = c.expr(#"$attr.friendly_name & " (" & $state & ")""#)
}))
// after
new c.DynamicGroup {
  query = c.stateIs("on")
  branches {
    new c.Branch { matches = c.domainIs("light"); render = c.slider }
  }
  render = (c.entityCard) {
    tap = c.toggleTap
    label = c.expr(#"$attr.friendly_name & " (" & $state & ")""#)
  }
}

// single-card group:
new c.DynamicGroup {
  query = c.lowBattery(20)
  render = (c.entityCard) {
    label = c.expr(#"$attr.friendly_name & " — " & $state & "%""#)
    secondary = "device_class"
  }
}
```

Delete the old `group`/`groupCases`/`dynWhen`/`dynCase` functions (pre-v1, Pkl-only surface, our two entries are the only users; jsonnet keeps its own API untouched).

### C. Fluent predicates

Combinators become methods on `Predicate` (methods never render, no `hidden` needed):

```pkl
abstract class Predicate {
  kind: String
  function and(other: Predicate): PAnd = let (l = this) new PAnd { items { l; other } }
  function or(other: Predicate): POr = let (l = this) new POr { items { l; other } }
  function not(): PNot = let (l = this) new PNot { item = l }
}
```

(`let (l = this)` because a bare `this` inside the `new {}` body would bind to the new object.) Leaf helpers renamed for reading position — `whenDomain`→`domainIs`, `whenState`→`stateIs`, `whenDeviceClass`→`deviceClassIs`, `stateLessThan`→`stateBelow`, `attrLessThan`→`attrBelow`; `always` and `lowBattery(n)` keep their names, `lowBattery` reimplemented as `deviceClassIs("battery").and(stateBelow(threshold))`. `pAnd`/`pOr`/`pNot` (Listing forms) are deleted — the methods replace them. Emitted Predicate AST JSON unchanged.

## Step 0 — syntax spike (first, in scratchpad)

One throwaway `.pkl` + the existing `Spike.java` runner pattern to confirm on 0.31.1 before touching real files:
1. `x |> f` where `f` is a lambda-valued property accessed through an import alias (`c.entityCard`).
2. Function amending of that imported property: `x |> (c.entityCard) { label = "y" }` — and that amending a *typed* result late-binds hidden props (slots recompute with the new `tap`).
3. `new Mixin<T> { ... }` + `|>` preserves the value's class (`is EntityCard` after pipe).
4. Method names `and`/`or`/`not` are legal identifiers (the current code comments claim they "collide with Pkl operators" — the 0.31 token list says otherwise, but verify; fallback names: `andAlso`/`orElse`/`negate`).
5. `branches { new Branch {...} }` Listing-amend inside `new DynamicGroup {}`, and `cases` deriving from hidden props via `const local` helper referencing `hass.SELF`.

Any spike failure downgrades only the affected sugar (e.g. keep `pAnd` if `and` is somehow reserved); the overall shape survives.

## Files to change

- `modules/fh-datastar-view/src/main/resources/dashboards/lib/components.pkl` — Parts A–C: factories + `tappable` + `title`/`button`; `Branch` + reworked `DynamicGroup`; Predicate methods + helper renames; delete `group`/`groupCases`/`dynWhen`/`dynCase`/`pAnd`/`pOr`/`pNot` (keep `Case` class and internal `caseOf`).
- `modules/fh-datastar-view/src/main/resources/dashboards/pkl-demo.pkl` — rewrite in the new style (pipes for the three static leaves, both dynamic groups as above, `c.title(...)`, `c.button(...)`).
- `modules/fh-datastar-view/src/main/resources/dashboards/pkl-tabs.pkl` — pipe forms for the entity cards inside tabs; `c.title`/`c.button`.
- `modules/fh-datastar-view/src/test/scala/fh/view/build/PklBuildSuite.scala` — update dynamic-group / predicate-helper snippets to the new API; add one equivalence test: `x |> (c.entityCard) { tap = c.toggleTap }` emits node JSON identical to the `new c.EntityCard {...}` form.
- `docs/adr/0006-pkl-authoring-track.md` — rewrite Decision 6 (classes + pipeable factories/function-amend options), add a decision for the dynamic authoring model (render functions, SELF internal-only, old function API removed — a deliberate jsonnet divergence), extend the gotchas list (`|>`/`Mixin`/function-amend availability on 0.31.1; the `let (l = this)` binding note).
- `lib/hass.pkl` — doc-comment update only on `SELF`/`DynamicEntity` ("internal to components.pkl; authors use render functions").

## Verification

1. **Byte identity**: render `pkl-demo` and `pkl-tabs` to JSON before starting (BuildApp with `DASHBOARD_ENTRY`, or the existing scratchpad `Render.java`), rewrite, render again, `python3 -m json.tool --sort-keys` + `diff` → must be empty for both.
2. `sbt 'fh-datastar-view/testFull'` — all suites green (78 tests + the new equivalence test).
3. `sbt scalafmt` for the touched Scala test file.
4. Optional live check: `sbt dashboardServe`, `/d/pkl-demo` renders and the dynamic groups still repopulate on state changes.

## Out of scope

- jsonnet track: untouched (keeps its own dynamic API and hand-written `cards`).
- Containers/Tabs/Popup authoring style, surfaces registration shape: unchanged.
- Backend Scala: zero changes (wire format identical).
