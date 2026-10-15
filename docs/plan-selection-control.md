# Plan — a selection control (#547, step 3)

Step 3 of #547. Steps 1 (#554, call values as a JSON body) and 2 (#550, slider
modes and the pill) have landed. Three PRs, in order:

- **A** — `c.select`: one control that chooses a value from a set, in a style
  picked per use. The value is a node variable's or an entity's. Usable
  wherever a node goes: a card's actions, a pill, a bar, a popup.
- **B** — a colour wheel in the popup, the colour mode's `"slider" | "popup" |
  "both"`.
- **C** — a hold-and-drag radial menu as another style of A, our own ring
  with no library.

## A — `c.select`

### The target decides

A select chooses one value from a set. What it chooses FOR is its target,
the first argument, and the target decides the three things that differ:

| | a node variable (a view choice) | an entity's value |
|---|---|---|
| a choice posts | `sse/var/…`, as `Variable.choose` does | a service call with a JSON body (#554): `select.select_option {option}`, `light.turn_on {effect}` |
| the chosen one is | the variable's pending ask, else its commit | the entity's state or attribute; pending until HA confirms (ADR 0025), as a slider's value is |
| the options come from | the author's list | the dump: `options`, `effect_list` |

```pkl
c.select(windowVar, List("1h", "24h", "7d")).style("dropdown")   // a view choice
c.select(house.select.robot_mode)                                // options from HA
c.select(lamp.effect).style("cycle")
```

The entity half covers `select`/`input_select` and a light's effects in A.
`SelectEntity` has no `options` yet, so the dump grows it. Climate and fan
modes (`hvac_modes`, `preset_modes`) follow the same pattern later.

An option is a value with a label and an optional icon. An author can filter
and relabel an entity's options. A variable's options are `varMod.Value`s,
since they travel in a route segment; an entity's travel in the JSON body,
so any string HA offers is fine.

### A select only chooses

A select never declares the variable it chooses. The node that holds the
readers declares it, with `vars = v.declare(…)`, as the slider already does
for `mode`. That is one way to declare a variable, not two, and the build's
existing "no declarer" error covers a select with nothing above it. The
window chooser stays a small convenience built from the two.

That needs any node to be able to declare, so a declarer's template carries
nothing for its variables (done): the page shell seeds each pending ask beside
its committed value and clears them all in one effect, and the renderer puts
`data-fh-url:<name>` on the declarer's `.fh-cell`. The tab bar, the window
chooser and the slider lost their own copies of the three.

### Styles

`typealias SelectStyle = "buttons" | "dropdown" | "cycle"`, with `"radial"`
added by C. `.style(s)` returns a subclass, one card per style: the card
registry reads each class's default `card`, so one class cannot switch
templates per instance.

- **buttons** (the default) — one button per option, the chosen one lit.
- **cycle** — one pill showing the chosen option, in its look (an icon
  replaces the label); a tap chooses the next. Over a variable one pill per
  option is baked, the shown one picked by the value: the shape that made the
  slider's reload flicker go away (#550). The slider's mode switch is this.
- **dropdown** — a `<details>`/`<summary>` showing the chosen option, opening
  a BeerCSS `menu` of the others.
  - `<details>` opens and closes with no script and works on iPadOS 15; the
    native `popover` attribute does not exist there.
  - The server renders the list, so the menu is complete on first paint.
  - A choice closes it; so does a tap outside.

### What moves onto it

Three hand-rolled choosers set a node variable today, each with its own
markup:

| chooser | variable | look |
|---|---|---|
| tab bar | `tab` | a row of buttons over a baked panel |
| `WindowChooser` (`components/history.pkl`) | `window` | a row of buttons over charts |
| the slider's mode button (`components/base/slider.pkl`) | `mode` | one button stepping round (moved: the mode switch) |

The tab bar and the window chooser move only if their markup survives
unchanged (their visual baselines say); otherwise each gets an issue.

A bake group is selected by VALUE (done, its own PR stacked on A): each
member names the value of its variable that shows it, defaulting to its index,
which is what tabs choose by. Over a variable,
`c.select`'s cycle and its dropdown's label are bake groups, so the first HTML
shows the viewer's option, as the slider pill does. The buttons style is lit by
the client, as a tab bar is.

The slider's mode switch is a `c.select` of `mode` in every style (done, on its
own PR stacked on #573), built from `c.modeSwitch`, a value among the slider's
buttons and their default list: `c.modeSwitch.modes(List(…)).style(…)`. The
switch carries the modes, since a head it cannot reach is never shown; fewer
than two on the light and it shows nothing (one left is that axis), and buttons
with no switch are the plain row. Its heads are chosen by mode name
(`?v.<id>.mode=colourTemp`), not index. `.modes`, `.single` and `.modeStyle`
left the slider, and the baked `ModeButton` and `c.select`'s own cycle button
both became `c.pill`.

### Status

- Done (`components/select.pkl`): `c.variable(name)`;
  `c.select(target).options(…).style(…)` over a node variable, a `select`
  entity (the dump now carries its `options`) and a light's `effects`, in all
  three styles.
  - Over an entity, each option is an `ActiveNode` lit by the server, so the
    first HTML is right in every style, the cycle included.
  - The server refuses a choice of a variable that nothing on the page
    reads. A cycle or dropdown is a reader of its own; buttons need one (a
    chart's `.over(v.ref)`, a bake group).
  - Tests: Pkl facts (`select.test.pkl`), the real build and a write
    (`SelectSuite`), a browser smoke test (`SelectSmokeSuite`).
- In the library's card index (`components.pkl`'s `modules`).
- `input_select` has no entity class yet; it follows `select` once it does.

### Tests

- Pkl facts:
  - each style's options choose their values, for both kinds of target;
  - an entity's options come from its dump;
  - a variable option outside `Value` is a build error.
- Behaviour:
  - a variable choice commits and repaints its readers, and a refused value
    is refused (`Renderer.refusals`);
  - an entity choice posts its service with its body, and the allowlist
    admits only the declared option key.
- Smoke, in a browser: the dropdown opens, a choice closes it and commits;
  the cycle steps round; the slider pill works as before.
- Visual: one shot per style. The `light-slider-*` baselines must not move.

## B — the colour wheel

- **Shape:** the wheel is a view tap, `c.view.colourWheel(l.colour)`, opening
  it in a popup. On a slider it is one of the buttons; dropping the hue track
  is `c.modeSwitch.modes(…)` without `"colour"`. It takes the capability group, so a light
  without colour is an editor error.
- **A slider's buttons** (decided with the maintainer): `.buttons(List(…))` of
  taps and pills, in the author's order, `c.modeSwitch` being the mode
  switch. A tap carries its own look (icon, accessible label, what lights it),
  so a bare tap is a pill; `c.pill(tap)` with `.label`/`.icon`/`.noIcon`
  overrides it, round when an icon or an emoji is all it shows. A null in the
  list is no button, so one list serves lights with and without a capability:
  `l.colour.ifNonNull((col) -> c.view.colourWheel(col))`. pkl-lsp types `col`
  as `NonNull`, and narrows no `!= null` either, so neither form is checked in
  the editor; the evaluator checks both. `c.tap` is
  what acts on HA, `c.view` what only changes the screen. An `ns` lambda bound
  to the entity was rejected: it is ADR 0013's entity-first builder, with no
  editor check for a capability. Naming of `c.view` and `c.pill` is open;
  `c.modeSwitch` keeps its name (it is no tap, so not `c.view`).
- **Status:** the wheel done on its own branch, stacked on A
  (`components/wheel.pkl`), as a CSS `conic-gradient` with a radial white
  fade, no library; the pointer handlers are Datastar expressions over a small
  `fhWheel` card script. The buttons on a branch stacked on it.
- **The wheel** sets hue and saturation, so pastels become reachable, which
  the full-saturation track cannot do.
  - The pointer maps to `[h, s]`, posted as `hs_color` through the JSON-body
    route (#554) on release, as the slider does.
  - Brightness stays on the slider.

## C — the radial menu

A style for phones: press and hold on the select, a ring of options appears
around the finger, drag to one and release to choose it.

**First spike (reading Orbit's source, 1.5.0):**

- **What Orbit needs from the browser.** Its satellites are placed with CSS
  `sin()`/`cos()` (Safari 15.4+, Chrome 111+); without them it paints an
  "update this browser" box. Its arcs and progress rings also need container
  style queries (`@container style(…)`, Safari 18+). Its own size: about 43 KB
  of JS source plus its SCSS.
- **A menu ring needs none of that.** The options and their count are known
  at build time, so the server can place each one with a plain transform,
  `rotate(a) translate(r) rotate(-a)`, which works on iPadOS 15 too. The only
  script is the gesture: a hold opens the ring, a drag picks the nearest
  option by angle, a release chooses it. That is the same few dozen lines with
  or without a library.
- **Decided:** C as our own ring, with no dependency. Orbit stays the
  candidate for what it is built for, the arcs: a thermostat dial, gauges.
- **Still to try in a browser:** how a hold coexists with a tap (the cycle)
  and with a page scroll (`touch-action`, a hold threshold).

## Open questions

- Whether the tab bar and the window chooser move onto `c.select` in A or
  later.
