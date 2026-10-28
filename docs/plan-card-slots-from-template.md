# Plan: a card's inputs are read off its template

Agreed in chat on 2026-10-27 (a and b, plus the CEL result type from issue #637).

**State:** built; review, then delete this file.

## Why

`CardDef.slots` was a hand-written list of names, checked only against the node's slot keys. It
never met the template, so the two silent defects passed:

- a placeholder with no slot renders empty (`{{lable}}`);
- a slot no placeholder reads is resolved and re-rendered for nothing.

17 of 29 cards declared the list, and a slot set only sometimes could not be listed. The region
checks read the template by regex.

A slot's CEL type-checks at build (`Cel.parse` compiles, which checks), and a boolean slot must
yield `bool`. A text slot took any result: a list or a map rendered as Java's `toString`.

## The design

### The template is the list

`Templates.card` compiles a template once, theme classes applied, with the rendering factory, and
walks that `Code` tree; `Validated` carries the compiled templates to the renderer, as it carries
the compiled CEL, so what is checked is what renders and nothing compiles twice (about 0.6 ms for
the library's 16 templates). Slot values are strings, so a name inside an ordinary section is
still the node's own; only a region's section opens a scope, holding `html`.

**Per card:** a section holding `{{{html}}}` is a declared region; a declared region's section is
exactly `{{{html}}}`; `html` appears nowhere else; the template compiles. This replaces the three
regex checks: the two on regions, and whether a fetch slot sits in a raw hole, which the tree
answers too.

**Per node:**

- A name outside every section is a slot, a region, an injected var (`id`, `hostId`,
  `dashboardSlug`), or `bakeIndex` on a card with a baked region. `x__bind`, `x__has`, `x__read`
  and `x__signal` read slot `x`.
- **A name inside a section may be absent: the section is how a template says "optional".** The
  library composes `{{#service}}…{{targetId}}…{{/service}}{{^service}}{{{onclick}}}{{/service}}`,
  so a name's guard is not always a section of its own name, and `x__has` exists to ask whether
  `x` is set. Requiring every name was measured first: it failed on most cards.
- Every slot is named in the template, or read by the server itself (`subject`, the live cell
  slots, a tap's route slots, which `Dashboard.calls` reads). This also catches a misspelt section,
  since the slot it meant goes unread.
- Every region the node fills is one its card declares.

`CardDef.slots` is gone: the Pkl property, the Scala field, the 17 lists, the test fixtures'
arguments, `checkRef`.

**What the checks found:** `tab` and `selectEntityOption` set `tappable`, the colour wheel
`busyVisual`, and none reads it (each now takes only the tap slots its template reads); one
`RenderInputsSuite` fixture filled a region its card lacks, so its child never rendered.

**No magic optional pieces in the library:** `historyReadings` set a raw `unit` and spliced the
space in with `{{#unit}} {{unit}}{{/unit}}`. Its slot is now the finished `postfix` (`" °C"`, or
`""`), placed bare. A section stays the way to say "optional"; a value a card always has is set,
`""` for nothing.

### A text slot yields text

A CEL slot that is not boolean must type-check to `string`, `int`, `uint`, `double`, `bool`,
`null`, `optional` of one of those, or `dyn` (an attribute read is `dyn`). A `list`, `map`,
`timestamp`, `duration` or `bytes` is a build error pointing at `str(…)`.

## Docs

- ADR 0006 point 7, `writing-components.md` (the example; optional names; what a transform may
  return), module `CLAUDE.md`, `core/node.pkl`'s `CardDef` docs.
- The PR closes #637.

## Checks

- `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, benchmarks compile, CI-mode
  compile, scalafix, scalafmt; wire snapshots regenerated (only `slots` lists and `tappable` go).
- New: `TemplateReadsSuite`; `BuildPhaseSuite` for an unread slot, a misspelt section, derived and
  server-read names, a region section's shape, a stray `{{{html}}}`, an undeclared region, a list or
  map text slot, `dyn` passing.
