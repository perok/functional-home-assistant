# Plan — conditions on every boolean slot, and live CSS custom properties

**Goal:** an author can say "disable this button while no light on the floor is on", "tint it while
any is", "add class X while Y" and "set `--fh-fill` from this value" with the same small set of
words, on any node, and the build checks it.

```pkl
local lit = q.from(hass.lights(floor.all)).where(q.eq(q.stateProp, "on"))

c.button(floor.floor_name, c.tap.lights.off(floor))
  .expressionValues(new Mapping { ["on"] = lit.count(); ["total"] = hass.lights(floor.all).length })
  .secondary(c.expr(#"string(on) + ' / ' + string(total) + ' on'"#))
  .active(lit.any())
  .disabled(lit.none())
  .classWhen("fh-many", c.expr("on > 2"))
  .cssProperty("--fh-fill", c.expr("string(on * 100 / total) + '%'"))
```

## Where it starts

The runtime already does most of it. `LayoutNode.foldNode` turns a node's live cell classes into
slots BEFORE it attaches expression values, so a cell class or a `disabled` slot whose CEL names
`lights_on` is woken by the tally and patched as one signal. Measured on 2026-10-06 with a probe on
a button: `active` = `lights_on > 0` and `disabled` = `lights_on == 0` rendered right, and the last
light going off sent one `datastar-patch-signals` frame that flipped both (`disabled` ORed with the
tap's own refusal, as `attrWhenEither` intends). What is missing is the Pkl spelling, which today is
`new slotMod.Slot { transform = "lights_on > 0" }`, plus three things below.

## Design

### One machinery: a slot reads values, a node names them

A **slot** is where a result lands (text, a class, an attribute, a style property). An **expression
value** is a named input. They are not alternatives: internally a slot already carries the values
its CEL reads (`SlotSource.values`), and `expressionValues` is only the node-level declaration that
`foldNode` distributes onto the slots that name it. Everything here is built on that one path. Nothing
gains a second evaluator.

### 1. A condition can be an expression value

`ExpressionValue` gains the `c.iff` condition type (`pred.Predicate`, folded as `c.iff` folds a
`q.Cond`). On the wire it is a new `ExprValue.Holds(p)` of CEL type `bool`, resolved by
`Conditions.matchesIn` (the interpreter `c.iff` uses). Its entities join the node's `liveEntities`
through `Predicate.referencedEntities`, as a tally's do. It is subject-free, like a state condition.

```pkl
expressionValues { ["any_on"] = lit.any() }
secondary = c.expr("any_on ? 'Some on' : 'All off'")
```

### 2. Every boolean slot takes a condition directly

`active`, `disabled` and `classWhen` take `Boolean | reading | condition | Expr`:

- a **reading** as today (`c.isOn(l)`, `c.stateIn(l, …)`, the fast tier);
- a **condition** (`lit.any()`, `lit.none()`, `lit.count().gt(2)`, `q.entity(l).stateIs("on")`).
  This is Pkl sugar over 1: it declares a value named after the input (`__active`,
  `__disabled`, `__class_<n>`) and a slot whose CEL is that name. No new runtime path.
- an **`Expr`** (`c.expr("on > 2")`) for arithmetic over named values.

Names starting `__` become reserved, so an author's value cannot collide with a generated one.

### 3. The build checks a boolean slot gets a bool

Nothing checks a slot's result type today. `classWhen("x", c.expr("on"))` builds, and then an int
decides a class. A **boolean slot** must get a bool. Which slots are boolean cannot be read off the
binding kind: `disabled` is a `handler` slot (ORed into `data-attr:disabled`, where a string
`"false"` is truthy), but so is a lock's service name, and `attr:value` carries the slider's
number. So the CARD names its boolean slots (`CardDef.booleanSlots`; the button's is
`disabled`), and a live cell class or a `class:` binding is one by kind. Checked per tier:

- **CEL** compiles to `bool`. `dyn` is still accepted, because an entity read such as `state` is
  `dyn` and refusing it would refuse `state == 'on'`'s neighbours.
- **`Simple`** is checked exactly, since every shape's result type is known at build time. A
  `match` whose cases are all `Boolean` (what `c.isOn`/`c.stateIn` build) is a bool. Every other
  shape (`state`, `attr`, `percent`, `fill`, …) is a string and is refused.

`cssProperty` (4) is the converse: it must produce a string, so a boolean `match` is refused there.

### 4. A live CSS custom property on any node

`.cssProperty(name, reading)` sets `name` on the node's `.fh-cell` from a reading. It is
`classWhen`'s value-carrying twin: one cell slot (a `cell.style:` key beside today's
`cell.class:`), the value inline in the document form and a `data-style:<name>` binding after the
wrapper's seed in both forms (the ordering `cellBindingsInto` already holds for classes). Only
custom properties (`--…`): a plain property on the cell would fight the theme, whose framework
writes `!important` utilities.

### Every input takes every reading form, the fast tier included

The binding kind and the tier are independent, so each input here takes whichever form fits:

| | `Simple` (fast tier) | CEL | condition |
|---|---|---|---|
| `active` / `disabled` / `classWhen` | a boolean `match`: `c.isOn(l)`, `c.stateIn(l, …)` | `c.expr("on > 2")` | `lit.any()` |
| `cssProperty` | `simpleMod.fill("brightness", 0, 255)`, `percent`, `attr`, … | `c.expr("string(on * 100 / total) + '%'")` | — (not a string) |
| `secondary` and other text | as today | as today | through a named value (1) |

Only a reading of NAMED VALUES is CEL-only. `Simple` is a static, total per-entity lookup by
definition (ADR 0028), and a tally is neither. A `Simple` shape over values (a percent of two counts)
needs its own totality rule (`total = 0`), and waits until something wants it.

**The slider keeps its own binding.** Its fill is the same mechanism (a `style:--_end` signal
slot), but on the `.slider` element, and it has to stay there. BeerCSS declares `--_end:0%` on
`.slider` itself, so a value inherited from the cell is shadowed. `beer.min.js` also rewrites
`.slider`'s `style.cssText` on every move (verified in the pinned bundle). An author CAN feed a
slider's own CSS through `cssProperty` for anything BeerCSS does not own, such as a colour the
slider's CSS reads.

## Rejected

- **A condition as a slot transform FORM** (beside CEL and `Simple`). No names, but then a
  condition is evaluated two ways: as a transform, and as a value inside CEL (1). One path is the
  rule.
- **Show/hide through a class.** Hiding is structure, which is `c.iff`'s job, rather than markup left
  in the page.
- **"When it changes, do X" triggers.** That is an automation and belongs in HA.

## Steps

1. Commit this plan, opening the PR.
2. `ExprValue.Holds` + `ExpressionValue` accepting a condition; `ExpressionValuesSuite`: a condition
   value read in text, a flip sending one frame.
3. The result-type check for boolean slots, both tiers (build refusals: a CEL int, a `Simple`
   string shape). `cssProperty`'s converse check lands with it in 6.
4. The sugar on `active`/`disabled`/`classWhen`, and the `__` reservation. Tests are the floor
   button above, a condition shared by two nodes sharing one signal, and a collision refusal.
5. `disabled` on the base tile and the base slider, ORed with the tap's own refusal as the
   button's is (`attrWhenEither`). The tile gets `fh-disabled` and no tap. The slider's input is
   disabled and its commit refuses. The HA layer passes it through (thin subclasses).
6. `cssProperty`: renderer cell binding, validation (`--` only; it must be live; a boolean
   `match` refused), a
   `LiveCellClassSmokeSuite`-style smoke test that a patch moves the property.
7. Docs: ADR 0034 rewritten (conditions as values, boolean slots), ADR 0015 (`disabled` on
   every base control), terminology (**reading** widened past "one entity", **condition**, **live
   cell property**), the arch doc for the cell bindings, the module CLAUDE.md. Delete this plan.

## Decided

- The name is `cssProperty`. `cssVar` is out because `terminology.md` already warns that "vars"
  means three things here.
- The tile and the slider get `disabled` now, not when a dashboard first needs it.
