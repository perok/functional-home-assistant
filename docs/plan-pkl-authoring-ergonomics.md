# Pkl authoring ergonomics: call-style card factories + render-lambda dynamic groups (no author-facing `hass.SELF`)

**Status: implemented** — landed on this branch; ADR 0006 records the decisions.

## Context

Two readability complaints about the Pkl authoring track (`modules/fh-datastar-view/src/main/resources/dashboards/`):

1. **Leaf cards are verbose**: every card is `new c.EntityCard { entity = dump.entities.x; ... }` — `new` + class name + braces even for the trivial case. Cards should read **entity-first as a call** — `(c.entityCard(dump.entities.x)) { ...options... }` — with `|>` reserved for *additions* (mixins), not construction.
2. **Dynamic groups are noisy and leak the self trick**: `c.groupCases(q, c.dynWhen(new Listing { c.dynCase(p, new c.Slider { entity = hass.SELF }) }, fallbackNode))` — deeply nested function calls, explicit `new Listing`, and authors must know the `hass.SELF` placeholder-entity convention.

Constraint that makes this cheap: Pkl is the way forward (jsonnet divergence explicitly OK), and the **wire format must not change** — the backend (`Dashboard`/`Renderer`) never sees `$self` at all (`dynCase` strips the `entity_id` slot; slots without `entityId` inherit the matched entity at render time). So this is a pure authoring-surface refactor: emitted JSON for both entries stays byte-identical, backend Scala untouched.

Design alternatives considered and rejected for the dynamic API (session 2026-07-06):
- **`entity = hass.SELF` as class default** — a static card that forgets `entity` silently emits `entity_id = "$self"`; needs a backend guard to catch.
- **`caseOf` amend-injecting `entity = hass.SELF`** (spiked working, incl. through a wider static type) — assumes a branch card is a single entity-bearing leaf; breaks the moment a branch is a `Row`/`Column` mixing per-entity and static children, where only the author can decide which children receive the matched entity.
- **`Dyn*` subclasses** — collide in the reflect-derived `cards` registry (duplicate card name).
- **explicit `c.dynSlider()` factories** — just `SELF` wearing a different name.

Render lambdas won: a dynamic branch is a **function of the matched entity**, `(e) -> ...`, so the author writes exactly where the entity flows — and it composes unchanged if cases ever grow from leaves to subtrees.

## Spike results (all confirmed on pkl-core 0.31.1, our pin, 2026-07-06)

Scratchpad mini-modules mirroring the card classes (hidden props, derived `slots` Mapping with `when` blocks):

- `(c.entityCard(e)) { label = "y"; tap = t }` — amending a parenthesized **method-call result** through an import alias works; the amended value keeps its class (`is EntityCard`), and hidden derived props **late-bind** (setting `tap` in the amend makes the `onclick` slot appear).
- The outer parens are **mandatory**: `c.entityCard(e) { ... }` is a parse error (Pkl's own error message suggests the parenthesized form).
- `c.entityCard(x) |> c.tappable` — pipe binds looser than call/amend, so mixins chain after construction, including after an amend. Mixin-*returning* methods work as pipe stages (`card |> c.stuff(50)`).
- **Methods and properties are separate namespaces**: a module can define `function slider(e)` AND a same-named function-valued property, with the property a pure delegate `(e) -> slider(e)` (the parens pick the method namespace — no recursion, no logic duplication). Call sites are unambiguous by form: `c.slider(x)` is the method, bare `c.slider` the value.
- **Mapping with object keys** preserves author order (first-match semantics survive), and two structurally-equal predicate keys in one body are a build error naming the exact line ("Duplicate definition of member …").
- Function-typed class properties (`(hass.Entity) -> Node`) work, and a derived `cases` listing can `.apply` them to `hass.SELF` — re-deriving slots against it (live label default etc.).
- `and`/`or`/`not` are legal **method** names (the old comment claiming they collide with Pkl operators is wrong — the operators are `&&`/`||`/`!`); the `let (l = this)` binding trick works.
- Function-valued *module properties* cannot be **exported** — irrelevant for `components.pkl` (a library, imported not rendered), but mark them `hidden` for hygiene.

## Design

### A. Call-style card factories (methods; `|>` only for additions)

Add to `lib/components.pkl` for the entity-first leaves (`entityCard`, `slider`): a factory **method**, plus a same-named **function value** (pure delegate) for render positions in dynamic groups; call-style helpers for the text leaves; mixins for common additions:

```pkl
/// Static: c.entityCard(dump.entities.x); options via parenthesized amend:
/// (c.entityCard(dump.entities.x)) { tap = toggleTap; label = "Office" }
function entityCard(e: hass.Entity): EntityCard = new EntityCard { entity = e }
/// The same constructor as a value, for DynamicGroup render positions:
/// branches { [domainIs("light")] = c.entityCard }
hidden entityCard: (hass.Entity) -> EntityCard = (e) -> entityCard(e)

function slider(e: hass.Entity): Slider = new Slider { entity = e }
hidden slider: (hass.Entity) -> Slider = (e) -> slider(e)

/// c.entityCard(x) |> c.tappable — the common toggle-on-tap addition.
tappable: Mixin<EntityCard> = new { tap = toggleTap }

function title(t: String): SectionTitle = new { text = t }
function button(l: String, a: Tap): Button = new { label = l; action = a }
```

(Factory parameter names must not shadow the properties they set — `e`, not `entity` — per the amend-scoping gotcha.)

Authoring before/after (pkl-demo.pkl):

```pkl
// before
new c.EntityCard {
  entity = dump.entities.sensor_ams_1a4e_u1
  value = c.expr(#"$round($number($state), 1) & " V""#)
  tap = c.serviceTap("homeassistant/toggle")
}
// after — entity-first call, options amended onto the result
(c.entityCard(dump.entities.sensor_ams_1a4e_u1)) {
  value = c.expr(#"$round($number($state), 1) & " V""#)
  tap = c.serviceTap("homeassistant/toggle")
}
// the trivial case collapses to a plain call:
c.entityCard(dump.entities.`sensor_ams_1a4e_q`)
// additions pipe on the end:
c.entityCard(dump.entities.switch_office) |> c.tappable
```

Methods are not first-class values, so there is deliberately no `x |> c.entityCard` construction form — construction is a call, `|>` is for additions. `new c.X { ... }` remains fully supported (same classes underneath — the reflect-derived `cards` registry is untouched). Containers (`Column`/`Row`/`Tabs`/`Popup`) keep the `new` + `children {}` style.

### B. Dynamic groups: Mapping branches + render lambdas (`SELF` internal-only)

Replace the `group`/`groupCases`/`dynWhen`/`dynCase` function-nesting API with an amendable `DynamicGroup`. Branches are a **`Mapping<Predicate, render fn>`** — one line per branch, author order preserved (= first-match dispatch order), duplicate predicates a build error, and individual branches replaceable by key when amending a base group (a Listing amend is append-only):

```pkl
class DynamicGroup extends LayoutNode {
  kind: "dynamic" = "dynamic"
  query: Predicate
  hidden branches: Mapping<Predicate, (hass.Entity) -> Node> = new {}
  /// The card for entities no branch matched (the only card when there are no branches).
  hidden render: ((hass.Entity) -> Node)? = null
  cases: Listing<Case>(!isEmpty) = new Listing {
    for (p, r in branches) { caseOf(p, r) }
    when (render != null) { caseOf(always, render!!) }
  }
}
```

`caseOf` is the current `dynCase` made `const local`, applying the render function to `hass.SELF` internally (`caseOf(p, r) = <dynCase body over r.apply(hass.SELF)>`). Authors never see the sentinel; a branch's `(e) -> ...` receives "the matched entity" semantically, and the emitted `Case` JSON (`when`/`card`/`slots`, entity_id stripped) is unchanged. Cases stay **leaf-only** today (`caseOf` drops children, as `dynCase` does) — but the lambda shape already distributes the entity explicitly (`(e) -> new c.Row { children { c.entityCard(e); c.title("static") } }`), so it needs no redesign if the wire format ever grows composite cases. `hass.SELF`/`DynamicEntity` stay in hass.pkl (internals + `labelSlot`'s `isDynamic` live-label default depend on them) but drop out of the documented authoring surface. Card classes keep `entity` **required** — no default; a forgotten entity on a static card fails the build exactly as today (Pkl's lazy "value is undefined" error).

Note: `always` and `caseOf` are referenced from the `DynamicGroup` class body, so they must be `const` / `const local` (the existing hass.pkl gotcha).

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
    [c.domainIs("light")] = c.slider
  }
  render = (e) -> (c.entityCard(e)) {
    tap = c.toggleTap
    label = c.expr(#"$attr.friendly_name & " (" & $state & ")""#)
  }
}

// single-card group:
new c.DynamicGroup {
  query = c.lowBattery(20)
  render = (e) -> (c.entityCard(e)) {
    label = c.expr(#"$attr.friendly_name & " — " & $state & "%""#)
    secondary = "device_class"
  }
}
```

Delete the old `group`/`groupCases`/`dynWhen`/`dynCase` functions (pre-v1, Pkl-only surface, our two entries are the only users; jsonnet keeps its own API untouched).

### C. Fluent predicates

Combinators become methods on `Predicate` (methods never render, no `hidden` needed) — spiked working, including the names:

```pkl
abstract class Predicate {
  kind: String
  function and(other: Predicate): PAnd = let (l = this) new PAnd { items { l; other } }
  function or(other: Predicate): POr = let (l = this) new POr { items { l; other } }
  function not(): PNot = let (l = this) new PNot { item = l }
}
```

(`let (l = this)` because a bare `this` inside the `new {}` body would bind to the new object.) Leaf helpers renamed for reading position — `whenDomain`→`domainIs`, `whenState`→`stateIs`, `whenDeviceClass`→`deviceClassIs`, `stateLessThan`→`stateBelow`, `attrLessThan`→`attrBelow`; `always` and `lowBattery(n)` keep their names, `lowBattery` reimplemented as `deviceClassIs("battery").and(stateBelow(threshold))`. `pAnd`/`pOr`/`pNot` (Listing forms) are deleted — the methods replace them. Also fix the stale comment claiming `and`/`or`/`not` collide with Pkl operators. Emitted Predicate AST JSON unchanged.

## Files to change

- `modules/fh-datastar-view/src/main/resources/dashboards/lib/components.pkl` — Parts A–C: factory methods + dual-name render values + `tappable` + `title`/`button`; reworked `DynamicGroup` (Mapping branches, `render`, `const local caseOf`, `const always`); Predicate methods + helper renames; delete `group`/`groupCases`/`dynWhen`/`dynCase`/`pAnd`/`pOr`/`pNot` (keep `Case`).
- `modules/fh-datastar-view/src/main/resources/dashboards/pkl-demo.pkl` — rewrite in the new style (call-amend for the three static leaves, both dynamic groups as above, `c.title(...)`, `c.button(...)`).
- `modules/fh-datastar-view/src/main/resources/dashboards/pkl-tabs.pkl` — call-amend forms for the entity cards inside tabs; `c.title`/`c.button`.
- `modules/fh-datastar-view/src/test/scala/fh/view/build/PklBuildSuite.scala` — update dynamic-group / predicate-helper snippets to the new API; add one equivalence test: `(c.entityCard(x)) { tap = c.toggleTap }` emits node JSON identical to the `new c.EntityCard {...}` form.
- `docs/adr/0006-pkl-authoring-track.md` — rewrite Decision 6 (classes + call-style factories, parenthesized amend for options, `|>` for mixins, the dual-namespace delegate); add a decision for the dynamic authoring model (Mapping branches + render lambdas, `SELF` internal-only, old function API removed — a deliberate jsonnet divergence); extend the gotchas list (mandatory parens around an amended call; function values need `.apply`/dual names; function-valued module props aren't exportable; the `let (l = this)` binding note).
- `lib/hass.pkl` — doc-comment update only on `SELF`/`DynamicEntity` ("internal to components.pkl's `caseOf`; authors write render lambdas").

## Verification

1. **Byte identity**: render `pkl-demo` and `pkl-tabs` to JSON before starting (BuildApp with `DASHBOARD_ENTRY`, or the existing scratchpad `Render.java`), rewrite, render again, `python3 -m json.tool --sort-keys` + `diff` → must be empty for both.
2. `sbt 'fh-datastar-view/testFull'` — all suites green (78 tests + the new equivalence test).
3. `sbt scalafmt` for the touched Scala test file.
4. Optional live check: `sbt dashboardServe`, `/d/pkl-demo` renders and the dynamic groups still repopulate on state changes.

## Out of scope

- jsonnet track: untouched (keeps its own dynamic API and hand-written `cards`).
- Containers/Tabs/Popup authoring style, surfaces registration shape: unchanged.
- Backend Scala: zero changes (wire format identical).
