# Plan: one fast expression family, and `valueWhile` decided once (#541)

Agreed in chat on 2026-10-25 after reviewing #634 (`valueWhile`) and #633 (the progress bar).
**Part 1 is built on this PR, except Q32's `otherwise` (built as optional, to be reworked); part 2
is built on #634 as a first version, to be reworked as below; 3 is not started.** The work lands
in three PRs, in stack order:

1. **This PR** (between #627 and #634): one fast expression family, and no CEL the author did not
   write.
2. **#634**: `valueWhile` decided once per slot per update.
3. **#633**: the progress bar sits in a `c.iff`.

## Why

There are two fast machineries that do not know about each other:

- `Transform.Simple` (ADR 0028): value shapes (`State`, `Attr`, `SuffixUnit`, `Prefix`,
  `Suffix`, `Percent`, `Fill`, `Duration`, `Match`) over the slot's one entity.
- `Predicate`, interpreted by `Conditions`: `Cmp` on any named entity, `And`/`Or`/`Not`, `Count`,
  `First`. `c.iff`, candidate sets and set taps use it.

A yes/no input bridges them through CEL. `active`, `disabled`, `classWhen` and `valueWhile` store
the condition as a value named `__active` (and so on) in `Node.derivedValues`, and the slot's CEL
is just that name. So every condition on a card is decided by `Conditions` and then passed
through a CEL call that does nothing. ADR 0034 rejected "a condition as a slot transform form";
that rejection was the mistake.

The library also writes CEL for inputs that are not CEL: the slot's default `transform = "state"`,
`.value("attr")`, `c.select`'s current option and its labels, and the slider's `"''"`.

#634, as first opened, decided `valueWhile` up to four times per update: in the pre-read, the
render key, the paint and the signal path. It also found the gating entity in two places
(`Component.gated`, `Renderer.withheld`).

## 1. This PR: one fast expression family

### The family

- **A condition is a shape.** `Predicate`'s cases join `Transform.Simple`, in one sealed family
  in one file. There is no wrapper: the wire object with `kind: "Cmp"` that `c.iff` takes is the
  same object a slot's `transform` takes. `Predicate` stays the name of the bool half, so `c.iff`,
  candidate sets and set taps are unchanged.
- **One decoder.** A `transform` is a string (CEL), an object with `stage` (a fetch stage), or an
  object with `kind`. `Op`/`Match` decode as today; the predicate kinds decode as `Predicate`.
- **One interpreter.** `Transform.runSimpleValue(s, subject, states)`. `Conditions.matchesIn` is
  its bool half, and a predicate shape returns a real `Boolean`. Measured: a condition filling a
  slot, 200 times, costs 9.5 µs and 14.4 kB decided directly, against 22.6 µs and 78.4 kB through
  the CEL bridge (`RenderBench.condition` vs `conditionCel`). `simple` and `page` allocate 2.3% and
  0.7% less than before.
- **One key.** `Simple.key` covers the predicate shapes, reusing the canonical form `ExprValue.key`
  gives a condition today. A key cannot collide with a CEL source, because no key is valid CEL.
- **Every read may name an entity**, defaulting to the slot's: each value shape gains
  `entity: String? = null`, as `Cmp` has. A named entity joins the slot's live entities (so it
  wakes the slot) and `referencedEntities` (so ADR 0023's bound covers it). A named entity the
  store does not hold renders the shape's absent form, as a `Cmp` on it is already `false`.
- **A condition in a slot reads the slot's entity** when it names none. The "free subject"
  refusal stays where there is no subject (a state activation, a value shared by content, a slot
  with no entity). A `once` slot that names another entity is refused, as one reading a live
  value is.
- **Types:** a predicate shape is a bool, so it fills a bool slot. On a text slot it renders
  `true`/`false`, as a bool CEL result does.
- **`Match` reads any property**, not only `state`: it takes the `Cmp` read (`property`, null
  meaning the state, plus an optional `entity`). That is what `c.select`'s labels need.
- **`otherwise` stays required, and may be a read (Q32).** It is a literal or a value shape
  (`Simple.Value`), so a match still says what an unmatched value shows. `c.select`'s `chosen`
  passes the read of its current property (`simple.state()` or `simple.attr(name)`): an option
  it has a label for shows the label, any other its raw value. The key covers the read.

### No CEL the author did not write

| Site | Today | After |
|---|---|---|
| `Slot.transform` default (Pkl and `SlotSource`) | `"state"` | `simpleMod.state()` |
| `nodes.booleanSlot` for a condition | `transform = "__name"` + a derived value | `transform = <condition>` |
| `Node.derivedValues`, the `__active`/`__disabled`/`__class_<hash>` names, the `__` refusal | exist | deleted |
| `slot.valueSlot(attrName)` | attr + `UNIT_EXPR` CEL | a new `AttrUnit(attribute)` shape |
| `select.pkl` `entityChosen` | `<current> == 'v'` CEL | a `Cmp` |
| `select.pkl` `chosen` | a CEL map lookup | a `Match` over the current property |
| `slider.pkl` `fillColor` fallback | `"''"` | a literal |

`"state"` and `Simple.State` already share the key `state`, so the default moves no signal name.

**CEL the library writes on purpose stays.** Each can become a shape once a measurement says so:
`CLOCK_TIME_EXPR`, the slider's percent/fill/hue (`valueExpr`, `percentExpr`, …), `wheel.pkl`'s
hs, more-info's `ATTR_LINES`, `bar.pkl`'s `peakSinceZero`, and `tap.pkl`'s `clickSlot` (read
once).

`ExprValue.Holds` stays: an author's CEL may still read a condition by name. That is CEL the
author wrote.

### ADR 0028's rule

A shape is no longer defined as a copy of a CEL expression. A shape that names another entity, or
compares, has no single CEL spelling to copy. Each shape is defined on its own, in its doc comment,
and must still be total over every state, `unknown` included. `TransformSuite`'s parity battery
stays for the shapes that have a CEL spelling: there it checks that the fast read gives exactly
what an author's CEL would give. It no longer defines them.

### Docs and checks

- ADR 0028 rewritten (the rule above, the predicate half, named entities). ADR 0034's "Boolean
  slots" rewritten: a condition fills a bool slot directly. Its Rejected bullet goes.
- `docs/architecture-rendering-pipeline.md`, `docs/terminology.md`, `writing-components.md` where
  they mention derived values or the one-entity limit.
- `RenderBench` before and after, numbers in the PR.
- `sbt dashboardSnapshotsUpdate`, then read the JSON diff: transforms that were `"state"`, the
  boolean inputs, and `select`/`slider` should be the only moves.

## 2. #634: `valueWhile` decided once per update

Merged onto this PR's branch first (merge, not rebase).

- **The API stays.** `valueWhile` takes a constant, a condition or CEL. None is refused. A
  condition is the fast path and the easy one to write.
- **Through the family.** A condition fills `valueWhileSlot` as a transform, like the other
  boolean inputs. `valueWhile = false` folds at build: the slot is the literal `""`. The
  free-subject refusal goes; a condition reads the slot's entity.
- **While false, the slot has no value**, written as `""`. Its transform does not run and its
  fetches are not read.

### What the first version got wrong

It is built on #634, and it works, but it bolts the decision onto the fetch request instead of
putting it where the other per-update decisions are:

- **The condition rides on the ask** (`SlotAsk.gate`, `Component.gated`), because the pre-read
  collects asks and an ask no longer knows its slot.
- **A set is fetched for every candidate and every clause** (`memberAsks`, `setAsks`), present or
  not, and only `valueWhile` filters that. A card that will not render can still fetch.
- **The decisions are a third private record** (`Asked.gates`, carried by `FetchSnapshot`), beside
  the pre-read's and the walk's own derivations of which surfaces show and which members are
  present.

### Three layers, and which one each piece belongs to

The renderer is already layered like a compiler. The rework names the layers and puts each piece
in one:

1. **Build** (`Dashboard.Validated`, the build passes in `docs/terminology.md`): once per
   evaluation. Unchanged.
2. **Index**, once per renderer: what can be known from the tree alone. `Renderer`'s `Index` (ids,
   parents, each node's asks, which nodes an entity wakes), `MemberGraph` (every member a
   candidate can be, one per clause, with its nodes), `SurfaceGraph`, `VarGraph`. Nothing after
   this walks the tree.
3. **Decisions**, once per render: what the entity states and this viewer decide, derived from
   the snapshot that render uses, before anything is read or painted. Today each reader derives
   these itself. The rework gives them one value, `Decisions`, built once per render and handed to
   the pre-read, the walk, the render key, the paint and the signal path.

**What moves into the index:**

- Each node's fetching slots, each with its asks and its `valueWhile` (the condition and the
  entity it is decided over, `BoundSlot.gateEntity`), in place of the node's flat `fetches`.
- A member's asks are read off the member a candidate is in one clause (`Member.nodes`, already
  built per clause with the graph), not merged across clauses.

**What `Decisions` holds**, each part computed on first use and kept for the rest of the render, so
a pull that reaches one node does not decide the whole page:

- **Which surfaces show** for this viewer: open popups, the selected tab, each state group's
  branch (`SurfaceGraph`, as now).
- **Which members are present, in which clause**, per set (`MemberGraph.membersOf`, as now). It is
  derived from the render's own snapshot, never read from the recorder's `Membership`: §4b's
  "readers derive, the recorder folds" stands, because a reader paired with the recorder's
  members can claim a version whose members it does not show (`ResumeSuite`).
- **Each distinct `valueWhile`**, keyed by content (the entity and the condition), so two cards
  gated alike share one decision and two cards sharing a read with only one gated still differ.
  A slot with no fetch, or whose subject only the paint knows, asks the same value, so every
  `valueWhile` is decided once per render wherever it is first needed.

**The pre-read then follows what will render:**

- the body, and the surfaces `Decisions` shows;
- for each set, the present members and only their current clause's nodes, and the nested sets in
  that clause the same way;
- for each fetching slot there, its asks only where its `valueWhile` holds.

It returns reads only. `FetchSnapshot` goes back to holding answers.

**What goes:** `SlotAsk.gate`, `Component.gated`, `SlotAsk.Gate`, `Asked`, `FetchSnapshot.gates`
and `decided`, `memberAsks`, `setAsks` and `membersUnder`, and the fall-back deciding in
`Renderer.withheld` and `Renderer.asked`.

**Sharing across viewers is left out.** The state half of `Decisions` (membership and
`valueWhile`) is the same for every viewer rendering the same store version, so it could be
shared per slug, as `RenderCache` shares bytes. Add it if a benchmark says the per-render cost
matters.

### Checks and docs

- `ValueWhileSuite`: an absent candidate and a clause that does not match ask for no fetch; the
  walk and the pre-read agree on a set, a nested set and a state group; a `valueWhile` is decided
  once per render.
- `FetchDriftSuite` still holds: the pre-read asks for exactly what the walk reads.
- `FetchBench` and `RenderBench` before and after. The pre-read now derives membership, which it
  did not, and the walk reuses it, so a set is still derived once per render.
- `docs/architecture-rendering-pipeline.md` §1a (the three layers) and §6's fetch section, ADR
  0034's `valueWhile` section, `docs/terminology.md` (**Index**, **Decisions**).

## 3. #633: the progress bar in a `c.iff`

- **The bar sits in a `c.iff`:** `features { c.iff(running).then(bar) }`. A spike showed this works.
  - While idle, the cell's `.fh-ifhost` is empty, and `core/css.pkl`'s existing rule collapses the
    cell.
  - No history is read while idle.
  - A start patches the bar in; the end removes it.
- **Removed:** the `fh-bar-idle` class, its CSS and `idleClass` in `base/bar.pkl`, and
  `progress.pkl`'s `whileRunning`, which reached into the bar's slots. The bar keeps its own
  `data-show`, which hides it on `unknown`.
- **The battery bar** has no `c.iff`: it always shows (Q27).
- **The countdown** keeps `valueWhile = running` (Q28).
- **`running` is never null:** `Boolean|Predicate`, `true` when nothing can tell. Exposed on the
  card, so `local p = c.progress(d)` then `c.iff(p.running).then(p)` hides the whole card. The
  card itself never hides; only its bar does.
- **Docs:** ADR 0015's paragraph and `writing-dashboards.md` describe the `c.iff`, not the class.
  `SinceStartBarSuite`'s cell assertion moves to the empty `.fh-ifhost`.
