# Plan: a card's inputs are read off its template

Agreed in chat on 2026-10-27 (a and b, plus the CEL result type from issue #637).

## Why

`CardDef.slots` is a hand-written list of names, checked only against the node's slot keys
(`Dashboard.scala`, `checkRef`). It never meets the template, so the two silent defects pass:

- a placeholder with no slot renders empty (`{{lable}}`);
- a slot no placeholder reads is resolved and re-rendered for nothing (`historyReadings` sets
  `entity_id`, which its template never names).

17 of 29 cards declare the list, and a slot the node sets only sometimes cannot be listed, so it
is no contract either. The region checks read the template by regex.

A slot's CEL type-checks at build (`Cel.parse` compiles, which checks), and a boolean slot must
yield `bool` (`typeErrors`). A text slot takes any result: a list, a map or a timestamp renders
as Java's `toString` (`Cel.stringify`'s last case).

## The design

### The template is the list

Validation compiles each card's template with mustache.java and walks the `Code` tree
(`getCodes`, `getName`; `ValueCode` is `{{x}}`/`{{{x}}}`, `IterableCode` `{{#x}}`,
`NotIterableCode` `{{^x}}`). `Templates` already walks it for inline regions. Slot values are
strings (a boolean for `paint`), so a name inside an ordinary section still names the node's own
slot; only a region's section opens a scope, holding `html`.

**Per card:**

- A section whose body holds `{{{html}}}` is a declared region, and a declared region's section
  holds exactly `{{{html}}}` (every library region already does). `html` appears nowhere else.
  This replaces the two regex checks.

**Per node, against its card's names:**

- Every name the template reads is a slot key, a region, or an injected var (`id`, `hostId`,
  `dashboardSlug`). `x__bind`, `x__read` and `x__signal` read slot `x`.
- Every slot is read: by the template, or by the renderer itself (`subject`, the `cell.class:` /
  `cell.style:` slots).
- Every region the node fills is one its card declares; otherwise its children never render.

**Empty is a value, absent is an error.** `{{#unit}} {{unit}}{{/unit}}` (in `historyReadings`)
adds " W" only when `unit` is non-empty: a section is how a template says a value may be empty,
and a plain `{{x}}` holding `""` renders nothing too. So a node always sets every name its
template reads, `""` when there is nothing, and a typo in a section name is caught like any
other. A Pkl `when` that leaves a slot out becomes `""`. Where a test asserts a slot is absent
(`!slots.containsKey("icon")`), it moves to `== ""` — those are listed in the PR for review.

`CardDef.slots` goes: the Pkl property, the Scala field, the 17 lists, the test fixtures'
`slots = List(…)`, `checkRef`. `injectedStatic` stays, now read by the template check.

### A text slot yields text

A CEL slot that is not boolean must type-check to `string`, `int`, `uint`, `double`, `bool`,
`null`, `optional` of one of those, or `dyn` (an attribute read is `dyn`, and cannot be refused).
A `list`, `map`, `timestamp`, `duration` or `bytes` is a build error naming the slot and the type,
pointing at `str(…)` or `string(…)`. The same holds for a variable's initial transform.

## Docs

- ADR 0006 point 7: a card carries its template; its inputs are read off it.
- `writing-components.md`: the example loses `slots { "message" }`; say every name the template
  reads must be set, `""` for nothing, and that a text slot's expression must yield text or a
  number.
- Module `CLAUDE.md`'s note on declared slots.
- Close #637 with the answer.

## Checks

- `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, benchmarks compile, CI-mode
  compile, scalafix, scalafmt; wire snapshots regenerated and the diff read.
- New tests: a placeholder with no slot, a slot no placeholder reads, a section typo, a region
  body with more than `{{{html}}}`, `html` outside a region, a node filling an undeclared region,
  a list-typed text slot, a `dyn` one passing.
