# Plan — a selection control (#547, step 3)

Step 3 of #547. Steps 1 (#554, call values as a JSON body) and 2 (#550, slider
modes and the pill) have landed. Three PRs, in order:

- **A** — `c.select`: one control that chooses a value from a set, in a style
  picked per use. The value is a node variable's or an entity's. Usable
  wherever a node goes: a card's actions, a pill, a bar, a popup.
- **B** — a colour wheel in the popup, the colour mode's `"slider" | "popup" |
  "both"`.
- **C** — a hold-and-drag radial menu as another style of A, on Orbit.

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

That needs any node to be able to declare, and a declarer used to write three
attributes in its own template: the pending ask's seed, its clear, and the URL
mirror. So, first:

1. **The shell holds every ask, the cell every mirror.** The page shell seeds
   each pending ask beside its committed value and clears them all in one
   effect; the renderer puts `data-fh-url:<name>` on the declarer's
   `.fh-cell`. Done, with the template copies still in place.
2. **The template copies go**, from the tab bar, the window chooser and the
   slider. Waiting on the maintainer: two existing tests pin the template seed
   (`PklDashboardBehaviourSuite` "a re-revealed bar…", `ActionSignalNamesSuite`'s
   group regex) and three pin the unkeyed mirror (`VarSignalNamesSuite`,
   `PklDashboardBehaviourSuite`'s window regexes).

### Styles

`typealias SelectStyle = "buttons" | "dropdown" | "cycle"`, with `"radial"`
added by C. `.style(s)` returns a subclass, one card per style: the card
registry reads each class's default `card`, so one class cannot switch
templates per instance.

- **buttons** (the default) — one button per option, the chosen one lit.
- **cycle** — one button showing the chosen option; a tap chooses the next.
  The slider pill is this today, one baked `ModeButton` per mode with the
  panel picked by `mode`. That shape is kept, since it is what made the reload
  flicker go away (#550).
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
| the slider's `ModeButton` (`components/base/slider.pkl`) | `mode` | one button stepping round |

The tab bar and the window chooser move only if their markup survives
unchanged (their visual baselines say); otherwise each gets an issue.

The slider pill does NOT move yet, a question for the maintainer. Its buttons
are baked, one panel per mode selected by `mode`, which is what keeps the
right button in the first HTML. A bake group is selected by a member INDEX
(`Activation.Var`, `Renderer.refusals`), while a select's values are names
(`7d`), so `c.select`'s cycle shows its buttons with `data-show` instead: all
hidden in the first HTML, the chosen one shown once Datastar reads the
committed value. Moving the pill onto it brings back a blank pill until the
script runs. The fix is a bake group selected by value, a runtime change.

### Status

- Done (`components/select.pkl`): `c.variable(name)`;
  `c.select(target).options(…).style(…)` over a node variable, a `select`
  entity (the dump now carries its `options`) and a light's `effects`, in all
  three styles.
  - Over an entity, each option is an `ActiveNode` lit by the server, so the
    first HTML is right in every style, the cycle included.
  - The server refuses a choice of a variable that nothing on the page
    reads, so a variable select needs a reader (a chart's `.over(v.ref)`, a
    bake group).
  - Tests: Pkl facts (`select.test.pkl`), the real build and a write
    (`SelectSuite`), a browser smoke test (`SelectSmokeSuite`).
- Not yet in the library's card index (`components.pkl`'s `modules`), which
  `PklBuildSuite` pins: adding it is an edit to that existing test.
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

- **Setting:** the colour mode gets `colour: "slider" | "popup" | "both"`,
  default `"slider"`, which is today.
  - `"popup"` swaps the hue track for a pill button opening a popup with the
    wheel.
  - `"both"` keeps the track and adds the button.
- **The wheel** sets hue and saturation, so pastels become reachable, which
  the full-saturation track cannot do.
  - The pointer maps to `[h, s]`, posted as `hs_color` through the JSON-body
    route (#554) on release, as the slider does.
  - Brightness stays on the slider.
  - Drawn with Orbit if its arcs fit, else a CSS `conic-gradient` with a
    radial white fade.

## C — the radial menu, on Orbit

A style for phones: press and hold on the select, a ring of options appears
around the finger, drag to one and release to choose it.

- **The library is [Orbit](https://zumerlab.com/orbit-docs/)** (`@zumer/orbit`,
  MIT, no dependencies). It is CSS plus a small runtime with custom elements,
  and it watches the DOM, so a server-rendered ring survives a Datastar
  repaint. It lays the ring out and draws arcs; the hold-and-drag gesture is
  ours.
  - It is also the candidate for later radial views, a thermostat's dial
    among them.
- **Spike first:**
  - whether anything is cleaner than Orbit for the ring;
  - how a hold coexists with a tap (cycle) and with a page scroll.
- **Older browsers:** a radial part may not render on an old tablet (iPadOS
  15). That is accepted; a plain tap still gives the dropdown.

## Open questions

- Whether the tab bar and the window chooser move onto `c.select` in A or
  later.
