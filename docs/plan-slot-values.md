# Plan: a slot's value is something read, and the steps run on it

Branch `slot-values`, stacked on `slot-views` (#657). Discussed in chat on 2026-11-01.

**State:** design in discussion (O1–O5 open); no code. Steps are written once those settle.

## Why

`SlotSource` is one flat case class holding several concepts:

- **What the value is read from**: a `literal`, an `entityId`, or a `fetch`, each with fields only
  it uses. `bypassUnavailable` and `reads` are the entity's; a literal still carries a `transform`
  nothing reads, so every consumer tests `literal` first.
- **How it is transformed**: `transform` is a CEL string, a Simple op or a fetch's `Stage`, exactly
  one, so a value cannot be computed and then shaped (a glyph's `Match`, then the theme's swap).
- **Its CEL's named inputs** (`values`, `ExprValue`): a second, smaller model of the same thing:
  typed literals, a count, a condition, a fetch.
- A gate (`valueWhileSlot`) and a fallback (`default`).

And `SlotType.CssClass` spells the theme's class swap as a type (#657's P3), while a literal's swap
and a reading's run in different places.

## The model

```scala
enum Value:                          // one value, read from somewhere
  case Literal(v: Lit)               // typed: Text | Whole | Real | Flag
  case Entity(id: Option[EntityId], part: Part, reads: Reads, bypassUnavailable: Boolean)
  case Fetch(fetch: FetchTemplate)

enum Transform:                      // one step; a ValueExpr runs a list of them, in order
  case Cel(src: String, inputs: Map[String, ValueExpr])   // the previous step is `value`
  case Simple(op: SimpleOp)          // the fast tier, over the previous step
  case Stage(s: Builtin)             // a step the renderer provides: Chart, ThemeClasses

final case class ValueExpr(value: Option[Value], steps: List[Transform], default: Option[String])
```

- **Typed in order.** Each value and step has a type: a literal its own, an entity part what the
  dump says it is, a fetch a series, CEL what its compiler infers, a Simple op and a stage fixed
  ones. Bind checks the list in order; the slot's `type` is what the last step must produce. Pkl
  has no user generics, so there the type stays a field.
- **Named inputs are `ValueExpr`s**, which replaces `ExprValue`. `valueWhile` is an
  `Option[ValueExpr]` that must produce a bool.
- **Compile.** The node plan's `Planned` is `Constant(value: SlotValue) | Live(…)`. A literal
  whose steps are all pure (a stage, a CEL over constants) folds once per renderer into a
  `Constant`, typed by the slot's type; anything else is `Live`, woken by its entity, the entities
  its steps name and its `valueWhile`'s.
- **Absorbs #657's P3 and P5:** `SlotType` is `text | bool` with `slotMod.bool(name)`; the class
  swap is the `ThemeClasses` stage that `slotMod.cssClass` adds; a literal is its own case.

## Open

- **O1, decided: Simple splits.** `State` and `Attr(name)` create a value, so they become
  `Value.Entity`'s `part`, and each op's own `entity` goes with them. The rest (`Prefix`,
  `Suffix`, `Percent`, `Fill`, `Duration`, `Match`) become steps over the incoming value, and an
  op's other operands are `Value`s, so a suffix is a literal or another part alike.
  `SuffixUnit` and `AttrUnit` become one step, `WithUnit(unit: Value)`: the value, a space and the
  unit, unless the unit is absent. ADR 0028 pins each Simple to its CEL spelling; a split op is
  pinned against CEL over `value`.
- **O2. Conditions and counts are operations.** The set or entity they read is a `Value`; the
  comparison, `where`, `count` and `any` are steps; `and`/`or` name their operands as inputs. Today
  all of it is one `Predicate` AST (ADRs 0003, 0028), used by `c.iff`, candidate sets and slots
  alike: how far to split it here is open.
- **O3. `reads` stays the entity's.** It says when a value is read again: `live` on a change,
  `onRender` on every render, `once` per entity. A fetch has no such choice today: it is asked
  whenever its node renders and wakes nothing (ADR 0034), so the field moves to `Fetch` only when
  one needs `once`.
- **O4. Stages are the renderer's own steps**, as opposed to an author's CEL or a Simple op. With
  steps a list, anything producing a series can feed `Chart`.
- **O5. Names and shape.** `Value`, `Transform`, `ValueExpr`; whether **Reading** stays the term
  for a `ValueExpr` over live state; the wire and the Pkl authoring shape. Dashboard authors
  keep `c.attr`, `c.expr` and `c.isOn`.

## Not in this plan

- A `number` type with slot-views point 4 (a `{{x}}` inside a Datastar expression must be a
  number, a bool or `x.jsValue`): it waits on the tap call design below.
- The tap call design round: a declared call id in place of the URL a template spells out.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`.
RenderBench and `validate` timing before and after; snapshots regenerated with
`dashboardSnapshotsUpdate` and read before each commit.
