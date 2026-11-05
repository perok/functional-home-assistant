# Plan: a slot's value is a source, and the steps run on it

Branch `slot-values`, stacked on `slot-views` (#657); PR #658. Discussed in chat on 2026-11-01.

**State:** part A (S1–S8) built on #658. Part B (B1–B4) and Part C (C0–C4) built on
`slot-conditions` (#666); its follow-ups are `cel-once` (#669) and `pkl-reading` (#670). Deleting
this plan is the maintainer's call.

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
    source: Value,                          // what it is read or computed from
    steps: List[Step] = Nil,                // run in order, each typed
    default: Option[Lit] = None             // when the result is empty; typed by the slot's type
)

enum SlotType:                              // what a literal is, and what a slot may produce
  case Text, Bool                           // Number joins with slot-views point 4

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
  case Present(tally: Predicate.Tally)      // the ids present, a list
  case Condition(p: Predicate)              // decides itself, over the entities it names
  case Cel(src: String,                     // computed from its inputs and the renderer's
           inputs: Map[String, ValueExpr] = Map.empty)  // bindings; it has no `value`

// in Predicate, so a condition's own `count()` and a Present share one shape; as today
final case class Tally(candidates: List[EntityId], when: Map[EntityId, Predicate])

enum Part:
  case State
  case Attr(name: String)
  case Whole                                // the entity itself, CEL's `fh.Entity` struct

sealed trait Step
final case class Cel(src: String,           // reads `value`, the step before's
                     inputs: Map[String, ValueExpr] = Map.empty) extends Step

sealed trait Simple extends Step            // the simple tier (ADR 0028): total, no engine
final case class Prefix(text: Value) extends Simple
final case class Suffix(text: Value) extends Simple
final case class WithUnit(unit: Value) extends Simple       // SuffixUnit and AttrUnit, merged
final case class Percent(min: Value, max: Value) extends Simple
final case class Fill(min: Value, max: Value) extends Simple
final case class Duration(scale: Double) extends Simple
final case class Match(cases: Map[String, Lit], otherwise: Value) extends Simple
case object Count extends Simple            // the size of a Present list

sealed trait Stage extends Step             // the renderer's own steps
final case class Chart(style: ChartStyle) extends Stage
case object ThemeClasses extends Stage

enum ValueType:                                    // what a source or step produces
  case Text, Bool, Int, Real, Dyn, Entity, Markup
  case List(of: ValueType)

enum SignalBind:                            // an object on the wire, no string to parse
  case Text, Bind, Handler
  case Style(property: String)
  case Attr(name: String)
  case Class(name: String)
```

On the wire every enum and sealed trait is `{"kind": "<Case>", …fields}`; a bare JSON primitive is
a literal `ValueExpr`. The node's `values` become `Map[String, ValueExpr]`, and a `Cell`'s classes
and styles hold `ValueExpr`s.

- **Typed in order.** Each source has a type: a literal its own, `State` `Text`, an `Attr` `Dyn`,
  `Whole` `Entity`, a fetch `List(List(Real))` (`[epochMillis, value]` pairs), `Present`
  `List(Text)`, a condition `Bool`, a CEL source what its compiler infers. Each step takes and gives
  one: a CEL step any (its `value` declared as it) to what it infers; `Prefix`, `Suffix`,
  `WithUnit`, `Percent`, `Fill` and `Duration` `Text`, `Int`, `Real` or `Dyn` to `Text`, so a
  whole entity or a list never prints as Java's `toString`; `Match` any to `Text` or `Bool`;
  `Count` a `List` to `Int`; `Chart` `List(List(Real))` to `Markup`, which only a raw hole takes;
  `ThemeClasses` `Text` to `Text`. Bind checks the list in order, and the last must produce the
  slot's `type`: `Text` takes `Text`, `Int`, `Real` or `Dyn`, `Bool` takes `Bool` or `Dyn`; a
  literal is checked the same way, so a `setIf` flag's slot is `slotMod.bool`. A
  CEL result no step takes (a map, a timestamp) fails at its compile, and an optional is `Dyn`.
  The binding checks it too, instead of implying it: `Class`
  needs a bool, `Text` and `Style` text, `Attr` text or a bool, `Handler` anything. A list is not
  `Dyn`: a series typed as one would turn a build error into error text on a wall panel.
- **CEL reads `value`.** In a step it is the value before it, declared as that step's type; a CEL
  source has none. Besides it, CEL reads its named inputs, `dashboard_slug` and `time_zone`; the
  fixed `state`, `attr`, `entity_id` and `domain` go. A reading of the entity itself is
  `Entity(part = Whole)`, whose value is the CEL struct `fh.Entity { state: string,
  attr: map(string, dyn), entity_id: string, domain: string }`, so `value.state == 'on'` and
  `value.attr.brightness` keep today's types. Spiked on cel 0.14.0: a `StructType` through the
  compiler's `CelTypeProvider` and a `StructValue` at run time; `value.state + 1` and `value.nope`
  are build errors, `value.attr[?'x'].orValue('')` and the string extensions work. A named input
  may be a whole entity too (`kitchen.attr.brightness`). `Present` reaches CEL as `list(string)`
  (`size(lights)`, `'light.kitchen' in lights`), written `q.from(…).where(…).present()`; `Count`
  is its simple-tier size. Every CEL in the
  library is rewritten, and a user's own CEL breaks, accepted pre-v1.
- **CEL computed from nothing is a source.** `Value.Cel` is a CEL that reads no running value: the
  click transforms, which read only `dashboard_slug`, and a CEL over inputs alone. So `source` is
  never absent. On a card with no subject, Pkl writes a reading's leading CEL step as this source
  (`c.expr("dashboard_slug")` on a plain button), where a read of the missing entity was dropped. A step's operand may not be a `Value.Cel` (`validate`), and a CEL input's own CEL
  sees no other input, so inputs cannot form a cycle.
- **The subject is named once per node.** The card's own `subject` (`String?`, which an HA card
  sets from its `entity`) is the literal `entity_id` slot; the template shows it and the server
  reads it as the node's subject. A reading of it is `Entity(of = None)`, so the wire does not
  repeat the id per reading (S2 had Pkl copy it, 1.7–2.7% of each wire at fixture id lengths).
  `Component.bound` resolves `None` to the subject, in the one place values and placement are
  already bound, so a bound slot always holds a concrete id and no consumer inherits; anything
  keyed by content (signals, the once-cache, programs) keys from the bound form. An
  `Entity(of = None)` on a card with no subject is a `validate` error naming the slot, in place of
  S2's Pkl throw.
- **A `valueWhile` naming no entity reads the card's subject**, where S2 and before it read its
  own slot's entity. They differ only on a slot that names another entity with a free-subject
  `valueWhile`; the library's one `valueWhile` (`progress.pkl`) is on a subject reading.
- **A computed subject is removed** (`resolveDirect`, `NodePlan.subjectDynamic`,
  `DynamicSubjectSuite`): no card builds one. If one is ever needed, it is an `Entity` whose id is
  itself a `ValueExpr`.
- **`Present` keeps `Tally` as it is**: the candidate list's order decides a tie (`first()`, a
  set's stable sort), and a candidate with no guard in `when` is always present. A `ListMap` would
  hold the same facts.
- **The fast tier owns its steps** (ADR 0028 rule 4): a step is pinned to a CEL spelling where one
  exists and held by tests of its own otherwise. `terminology.md`'s "each pinned byte-equal" is
  corrected.
- **Compile.** The node plan's slot is `Planned.Constant(value, js)` or `Planned.Live(source,
  run)`. A pipeline with no live input folds once per renderer into a constant: a literal through
  a stage, and a CEL source whose inputs are constants and renderer bindings, which is every click
  transform today (`reads: once`, through the identity cache). A live pipeline is composed once
  into one function, so a render walks no step list.
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
- **S4. Named inputs:** `ExprValue` becomes `ValueExpr` inputs, with `Present` and `Count`; an
  input is a literal, a count, a condition or a fetch, another shape waiting for a use; the node's
  `values` likewise.
- **S5. Types:** typed literals and defaults, `SlotType` with `slotMod.bool`, the binding's type
  check, and the `ThemeClasses` stage in place of `SlotType.CssClass`.
- **S6. Compile:** `Planned = Constant | Live`, live pipelines composed once.
- **S7. CEL reads `value`:** `Value.Cel` and a required `source`, `Part.Whole` with the `fh.Entity`
  struct (held once per state change, not per evaluation), the fixed names gone, `Present` as
  `list(string)`, `ValueType` checked in order through every step, and `Format` named `Simple` beside
  `Stage`. Pkl gains the source class and `Whole`; the library's CEL is rewritten to `value`. The
  goldens hold apart from signal names.
- **S8. Docs.**

## Part B: conditions are values

Branch `slot-conditions`, stacked on `slot-values` (#658). Discussed in chat on 2026-11-03. The
aim is consistency and simplicity: `Predicate` dissolves into the step model, so one condition
language serves slots, `c.iff` and candidate sets, and the evaluator stays as fast as today.
`query.pkl`'s author API does not change; only the classes it builds do.

| Where | Today | In B |
|---|---|---|
| a bool slot, a CEL input | `Value.Condition(p)` | a bool `ValueExpr` |
| a tally's guards | `Tally.when: Map[String, Predicate]` | `Present.when: Map[String, ValueExpr]` |
| a candidate set's clauses | `SetClause.when: Option[Predicate]` | `Option[ValueExpr]` |
| `c.iff`'s branch | `Activation.State(condition: Predicate)` | `Option[ValueExpr]`, absent for the else |
| an order term | `SortKey.Holds(p)` / `SortKey.Prop("attr:x")` | `SortTerm.by: ValueExpr` |

```scala
enum Value:
  case Literal(v: Lit)                // Lit stays scalar: no consumer for a list of literals
  case Entity(of: Option[String] = None, part: Part = Part.State, …)
  case Fetch(fetch: FetchTemplate)
  case Present(candidates: List[String], when: Map[String, ValueExpr] = Map.empty)
  case List(items: List[ValueExpr])   // one or more, of one type, without CEL
  case Cel(src, inputs)

// Simple steps on a list
case object Count                           // a list → Int
case object AllOf                           // a list of bools → Bool, stops at the first false
case object AnyOf                           // a list of bools → Bool, stops at the first true

// Not simple: it reads every listed id's state
case class First(orderBy: List[SortTerm])   // a list of ids → the leader's id, null when empty

// Simple steps on a value
case class Compare(op: Op, to: Value)       // text, a number or dyn → Bool
case object Not                             // Bool → Bool

SortTerm(by: ValueExpr, dir)
SetClause(when: Option[ValueExpr], node)
Activation.State(condition: Option[ValueExpr] = None)
```

- **`Present`** is what a `List` of its candidates as entity reads, filtered by each one's guard,
  would mean, kept as one source so a candidate is its id on the wire (15 B, not about 55). Its
  doc comment and `terminology.md` say so. `Tally` folds into it.
- **And/or are list steps:** `a && b` is `List([a, b]) |> AllOf`, symmetric, with step operands
  still a plain `Value`. The plan's `And(with)` step would have made every operand a `ValueExpr`
  and the first condition the source. `Not` is a step.
- **The aggregates are counts:** `.any()` is `Present |> Count |> Compare(gt, 0)`, `.none()`
  `== 0`, `.all()` `== length`, as today; `first().eq(e)` is `Present |> First(order) |>
  Compare(eq, e)`. No `AnyOf` on a present list: its items are ids, so `validate` refuses it.
- **Absent means holds**, everywhere: a candidate with no guard, a clause with no `when`, and an
  else branch, which loses today's `And(Nil)`.
- **No `Value.Member`:** `Entity(of = None)` means "the entity this is about": inside a guard or an
  order term the member it is decided for, elsewhere the card's subject. A state condition names
  its entities, as `validate` requires today (`hasFreeSubject` becomes the nested
  `readsMissingSubject`).
- **No `Part.Registry`:** the runtime holds no registry. `query.pkl` decides registry facts per
  candidate at build time (`ReadyReg`) and refuses a registry order key before a live one, so only
  `state` and `attr:<name>` reach it, `Part.State` and `Part.Attr`.
- **`Compare`** takes its meaning from the operand's type (ADR 0035, Part C). Strict `Dyn` (Not
  in this plan) is independent: a condition ends in a bool, never in `Dyn`, so it follows as its
  own PR.
- **`validate` holds every clause, state surface, guard and order term to a bool without CEL**
  (`ValueExpr.conditionError`).
- **Pkl keeps its classes:** `core/predicate.pkl`'s `Cmp`, `PAnd`, `POr`, `PNot`, `Count`, `First`
  stay the author's types and methods, their fields hidden, and each writes the reading: `source`
  and `steps` of `slot.pkl`'s value classes and `simple.pkl`'s steps. `Tally` IS the `Present`
  source; `ConditionRead` and `PresentRead` are gone. A settled condition is `PConst`, a literal
  bool.
- **Order terms:** `by` is evaluated per member; a bool key sorts true first under `asc`, any
  other no value last, numeric when both parse, else text (`Evaluator.Keys`). A
  set node's `orderBy` and `First`'s are the same type, compared by the same code. `First` stays
  one pass: a `Sort` step would allocate a sorted list per evaluation, and nothing else sorts.
- **The evaluator** is Part C's one compiler. `Count |> Compare(op, literal)` stops counting once
  the answer is fixed: `> 0` and `== 0` at the first present candidate, `== length` at the
  first absent, against a whole number only.
- **The wake index** is the same walk over a `ValueExpr`'s references a slot's bind uses, now
  through `Present`'s candidates and guards, `List`'s items and `Compare`'s operand.
- **Wire:** guards already repeat per candidate today (the build decides each candidate apart, so
  their leftovers can differ); B keeps the count of copies. A state guard goes from 56 B to 77 B,
  `fixture-slots`' count slot from 290 B to 354 B, and an and/or costs about 38 B more than
  `{"kind":"And","items":[…]}`: a constant factor per use, as Part A accepted.
- **Rendering does not change.** Member ids, deltas, order and the flip stay; the goldens hold
  apart from a set call's id, a hash of the set as the wire writes it.

### Steps

- **B0.** This section, and the draft PR.
- **B1. The new kinds:** `Value.List`, `Compare`, `Not`, `AllOf`, `AnyOf`, their `ValueType`
  rules, keys and wake walk, and `Conditions` evaluating a bool `ValueExpr`, unit-tested. Nothing
  emits them yet.
- **B2. Every condition switches at once:** bool slots, CEL inputs, `Present`'s guards, clauses,
  `c.iff`, `First` and the early-stopping count, in Scala and in Pkl (`core/predicate.pkl`'s
  classes write the value classes; `query.pkl` is unchanged). One step because a predicate tree
  nests counts. `Predicate`, `Tally` and `Value.Condition` are deleted.
- **B3. Order terms:** `SortTerm.by`, `SortKey` deleted. Also the build-time bool sort:
  `orderValue` returns a Boolean for a condition over a registry fact, and Pkl 0.32.1 refuses
  `<` on Booleans (spiked), so such an order fails the build; its comment ("`desc` puts true
  first, matching the runtime") contradicts the runtime and `predicate.pkl`, which put true first
  under `asc`. Fixed to the runtime's rule, with a test ordering on a registry condition. A
  Boolean registry property had the same `<` failure; it sorts as the runtime sorts a value's
  text, false first.
- **B4. Docs:** ADRs 0003, 0004 and 0007, and 0006, 0013, 0015, 0028 and 0034 where they name
  `Predicate`; `terminology.md` (Candidate set, Clause, Present); the module `CLAUDE.md`'s
  `Dashboard.scala` and `query.pkl` rows; the architecture doc where it names a predicate. The
  plan is then deleted, the maintainer's call, and its "Not in this plan" items move to a new home.

### B's tests

Approval asked with this section. About 235 test lines in 29 files build `Predicate`,
`SetClause`, `SortKey` or `Activation.State` and are rewritten to the new constructors (B2, B3,
mechanical, through the testkit's `Conds`, which builds what `core/predicate.pkl` writes; no
assertion weakened). The asserts on a `domain` or `entity_id` comparison go with those
properties, which nothing emits. New: each step's evaluation and typing (B1); the early-stopping
count giving a full count's answer for every op and candidate mix (B2); ordering on a registry
condition (B3). The library's Pkl tests follow the classes, the wire snapshots are regenerated with
`dashboardSnapshotsUpdate`, and the goldens hold.

### B's checks

Per step, as Part A's. RenderBench's `condition`, `conditionCel`, `holdsSeed`, `pageSet`,
`publishSet`, `publishManySets`, `publishFlip`, `publishSetIfs`, `publishSetIfsFlip`,
`resumeFlip`, `memberClauseFlip` and `memberClauseSteady`, and `validate` timing, before and after
B2 and B3; the starter site's Pkl evaluation time and `dashboard.json` size before and after B2.

## Part C: one evaluator, compiled once

B left three evaluators for a `ValueExpr`, and they disagree:

| Path | Runs | An attribute reads | An entity the store lacks | Skips |
|---|---|---|---|---|
| `Conditions.run`, walking the tree per call | clauses, `Present` guards, order terms, state surfaces, set calls, a slot with no CEL and a deciding step | its JSON text: `21.0` | `Missing`: a comparison over it is false | CEL, `Stage`, a misplaced `First` |
| `Renderer.compose`, closures built per slot plan | every other slot | typed: `21` | `""`: `ne("on")` holds | `First` |
| `Conditions.resolve` | CEL's named inputs | typed | `""` | anything off its case list |

`validate` accepts expressions that mix CEL with a deciding step, so `compose` carries its own
copies of `Compare`, `Not`, `AllOf` and `AnyOf` (in `Transform.formatWith`). One such shape is
wrong: `Present |> First |> Cel("value + '!'")` types as text, but `compose` skips `First`, so
CEL gets the id list. Refusing those shapes would keep two evaluators, and one `attr(…)` would
still read `21` on a card and `21.0` in a condition.

### The value language and the evaluator

Agreed 2026-11-04, and recorded as ADR 0035 (C4): typed reads everywhere, `null` the one absent
value and every step strict, `Compare` by the operand's type, the boundaries where a value
leaves the language, the bypass on a text slot's own read only, and one compiler that every
consumer calls, its early stops picked at compile. The open question there is an `Option` type.

### Steps

- **C0. The benchmarks C changes and no case runs yet:** a set ordered by a condition and an
  attribute (`publishSetOrdered`; no benchmark orders a set), `first().eq(e)` for a row of pills
  (`leaderRow`), an and/or/not tree per leaf (`conditionTree`), and a guard over an attribute on a
  fresh state (`conditionAttr`, where the typed read is paid). A one-off A/B of the same against
  slot-values (the fixtures ported to its `Predicate` and `SortKey`, the port not committed)
  gives B2's `First` and and/or and B3's order terms a before number.
- **C1a (done).** The goldens render every dashboard a third time, its entities unavailable.
- **C1b (done). The bypass:** off by default, `validate` refusing it off a text slot's own state
  or whole-entity read, `valueSlot` and the `Expr` readouts opting in. The goldens move only
  under "unavailable": the more-info lines list what HA still reports, an attribute second line
  and a cell style read as is.
- **C1c (done). The compiler, and the renderer's slots on it** (`Evaluator`); `compose` goes. The
  goldens hold byte for byte; RenderBench equal or better, allocation down on the render paths
  (`tickRender` -6%, `pageSignals` -3%, `page` -2%).
- **C2 (done). Every other consumer on it:** `MemberGraph` (clauses, and an ordering's keys read
  once per candidate per sort), `SurfaceGraph`, set calls, CEL inputs (compiled once);
  `Conditions` is deleted and `isCondition` stays only as what `validate` holds a condition to.
  One round against C0: `publishSetOrdered` -41% (1.35 MB -> 0.52 MB), `leaderRow` -72%,
  `conditionTree` -69% and allocation-free, `countAny` -86%, `conditionCel` -34%, `pageSet`
  -9%. `conditionAttr` regressed +174% (94 -> 202 kB), the typed read building the entity's
  whole Java attribute map on a fresh state; `EntityState.attribute` reads the one attribute
  until the map exists: 202 -> 26 kB, 76 -> 8.4 µs.
- **C3 (done). `First` over any id list;** the position rule goes (`ValueExprSuite`'s case
  changed, approved), and a test renders `Present |> First |> Cel` and gets the leader.
- **C4 (done). Docs:** the value language and the evaluator are ADR 0035, `terminology.md`'s
  Reading pointing at it; ADRs 0004, 0007, 0028 and 0034, `terminology.md`, the architecture
  doc's file map and the module `CLAUDE.md` name the evaluator where they named `Conditions`;
  Part B's paragraphs that C superseded are rewritten.

### C's tests

Approved 2026-11-04. `ConditionsSuite`'s sweep ("a condition decides as the rule says") has the
JSON-text rule as its oracle, which becomes the agreed one; tests pinning `21.0`
against `21` or a slot over a missing entity follow the agreed rules. Tests and benchmarks that
call `Conditions.holds` call a compiled run instead (mechanical); the compile cost is measured
apart from the per-call cost.

Approved with "What moves": `TransformSuite`'s pins that `Percent` and `Fill`
render `0 %` and `100%` over what they cannot read become pins that they give `null`, and the
builders' defaults are pinned in their place; the wire snapshots gain those defaults
(`dashboardSnapshotsUpdate`), and the goldens must hold.

### C's checks

Per step, as B's, interleaved against the slot-conditions head: `condition`, `countAny`,
`countAnyFull`, `conditionCel`, `simple`, `direct`, `cel`, `memberClauseSteady`,
`memberClauseFlip`, `pageSet`, `publishSet`, `publishFlip`, `publishSetIfs` and C0's four.

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
- **`bypassUnavailable`** yields the raw state, text, so it stays refused on a slot that must
  produce a bool, as `validate` does today.
- **A CEL evaluation reads the entity through a struct value.** Held on the entity's state, built
  once per state change, so an evaluation allocates no more than today's name resolver.
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
ADRs 0001, 0004, 0012, 0027, 0031 and 0033 named `SlotSource`, `Transform.Simple` or the fixed CEL
names, and are corrected where they did.

## Not in this plan

- **One machinery for "a value picks what fills a region".** A state `c.iff` is a bake group
  chosen by `Activation.State` (`SurfaceGraph`, the flip); a set member picks its first holding
  clause and the set its present members (`MemberGraph`, a Scala-written `fh-group` wrapper).
  Both are "the first branch that holds renders", and tabs and `c.iff` over a variable are the
  same with a per-viewer value. With every decision a value expression (Part B), a region filled
  by a value (the branch a bool or a variable picks, or one node per id of a list) could serve all
  of them, and `SetNode` would become a card. ADR 0007's "a member container is a per-entity
  comprehension, not a gate" is the argument to answer. Goes with the regions round.
- A `number` slot type with slot-views point 4: it waits on the tap call design.
- The tap call design round: a declared call id in place of the URL a template spells out.
- **Strict `Dyn`** is an open question in ADR 0035, beside the `Option` type.
- **Goldens as short, specific dashboards**, in place of whole fixture and smoke pages.
- **From the 2026-11-05 review**, to do after this stack:
  - A CEL's named inputs live once, on the slot: the wire puts them on each CEL step and source,
    and bind merges them per slot, so every CEL in a slot gets all of them. One concept, four
    names (Pkl `values`, wire `inputs`, Scala `values`, "expression values").
  - Type-check and compile as one step, so `validate` hands the renderer compiled runs: today the
    renderer pairs each CEL with its program by position (`ValueExpr.typed`'s key order).
  - One exhaustive operand and child traversal over `ValueExpr`, in place of six hand-written
    walks with `case _ =>` defaults (`about`, `operands`, `readsMissingSubject`,
    `bypassesInside`, `entities`, `isLive`), where a new step or value kind falls through silently.
  - Pkl's `Expr` folded into `Reading`, as `Predicate` was into `ValueExpr`: about 15
    `is Expr` conversions, and a card's `label`/`value` take an `Expr` but not a reading.
  - `query.pkl` and `Cmp` carry a property as a typed `Part`, not `"attr:<name>"` text parsed back
    by `propertyRead`.
  - `SlotAsk.Stage`, a copy of the fetch stages `Step` already has, folded into `Step`.
  - One definition of a condition (`isCondition` by shape, `conditionError` by type). A state
    surface's unnamed-entity check still reads `readsMissingSubject` alone, since it runs after
    `conditionError`; with one definition it is `unnamedComparison` like the slot checks.
  - `validate` out of `Dashboard.scala` into its own file.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`, S1's
goldens, and every benchmark run once (`-f 1 -wi 0 -i 1`): CI only compiles them, and S2 left
`RenderBench`'s setup failing. RenderBench and `validate` timing before and after S3, S5, S6 and S7; the starter site's Pkl
evaluation time before and after S3; `dashboard.json` size and bytes per slot before and after S3,
S4, S5 and S7. S7's `validate` timing (607f3bf6 against the S7 head) and the starter site's
evaluation time (d5aaf310 against the head) run after Part B: the commits are fixed, so waiting
loses nothing.
