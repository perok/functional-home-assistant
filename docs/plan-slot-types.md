# Plan: what a slot takes

A slot's type says only `text` or `bool`, and a fetch slot gets around it. With no step its answer
is placed as JSON; with a chart step it is SVG, which `fits` counts as text. Three rules exist only
for that: `typeErrors` skips every fetch slot, `fetchErrors` picks the hole (escaped or raw) by
whether the step draws, and a `valueWhile` that is a bare fetch is refused by a rule of its own.
Every other slot in a raw hole is not checked at all; a live `onclick` handler is one today.

**State:** steps 1–4 built, awaiting review.

## Design

- **A slot's type names its hole, and is declared.** `text` (the default) is placed `{{x}}`,
  `textRaw` is placed `{{{x}}}`, `bool` is a section. Pkl declares it; `validate` checks every place
  the template puts the slot against it, for every slot, not only a fetch's. Removing escaping is
  always written down: a slot is `textRaw` only where a card says so.
- **Implicit toString, only where a value meets its slot.** There, text, a number, a bool and
  `dyn` become text without a step, and fit `text` and `textRaw` (a bool prints `true` or `false`,
  which a client library can read). `TextRaw` (today's `Markup`) fits `textRaw` only. `Json`, a
  series, a list and a whole entity fit no slot. Between steps nothing converts: each step takes
  the types it names (a `prefix` takes a number or text, a `not` a bool), as today.
- **Every conversion is a step with a fixed type.** `json`: a fetch's series to `Json` (its
  provider's answer, as the passthrough stage sends it today). `jsonText`: `Json` to text, its
  format options to come. `chart`: a series to `TextRaw`. JSON for a raw hole (a `<script>`) will
  be a step of its own, `jsonRaw`, `Json` to `TextRaw`, rather than a mode on `jsonText`, so no
  step's type depends on its options; it lands with its first reader, and a comment beside the
  `json` stage says so.
- **A fetch slot's steps:** one stage (`json` or `chart`), then `jsonText` after `json`. A CEL
  input asks the `json` stage, so `SlotAsk.stage` is no longer optional.
- **What goes:** the fetch-slot skip in `typeErrors`, the hole branch of `fetchErrors` (the slot
  type's hole check covers it), the `valueWhile` fetch rule (a series fits no `bool` slot), `Markup`
  and the passthrough with no stage.
- **What is given up:** a bool reading in a text slot was a build error ("yields a bool, which a
  card cannot show"); it now prints `true` or `false`.

## Who moves

The slots placed raw in the wire snapshots, each becoming `textRaw`: `onclick` on `button`,
`pill`, `tab` and `tile` (often live CEL); `service` on `colourWheel` and `sliderHead`; `active`
on `tab` (a literal Datastar expression); `chart` on `historyChart`. `href` on `button` and
`pill`, a navigating tap's URL, was raw too; it is placed escaped instead, and `taps` gains a
navigate button so a fixture covers it. `historyReadings` writes
`json` then `jsonText`. Most come from shared library modules, so most dashboards' wire
snapshots move; the goldens must not.

## Steps

1. Types: `textRaw`, `TextRaw`, `Json`, the steps, `fits` with implicit toString only, the hole
   check for every slot, the three rules deleted.
2. Pkl: `core/slot.pkl`'s `SlotType`, the raw slots above, `historyReadings`, the chart stage;
   wire snapshots regenerated and read, goldens unchanged.
3. Tests: each slot type against each hole, each value type against each slot type, a bool shown
   as text but refused by a step that takes text, a series refused in every slot, `jsonText`
   placed.
4. Docs: ADR 0034 ("Boolean slots, and what fills one"), ADR 0035's types, ADR 0031's stages,
   `writing-components.md`, the terminology. Bench: `validated` timing, one round.
