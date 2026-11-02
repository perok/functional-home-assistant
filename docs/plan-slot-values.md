# Plan: a slot's value is a source, and the steps run on it

Branch `slot-values`, stacked on `slot-views` (#657); PR #658. Discussed in chat on 2026-11-01.

**State:** design agreed for this PR (part A); part B designed for the next stacked PR. S1–S3
built.

## Why

`SlotSource` is one flat case class holding several concepts:

- **What the value is read from**: a `literal`, an `entityId`, or a `fetch`, each with fields only
  it uses. `bypassUnavailable` and `reads` are the entity's; a literal still carries a `transform`
  nothing reads, so every consumer tests `literal` first.
- **How it is transformed**: exactly one `transform` (a CEL string, a Simple op, a fetch's `Stage`),
  so a value cannot be computed and then shaped (a glyph's `Match`, then the theme's swap). Simple
  ops each name their own `entity` besides the slot's `entityId`.
- **Its CEL's named inputs** (`values`, `ExprValue`): a second, smaller model of the same thing.
- Which entity a reading reads when it names none is resolved in Scala, per node plan, by
  inheriting the card's subject.

And the theme's class swap is a type (`SlotType.CssClass`), while the binding kind is a parsed
string (`"style:--fh-fill"`) and so is an entity's property in a condition (`"attr:x"`).

## The model (part A, this PR)

```scala
final case class TemplateSlot(
    expr: ValueExpr,
    valueWhile: Option[ValueExpr] = None,   // must produce Bool
    signal: Option[SignalBind] = None,
    `type`: SlotType = SlotType.Text        // what `expr` must produce
)

final case class ValueExpr(
    source: Option[Value],                  // None: a CEL that reads no entity
    steps: List[Step] = Nil,                // run in order, each typed
    default: Option[Lit] = None             // when the result is empty; typed by the slot's type
)

enum SlotType:                              // what a literal is, and what a slot may produce
  case Text, Bool, Number                   // a slot declaring Number waits for slot-views point 4

enum Lit:
  case Text(v: String)
  case Bool(v: Boolean)
  case Whole(v: Long)                       // Whole and Real are both Number:
  case Real(v: Double)                      // CEL's compiler types them apart

enum Value:
  case Literal(v: Lit)                      // a bare JSON string, number or bool
  case Entity(of: Option[EntityId] = None,  // None: the entity this is about (the card's subject)
              part: Part = Part.State,
              reads: Reads = Reads.Live, bypassUnavailable: Boolean = true)
  case Fetch(fetch: FetchTemplate)          // a series
  case Present(tally: Predicate.Tally)      // the candidates present, a list
  case Condition(p: Predicate)              // decides itself, over the entities it names

// in Predicate, so a condition's own `count()` and a Present share one shape
final case class Tally(candidates: ListMap[EntityId, Option[Predicate]])

enum Part:
  case State
  case Attr(name: String)

sealed trait Step
final case class Cel(src: String, inputs: Map[String, ValueExpr] = Map.empty) extends Step

sealed trait Format extends Step            // one value in, one out
final case class Prefix(text: Value) extends Format
final case class Suffix(text: Value) extends Format
final case class WithUnit(unit: Value) extends Format       // SuffixUnit and AttrUnit, merged
final case class Percent(min: Value, max: Value) extends Format
final case class Fill(min: Value, max: Value) extends Format
final case class Duration(scale: Double) extends Format
final case class Match(cases: Map[String, Lit], otherwise: Lit) extends Format

case object Count extends Step              // the size of a Present list

sealed trait Stage extends Step             // the renderer's own steps
final case class Chart(style: ChartStyle) extends Stage
case object ThemeClasses extends Stage

enum SignalBind:                            // an object on the wire, no string to parse
  case Text, Bind, Handler
  case Style(property: String)
  case Attr(name: String)
  case Class(name: String)
```

On the wire every enum and sealed trait is `{"kind": "<Case>", …fields}`; a bare JSON primitive is
a literal `ValueExpr`. The node's `values` become `Map[String, ValueExpr]`, and a `Cell`'s classes
and styles hold `ValueExpr`s.

- **Typed in order.** A literal has its own type, `State` is text, an `Attr` is dynamic, a fetch
  is a series, `Present` a list of ids (CEL gains a list kind for it, `size(lights)`), `Count` a
  whole number, CEL what its compiler infers, a Format step and a stage fixed ones. Bind checks the list in order; the last step must produce
  the slot's `type`. The binding checks it too, instead of implying it: `Class` needs a bool,
  `Text` and `Style` text, `Attr` text or a bool, `Handler` anything.
- **CEL's running value is `value`**, reserved, so no input may take the name (none does today).
  When the source is an entity, CEL also keeps its `state`, `attr` and `entity_id`, so today's
  expressions keep working. With one input, that input is the source:
  `Present(lights) → Count → Cel("string(value) + ' on'")`.
- **The subject is named once per node.** The card's own `subject` (`String?`, which an HA card
  sets from its `entity`) is the literal `entity_id` slot; the template shows it and the server
  reads it as the node's subject. A reading of it is `Entity(of = None)`, so the wire does not
  repeat the id per reading (S2 had Pkl copy it, 1.7–2.7% of each wire at fixture id lengths).
  `Component.bound` resolves `None` to the subject, in the one place values and placement are
  already bound, so a bound slot always holds a concrete id and no consumer inherits; anything
  keyed by content (signals, the once-cache, programs) keys from the bound form. An
  `Entity(of = None)` on a card with no subject is a `validate` error naming the slot, in place of
  S2's Pkl throw. Two `None`s, two meanings, one level apart and never confused: `source = None`
  reads no entity, `Entity(of = None)` reads the subject.
- **A CEL with no source** (where the card has no subject, or the library knows it reads none: the
  click transforms, which read only `dashboard_slug`) compiles in an environment without `state`,
  `attr`, `entity_id` and `domain`, so reading one is CEL's own "undeclared reference" error at
  build. Where the card has a subject, a CEL's source is `Entity()`, since it may read `state`.
- **A `valueWhile` naming no entity reads the card's subject**, where S2 and before it read its
  own slot's entity. They differ only on a slot that names another entity with a free-subject
  `valueWhile`; the library's one `valueWhile` (`progress.pkl`) is on a subject reading.
- **A computed subject is removed** (`resolveDirect`, `NodePlan.subjectDynamic`,
  `DynamicSubjectSuite`): no card builds one. If one is ever needed, it is an `Entity` whose id is
  itself a `ValueExpr`.
- **`Present` is one ordered map**: the order decides a tie (`first()`, a set's stable sort), and an
  unguarded candidate is always present (`Conditions.scala:79`). `Cell` already decodes a
  `ListMap` in wire order.
- **The fast tier owns its steps** (ADR 0028 rule 4): a step is pinned to a CEL spelling where one
  exists and held by tests of its own otherwise. `terminology.md`'s "each pinned byte-equal" is
  corrected.
- **Compile.** The node plan's slot is `Planned.Constant(value: SlotValue)` or `Planned.Live`. A
  pipeline with no live input folds once per renderer into a constant: a literal through a stage,
  and a source-less CEL whose inputs are constants and renderer bindings, which is every click
  transform today (now `reads: once` readings in the identity cache). A live pipeline is composed
  once into one function, so a render walks no step list.
- **Absorbs #657's P3 and P5:** `slotMod.bool(name)` declares a bool handle, `slotMod.cssClass`
  adds the `ThemeClasses` stage, and a literal is its own case.

## Pkl

One set of Pkl classes mirrors the Scala ones, paired by `WireShapeSuite`; there is no second
copy and no lowering layer. Pkl has no user generics, so a type is a field. The helpers keep their
call sites: `c.attr`, `c.expr`, `c.isOn`, `p.history.of`, `q.from(…).count()`, `slotMod.asStyle`,
`x.of(…)`. What changes: `Reading` becomes the Pkl `ValueExpr`, with `source` and `steps` in place
of `entityId` and `transform`; `simpleMod`'s functions build steps; the 31 hand-built
`new slotMod.Reading { … }` in the library, and the example in `writing-components.md`, change
shape. A component of a user's own that builds a `Reading` by hand breaks, accepted pre-v1.

## Steps, one commit each, the suite green at each

- **S1. Rendered goldens.** `RenderGoldenSuite` checks in what the test dashboards render (the
  three `FixtureEntries`, the starter and the smoke dashboards): each body, surface and node
  patch, at the house as fixtured and flipped, with each transform hash numbered by first
  appearance. `fixture-slots` is new, for the slot shapes the others miss (CEL with and without
  an entity, attribute reads, bool inputs, cell slots, a count read by name, charts, a chooser);
  it and the starter get wire snapshots too. The wire snapshots change wholesale from S3 on and
  cannot be read as a diff; the goldens prove every later step renders the same bytes apart from
  the names, or name the bytes that change.
- **S2. Entity ids written by Pkl;** Scala's inheritance and the computed subject go, and
  `validate` holds `entity_id` to a literal. Pkl writes exactly what Scala inherited, so the
  goldens hold and the wire gains only the `entityId`s. A reading with no entity still compiles
  its CEL in the full environment; the one without `state`, `attr`, `entity_id` and `domain`
  comes with `source` in S3.
- **S3. The value model** for template and cell slots: `Value` (with `Condition` as a source),
  `ValueExpr`, `Step`, Simple split into parts and Format steps, the chart a step
  (`core/stage.pkl` goes), `SignalBind` and `Part` as objects, Pkl classes and helpers,
  `WireShapeSuite`'s pairs. `Entity(of = None)` replaces S2's copied ids, resolved in
  `Component.bound` (S2's Pkl fill and the test helper `memberSlots` go); a step's operand that
  names none reads its source's entity. The new decoders reject unknown fields from the start
  (#657's open item). The goldens change only in signal names; the wire loses the click
  transforms' entity source.
- **S4. Named inputs:** `ExprValue` becomes `ValueExpr` inputs, with `Present`; the node's
  `values` likewise.
- **S5. Types:** typed literals and defaults, `SlotType` with `slotMod.bool`, the binding's type
  check, and the `ThemeClasses` stage in place of `SlotType.CssClass`.
- **S6. Compile:** `Planned = Constant | Live`, live pipelines composed once.
- **S7. Docs.**

## Part B: the next stacked PR

`Predicate` dissolves into the step model, so one condition language serves slots, `c.iff` and
candidate sets:

| Where | Today | In B |
|---|---|---|
| a bool slot, a CEL input | `Value.Condition(p)` (A) | a bool `ValueExpr` |
| a tally's guards | `Present`'s `Predicate`s | bool `ValueExpr`s |
| a candidate set's clauses | `SetClause.when` | a bool `ValueExpr` per clause |
| `c.iff`'s branch | `Activation.State(condition)` | a bool `ValueExpr` |
| a set's order | `SortKey.Holds(p)` / `SortKey.Prop("attr:x")` | a `ValueExpr` per term |

- **Operations:** `Compare(op, to)` on a value; `Not`, `And(with)`, `Or(with)` on a bool;
  `AnyOf`, `AllOf`, `NoneOf`, `First` on a `Present` list. An operand is then a `ValueExpr` (a
  plain value is one with no steps), which widens O1.
- **No `Value.Member`:** `Entity(of = None)` already means "the entity this is about", which for a
  set's clause or order term is the member it is decided for. Today's free-subject `Cmp` is the
  same reference, so the model has one.
- **`Part.Registry(name)`**, for today's `reg:<name>`.
- **Reused machinery:** what decides presence, branches and order (`Conditions`) becomes the
  evaluator of bool and sort `ValueExpr`s, and the entity index comes from the same walk over a
  `ValueExpr`'s references a slot's bind uses. `Activation.Var` (a viewer's selection) stays.
- **Regions** compose with it: the value model decides whether and which, a region is where the
  content lands.
- ADRs 0003 and 0007, `query.pkl`'s builders and the presence evaluation move with it.

## Review: what the design has to watch

- **Signal names change, accepted.** A display signal is named by its entity and a hash of its
  transform's key (`Renderer.displayPath`, `transformSegment`), and the key is now the
  `ValueExpr`'s, so the hashes move. S1's goldens normalise them.
- **Shared reads.** Several things are shared by what they read: a display signal per entity and
  key (#134), the once-cache, a fetch asked once per render however many slots read it
  (`SlotAsk`; two sizes of one chart share the fetch, not the bytes), a compiled CEL program per
  source and environment. Each now takes its key from the bound `ValueExpr` (the subject
  resolved): the whole key for signals and the once-cache, the `Fetch` source and its `Chart` step
  for the ask.
- **Render cost.** The fast tier is one `match` today. A live pipeline must not walk a `List` or
  allocate intermediates per render (S6); RenderBench's `simple`, `direct`, `cel`, `tickRender` and
  `page` hold. `ThemeClasses` on a live value runs per resolve, as today: a memo per value would be
  unbounded for arbitrary text.
- **Typed literals move bytes.** A literal flag becomes `true` where it was `"1"`: a `jsValue`
  prints `true` for `'1'`, and a cell's literal class carries `true`. Behaviour is the same; the
  goldens name each such diff in S5.
- **Wire size grows by a constant factor, not faster.** The nested shape costs bytes per slot,
  which is accepted. What is not: bytes that grow with how often a thing is used, such as one
  `Present`'s candidate list repeated in every slot that reads it because the design leaves Pkl no
  way to share it. Each wire snapshot's compact size, and the bytes per non-literal slot, are
  measured before and after S3, S4 and S5 (S1's baseline: 131–158 B per slot object across the
  four snapshots); a dashboard whose growth departs from the others' gets looked at before the
  next step.
- **Pkl evaluation time.** Building the wire runs at boot and on every dump refresh, so the
  starter site's evaluation time is measured before and after S3.
- **`WithUnit` merges two rules that differ at the edge.** `AttrUnit` leaves the unit off an empty
  value; `SuffixUnit` does not, printing `" W"` for an empty state. The merged step leaves it off,
  a byte change the goldens name.
- **Conditions keep their strings in A.** `Present`'s guards and `Value.Condition` are still
  `Predicate`, whose properties are `"attr:x"` strings, until B.
- **`bypassUnavailable`** yields the raw state, text, so it stays refused on a slot that must
  produce a bool, as `validate` does today.
- **Names** (`Value`, `ValueExpr`, `Present`) are working names, settled in S3. **Reading** stays
  the term for a `ValueExpr` over live state, and the source field is `source`, since Pkl's
  `Slot.value` and CEL's `value` already use the word.

## Tests that change

Approval for these is asked with this plan. About 283 test lines build `SlotSource` or
`TemplateSlot` (S3, mechanical); `DynamicSubjectSuite` is deleted with the feature (S2); the
library's Pkl tests follow the new classes; the wire snapshots are regenerated with
`dashboardSnapshotsUpdate` and the goldens hold. S1 adds the goldens.

## Docs

ADRs 0006 (the handle and its value), 0017 (`SignalBind` objects, the binding's type check), 0020
(the `ThemeClasses` stage), 0028 (the split, pinning where a spelling exists), 0034 (named inputs,
bind); `terminology.md` (Slot, Reading, Slot type, Expression value, Value while, the tiers);
`writing-components.md`; the architecture doc's node plan; the module `CLAUDE.md`'s slot lines.

## Not in this plan

- Part B, above: the next stacked PR.
- A `number` slot type with slot-views point 4: it waits on the tap call design.
- The tap call design round: a declared call id in place of the URL a template spells out.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`, S1's
goldens, and every benchmark run once (`-f 1 -wi 0 -i 1`): CI only compiles them, and S2 left
`RenderBench`'s setup failing. RenderBench and `validate` timing before and after S3, S5 and S6; the starter site's Pkl
evaluation time before and after S3; `dashboard.json` size and bytes per slot before and after S3,
S4 and S5.
