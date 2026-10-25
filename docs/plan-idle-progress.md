# Plan: a slot's value only while a condition holds, and a device's idle bar (#541)

A device's progress bar means nothing while no job runs: a washer at 0 minutes, a printer at 100%.
The bar should leave by itself, and a bar that reads history should not read it while idle. Both are
one general mechanism on a slot, `valueWhile`, which the progress roles then set per bar.

Stacked on #627 (E3, E3b). Replaces this PR's first version, `.whileRunning()`, which hid the whole
card through a `c.iff`.

## Decisions

- **Q26.** The bar hides while idle by default. Nothing to call; the card stays.
- **Q29.** A status whose state is one of `defaults.progress.idleStates` means idle, whatever the
  numbers say (a delayed start shows the programme's length as time left). Any other state leaves
  the numbers to decide. A list of IDLE words, not running words: a missing idle word shows a bar
  at 0, a missing running word would hide a running job. Shipped empty until the words are read off
  real devices.
- **Q30.** A slot with no value is `""` on the wire, as a CEL `null` already is (`Cel.stringify`).
  No second signal: a `null` in a signals frame deletes the signal (ADR 0017), and what `""` means
  is the card's to say. The bar hides on it.
- **Q31.** Which condition gates the bar follows the role the device was found with, and the
  expensive bar (history) is gated so it is never read while idle.
- **Q27.** The battery bar always shows. An animation while it charges is a later idea.
- **Q28.** While idle the countdown reads empty, by the same rule, so the status line is what
  shows.

## `valueWhile` on a slot

```pkl
new Slot {
  entityId = left.entity_id
  valueWhile = q.entity(left).gt(q.prop("state"), 0)   // default: true
  transform = "<peak since the last 0>"
  expressionValues { ["past"] = c.fetch.history(left) }
}
```

**It is a boolean input** (`nodes.BooleanInput`), the type `active`, `disabled` and `classWhen`
take, so it reuses what decides those and adds no engine of its own:

- a **constant** (`Boolean`), folded at build: `true` is the default and is not on the wire;
- a **condition** (`pred.Predicate`): a comparison on an entity, `q.entity(e).gt/lt/eq/ne(…)` or
  `stateIs`, combined with `and`/`or`/`not`, or an aggregate over `q.from(…)`. Decided by
  `Conditions` over live state, no CEL;
- a **reading** (`c.exprOf(e, "…")`, `c.expr`, a `Slot`): CEL, typed `bool`, for what a comparison
  cannot say.

It is lowered through `nodes.booleanSlot`, as `classWhen` lowers its condition: a condition becomes
a slot-scoped expression value read by a `bool` slot.

**On the wire** a slot carries it as `valueWhileSlot`, that boolean slot, absent when true. A
condition is the nested slot's own value `__while`.

**At run time**, for every slot, render and signal path alike:

1. decide `valueWhile` over live state;
2. if it does not hold, the slot's value is `""`: its transform does not run and **its fetches are
   not read**;
3. otherwise read the slot's fetches and run its transform, as today.

So a frame reads only the fetches of slots whose condition holds, in one batch, with one CEL
evaluation per slot. A fetch ask carries the condition (`SlotAsk.gate`); the page, surface and
patch pre-reads and the render key keep only the asks whose condition holds. An ask over a subject
only a paint knows stays ungated: asking too much is safe, too little raises.

**Waking:** the entities `valueWhile` names wake the slot, like any condition a slot reads.

**Signal name:** a display signal is named by what it reads, `(entity, transform)`, and shared
between cards (#134). A slot with a `valueWhile` reads more than that, so the condition is part of
its name; otherwise a card without one would go blank with it.

**Refused at build:** a `valueWhile` reading a fetch (it is decided before any fetch is read), a
reading that does not produce a `bool` (as for any boolean slot), one on a `once` slot, and a
condition naming no entity.

## The bar hides on `""`

`base/bar.pkl` hides itself (`data-show`) while its `value` or `of` is `""` or not a number, so an
`unknown` or `unavailable` reading hides it too, rather than showing today's empty track. The first
HTML still carries the track: hiding it there needs the value in the template, which the patch
form withholds. Revisit if the moment before Datastar starts shows.

## The roles set it per bar

`progressOf` already picks the bar. Each option now also says when the bar has a value:

| Found | Bar | `valueWhile` |
|---|---|---|
| percent | its own % | `running` |
| time left + total | left of total | `running` |
| charging countdown + battery | battery | `true` (Q27) |
| time left only | out of where it started, from history | `running`: history is never read while idle |

`running` (`runningOf`) is `left > 0`, else with no countdown `0 < percent < 100`, and, where a
status was found, its state in none of `idleStates`. The countdown's reading uses it too (Q28).
`Progress` carries it, so a hand-written card or a `c.iff(p.running)` reads the same rule.

## Still open

- When cel-java ships async functions (cel-expr/cel-java#1240, merged after 0.14.0), a fetch can
  become a function the transform calls only when reached. ADR 0034 records this.

## Steps

1. Done: runtime and model, `ValueWhileSuite`. Docs with it: ADR 0017, ADR 0034, the architecture
   doc, `terminology.md`, `writing-components.md`.
2. Done: the bar hides on `""`. Needs a look in the browser.
3. Done: roles, `running`, `idleStates`, the bar's and the countdown's `valueWhile`;
   `.whileRunning()` removed.
4. Done: `writing-dashboards.md`.
