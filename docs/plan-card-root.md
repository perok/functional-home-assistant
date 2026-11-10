# Plan: the card writes its root

Part of the regions work (`docs/plan-regions.md` on branch `regions`, step C), done ahead of it.
Today the renderer wraps every node in a `<div class="fh-cell" id="…">` it writes itself
(`Renderer.cellOpen`, `wrapperInto`, ADR 0008), so every card is two elements, a set's members
cannot be `<li>` or `<tr>` (a `<div>` between `<tbody>` and `<tr>` is moved out of the table by the
HTML parser), and the tab anchors opt out with `wrapAsCell = false`.

**State:** step 1 built (ccad7d42); step 2 next.

## Design

The wrapper moves into the card's own root element, which the library writes from three
`CardDef` fields:

```pkl
cardDef { tag = "a"; classes = "fh-tab"; attrs = #"data-on:click="…""#; template = "\(label)" }
```

Pkl lowers it to the wire's one template,
`<a class="fh-cell fh-tab{{{cellClasses}}}"{{{cell}}} data-on:click="…">{{label}}</a>`: `fh-cell`,
the card's classes, then the node's cell classes, the order `groupOpen` writes today. Both holes
are writer holes (`Templates.writerHoles`, as a region is), written straight into the buffer:

- `cellClasses`: each with its leading space, a live one inline in the document form only, as
  `cellClassesInto` does;
- `cell`: the document form's live `style`, the id, the `data-signals` seed, the cell bindings and
  the URL mirror, the values `wrapperInto` writes today, in today's order.

The template is the root's content; the theme's class rewrite (`ThemeClasses.rewriteTemplate`)
reaches the root as any card markup (its `Token` pattern matches `fh-cell` before `{{{`).

- Every ADR 0008 guarantee holds: the library writes the root, so the id cannot be forgotten, the
  node has exactly one root, and every root carries `fh-cell`. What ADR 0008 rejected (an id the
  author places on the root) stays rejected.
- `attrs` is a Pkl type constraint, by attribute name (the lookbehind `ThemeClasses.ClassAttr`
  uses, so `data-class="{…}"` is not `class`): no `id`, `class`, `style` or `data-signals`, which
  `cell` writes, and the browser drops a second one silently. `tag` is a plain element name. A
  node refuses, in Pkl, a `cellClassWhen(x)` where its card's `attrs` binds `data-class:x`.
- `layoutCell = false` replaces `wrapAsCell`: the root keeps `cell` and loses the `fh-cell` class,
  for a root that must not be a layout box. The tab anchors need it: in a `.fh-col` they would
  inherit `--fh-cell-width:100%`. The layout builders (`columns`, `fullWidth`, `hug`, `grow`,
  `stick`) write a field of their own, `cell.layout`, written ahead of the other cell classes, and the build refuses a non-empty `layout` on
  such a card; `cellClassWhen` and `cellStyle` stay allowed. `wrapAsCell`'s other two refusals
  (live slots, use as a set clause) go, since the root still has its id.
- A card that sets no `tag` gets a `div` root with no classes and no attributes, which is today's
  wrapper byte for byte. A user's own cards keep working.
- `tag`, `classes` and `attrs` do not reach the wire; `CardDef` loses `wrapAsCell` and gains
  `layoutCell`, and `Cell` gains `layout`.
- `Renderer`'s set root (`groupOpen`) stays until the regions work makes the set a card.

## Steps

1. **The mechanism, byte-identical.** The fields, the two writer holes, `layoutCell`, `cell.layout`,
   the Pkl constraints; `Renderer` stops wrapping (`cellOpen`; `wrapperInto` becomes the `cell`
   hole's writer). `TabButton` moves to `tag = "a"`, `layoutCell = false`. Goldens byte-identical
   except the tab anchors, which gain `class=""` and their id (kept: every root gets the class
   attribute, so `cellClassWhen` has a place on a `layoutCell = false` root); wire snapshots regenerated deliberately.
   Bench `tickRender` and a page render, before and after: allocation first, with
   `-XX:-DoEscapeAnalysis` on both sides.
2. **One commit per card family** moving its root into the fields: `layout.pkl` (Grid, Row,
   Column), `components/base/` (button, onoff, tile, bar, slider, text, surface), `select.pkl`,
   `moreinfo.pkl`, `history.pkl`, `wheel.pkl`, `core/surface.pkl`, `core/text.pkl` where it is a
   card. Each golden diff must show only a `div` going and its classes and attributes joining the
   card's root. The view tests and CI's visual snapshots are the check, since the bytes move.

## Docs

ADR 0008 rewritten in place (the cell is the card's root, written by the library; the rejected
alternative stays rejected), ADR 0020 (theme classes reach the root through the template),
`docs/architecture-rendering-pipeline.md` (the wrapper), `docs/terminology.md` (cell),
`docs/writing-components.md` (the root fields, `layoutCell`), `theme.pkl`'s note on `fh-cell`, and
the module `CLAUDE.md` lines on "every node is a cell" and `wrapAsCell`.

## Done when

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` is green, `git grep wrapAsCell` finds
nothing, the bench shows no allocation increase per render, and CI's visual snapshots are
unchanged.
