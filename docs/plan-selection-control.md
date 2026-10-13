# Plan — a selection control over a node variable (#547, step 3)

Step 3 of #547. Steps 1 (#554, call values as a JSON body) and 2 (#550, slider
modes and the pill) have landed. Three PRs, in order:

- **A** — `c.select`: one control that chooses a node variable's value, in a
  style picked per use. Usable wherever a node goes: a card's actions, a pill,
  a bar over children.
- **B** — a colour wheel in the popup, the colour mode's `"slider" | "popup" |
  "both"`.
- **C** — a hold-and-drag radial menu as another style of A, after a library
  spike.

## A — `c.select`

### What exists, and what it replaces

Three hand-rolled choosers already set a node variable through
`Variable.choose` (ADR 0033), each with its own markup:

| chooser | variable | look |
|---|---|---|
| tab bar | `tab` | a row of buttons over a baked panel |
| `WindowChooser` (`components/history.pkl`) | `window` | a row of buttons over charts |
| the slider's `ModeButton` (`components/base/slider.pkl`) | `mode` | one button stepping round |

`c.select` is the one control under all three: options in, a style out. The
slider pill moves onto it in this PR; the tab bar and the window chooser move
only if their markup survives unchanged (their visual baselines say), else
they get an issue of their own.

### Shape

```pkl
c.select(options)              // a row of buttons: the default style
c.select(options).style("dropdown")
c.select(options).style("cycle")
```

- **An option** is a value (a `varMod.Value`) with a label and an optional
  icon. `options` is a `List<Option>`; a helper builds one from a list of
  values whose label is the value.
- **The variable.** The select either declares it, as a bar over the nodes
  that read it (the `WindowChooser` shape), or chooses one declared above it
  (the slider's pill, where the slider declares `mode`):
  - `c.select(options).declares(v, children)`: declares `v` at the first
    option, or at `.initial(value)`, and wraps `children`.
  - `c.select(options).chooses(v)`: chooses `v` from below its declarer;
    the build's existing "no declarer" error covers a select with nothing
    above it.
- **`typealias SelectStyle = "buttons" | "dropdown" | "cycle"`**, with
  `"radial"` added by C. One node class, one card per style: the card registry
  reads each class's default `card`, so a style is a subclass, not a property
  switching templates per instance. `.style(s)` returns the subclass.
- **The highlight** is the option whose value equals the variable's `chosen`
  spelling (pending ask, else commit), as the tab bar and the window chooser
  do today.

### The three styles

- **buttons** — one button per option, the chosen one lit.
- **cycle** — one button showing the chosen option; a tap chooses the next.
  The slider's pill today is this: one baked `ModeButton` per mode, the panel
  picked by `mode`. That shape is kept, since it is what made the reload
  flicker go away (#550).
- **dropdown** — a `<details>`/`<summary>` showing the chosen option, opening
  a BeerCSS `menu` of the others.
  - `<details>` opens and closes with no script and works on iPadOS 15; the
    native `popover` attribute does not exist there.
  - The server renders the list, so the menu is complete on first paint.
  - A choice closes it; so does a tap outside (`data-on:click__outside`).

### Where it can sit

A select is a node, so it goes wherever a node goes, and the variable decides
what it acts on:

- **A card's actions**: on a slider's pill, a tile's actions or a bar's
  trailing slot.
- **A bar over children**: the `WindowChooser` shape, for scenes, windows or
  a filter.
- **Inside a popup.** The variable's declarer can sit outside the popup's
  surface; the chooser reads up the host's scope (ADR 0033).

### Tests

- Pkl facts: each style's options choose their values, `.declares` emits the
  declaration and `.chooses` does not, and an option outside `Value` is a build
  error.
- Behaviour: a choice from each style commits and repaints the readers, and a
  refused value is refused (`Renderer.refusals`).
- Smoke, in a browser: the dropdown opens, a choice closes it and commits; the
  cycle steps round; the slider pill works as before.
- Visual: one shot per style. The `light-slider-*` baselines must not move.

## B — the colour wheel

- **Setting:** the colour mode gets `colour: "slider" | "popup" | "both"`,
  default `"slider"`, which is today.
  - `"popup"` swaps the hue track for a pill button opening a popup with the
    wheel.
  - `"both"` keeps the track and adds the button.
- **The wheel** sets hue and saturation, so pastels become reachable, which
  the full-saturation track cannot do.
  - It is drawn as a CSS `conic-gradient` with a radial white fade.
  - A small script maps the pointer to `[h, s]` and posts `hs_color` through
    the JSON-body route (#554), on release, as the slider does.
  - Brightness stays on the slider.
- **Open:** whether the wheel comes from a library (spiked alongside C) or is
  the ~60 lines of our own that the gradient makes possible.

## C — the radial menu (spike first)

A style for phones: press and hold on the select, a ring of options appears
around the finger, drag to one and release to choose it.

- **Spike:**
  - Which library, if any. Candidates are compared on bundle size, touch and
    pointer support on iPadOS 15, and whether it can draw
    server-rendered options rather than its own model.
  - How a hold coexists with a tap (cycle) and with a page scroll.
- **Falls back** to the dropdown where there is no touch, and on a plain tap.

## Open questions

- The library for C and maybe B: the maintainer has one in mind.
- Whether the tab bar and the window chooser move onto `c.select` in A or
  later.
