# Plan: one fast expression family, and `valueWhile` decided once (#541)

Agreed in chat on 2026-10-25 after reviewing #634 (`valueWhile`) and #633 (the progress bar).
**Part 1 is built on this PR, part 2 on #634; 3 is not started.** The work lands in three PRs, in stack
order:

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

#634 decides `valueWhile` up to four times per update: in the pre-read, the render key, the paint
and the signal path. It also finds the gating entity in two places (`Component.gated`,
`Renderer.withheld`).

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
- **One decision per gate per update.** While false, the slot has no value, written as `""`. Its
  transform does not run and its fetches are not read.
  - A slot that reads a fetch is decided in the pre-read (`fetchesForPage`, `fetchesForSurface`,
    `readsForPull`), because a render is a synchronous string build and its fetches are answered
    before it. Each ask already carries its slot's gate (`SlotAsk.gate`), and that is the only way
    the pre-read reaches a member's subtree, so the gate stays on the ask. The pre-read decides
    each distinct gate once and returns the decisions beside the reads (`Asked`), keyed by content
    (`SlotAsk.Gate.key`: the entity and the condition), so two cards sharing a read with only one
    gated still differ, and two gated alike share a decision. `FetchSnapshot` carries them.
  - The render key, the paint and the signal path look the decision up and never evaluate the
    condition again.
  - A slot that reads no fetch, or whose subject only a paint knows, is decided once, where its
    value is computed.
  - One function resolves the entity a gate is decided over (`BoundSlot.gateEntity`), for the
    pre-read and the paint.
- **Docs written in #634** (ADR 0034's `valueWhile` section, the architecture doc, terminology,
  `writing-components.md`) say this instead.
- `ValueWhileSuite` checks that the walk keeps the pre-read's decision, and that CEL decides too.

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

## Open questions

- **Q32.** `c.select`'s `chosen` shows a value outside the options as itself, so a `Match`'s
  `otherwise` there is "the property read", not a constant. Recommended, and built that way
  pending a yes: `otherwise` becomes optional, and absent means the read itself. That still degrades on a state HA adds, which is
  why ADR 0028 made it required, and it is not "the cases are exhaustive", which it rejected. The
  alternative is to leave `chosen` as CEL.
