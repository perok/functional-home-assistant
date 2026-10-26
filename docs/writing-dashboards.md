# Writing dashboards

This guide is for people who write dashboards in Pkl. To add a new kind of
card, see [Writing your own components](writing-components.md).

Your dashboards live in one folder. With the add-on that is
`<ha config>/fh-dashboards/`:

```
fh-dashboards/
  site.pkl        # every dashboard you serve: edit me
  components.pkl  # the cards your files import as `c`, with your defaults
  PklProject      # binds the @fh-dashboard and @fh-home names your files import
  .fh/         # written by the server on every start: don't edit
```

The card library (`@fh-dashboard`) and your entities (`@fh-home`) are
packages, not files here. The library comes with the add-on, so an upgrade
brings the new one without touching your files.

## Dashboards

- **`site.pkl` is the one entrypoint.** Every dashboard is a key in its
  `dashboards` map, and the key is the route: `["kitchen"]` serves at
  `/d/kitchen`. Any other `*.pkl` beside it is an ordinary module — it becomes
  a dashboard only when a key points at it:

  ```pkl
  dashboards {
    ["home"] { title = "Home"; card = ... }        // inline
    ["kitchen"] = import("kitchen.pkl")            // its own file
  }
  ```

  Being data, they can also be generated — a `for` over your floors gives you
  a dashboard per floor.
- `default = "<slug>"` in `site.pkl` picks what `/` serves; with none, the
  dashboard keyed `dashboard`, else the first one.
- **Every edit hot-reloads**, including adding or removing a dashboard:
  connected browsers repaint, no restart needed.
- A dashboard that fails to build serves an error page naming the problem and
  recovers the moment you fix it; the others keep serving. If `site.pkl`
  itself will not evaluate, every dashboard shows that error — the file no
  longer says what they are — and one fix restores them all.

## Your components

Every file imports the cards as `c`:

```pkl
import "components.pkl" as c
```

`components.pkl` is yours: the library's cards with your `defaults`, which every
card starts from. Set one there and it applies to every dashboard that imports
the file:

```pkl
amends "@fh-dashboard/components.pkl"

defaults {
  light { opensOn = List("colourTemp", "colour") }
}
```

Completion inside `defaults { }` lists what you can set. One card still
overrides a default with its own builder, e.g. `.opensOn(List())`.

`import "@fh-dashboard/components.pkl" as c` works too, and gets the library's
defaults instead of yours. A new workspace starts with `components.pkl`; an
older one does not, so create it with the three lines above when you want
defaults.

## Your entities

`import "@fh-home/dump.pkl" as dump` gives typed references to everything in
your Home Assistant: `dump.entities.<name>`, `dump.lights.<name>`,
`dump.areas.<name>`, `dump.floors.<name>`. It is generated from your live
entity registry, so there is no file to edit.

It also refreshes while running. When the registry changes (a device, entity,
area or floor is added, renamed or removed, or an integration is set up), the
dump is rebuilt, every dashboard that builds today is checked against it, and
only then is it swapped in. If the new dump *would* break a dashboard, the swap
is skipped and a warning is logged; fix the dashboard and refresh again from
the `/edit` editor.

## Showing whichever entities match

For a fixed list, spread it into a card's children. Every entity always
shows:

```pkl
(c.column) { children { ...dump.lights.all.map((l) -> c.entitySlider(l)) } }
```

When which ones show should follow their state, use a set instead. This is
the default dashboard's: a slider for each light that is on, a plain card for
each one that is off:

```pkl
q.from(dump.lights.all)
  .caseOf(q.eq(q.stateProp, "on"), c.entitySlider)
  .`else`(c.entityCard)
  .build()
```

- `q.from(list)` names the candidates. They are fixed when the dashboard
  builds; what follows Home Assistant live is which of them show, and in what
  order.
- `.where(…)` keeps only the ones matching. A condition on registry data, such
  as `q.prop("device_class")`, is settled when the dashboard builds; one on
  state is followed live.
- `.render(card)` gives every member the same card. `.caseOf(condition, card)`
  picks a card per member, the first match winning, and `` .`else`(card) ``
  takes the rest. A member matching no case is not shown, so `.caseOf(…)` with
  no `else` shows only the matching ones.
- The card can hold a `c.iff(…)` on another entity, which switches part of
  each member's card where a case switches all of it, so two independent
  conditions do not need four cases:

  ```pkl
  .render((t) -> (c.column) { children {
    c.entityCard(t)
    c.iff(q.entity(dump.entities.vindu).stateIs("on")).then(c.title("Window open"))
  } })
  ```
- `.orderBy(q.asc(q.prop("friendly_name")))` orders them and `.limit(n)` caps
  how many show.
- `.build()` ends the chain. The result is a card like any other, so
  `.fullWidth()` and the rest apply.

When a member changes, only its own card is redrawn.

## A device's job

`c.progress(dump.devices.washer)` shows how far along a washer, a printer or a
vacuum's run is: the time left, what it is doing, and a bar
when the device says enough for one. You never say which sensor is which: it
reads them by their names in Home Assistant.

```pkl
c.progress(dump.devices.printer)
c.progress(dump.devices.washer).label("Washer")

// only the devices that report a job
for (d in dump.devices.all.filter((d) -> c.roles.progress(d) != null)) {
  c.progress(d)
}
```

- The bar is the device's own percentage, else the time left out of the
  programme's length. A washer that reports only the time left fills out of
  where its countdown started, read from Home Assistant's history.
- When two sensors could be the same part and nothing tells them apart, neither
  is picked. A car with a charging and a climatisation countdown is two jobs,
  so start from the one you want: `c.progress(dump.entities.car_charging_time_left)`.
- A device with nothing it can read fails the build, naming its sensors.
- One part can be named by hand: `.left(…)`, `.percent(…)`, `.total(…)`,
  `.status(…)`. `c.progress(sensor)` starts from a remaining-time sensor
  alone.
- The bar and the time left show only while the job runs: while the time left
  is above 0, or, with no countdown, while the percentage is between 0 and
  100. Between jobs the card shows its name and status, and a washer's history
  is not read. A battery put on with `.percent(…)` always shows.
- To hide the whole card between jobs, wrap it in a `c.iff` on the same rule:
  `local p = c.progress(d)` and then `c.iff(p.running).then(p)`.

When yours is not found, teach it once in `components.pkl`. Each section is a
part, and each line a rule: words in the sensor's name, a `new { … }` naming the
integration or the key, or one sensor:

```pkl
defaults {
  progress {
    countdown {
      "finish in"
      new { platform = "miele"; key = "program_remaining" }
      dump.entities.washer_time_left
    }
    ignore { dump.entities.car_service_inspection_days }
  }
}
```

The sections are `countdown`, `total`, `percent`, `phase`, `status` and
`ignore`. `idleStates { "off"; "idle" }` lists the status words that mean no
job runs, whatever the numbers say, for a device that shows its programme's
length before it starts. A rule naming one sensor beats a `new { … }`, which beats words.
A rule cannot make a countdown out of a sensor that is not a duration: naming
one is an error that says so. `components.pkl` needs
`import "@fh-home/dump.pkl" as dump` to name a sensor.

## What a button can act on

A tap names its own target. `c.tap.call(service, target)` runs any HA service,
and `c.tap.lights.on/off/toggle` are the light shortcuts. The target can be:

- **an entity**: `c.tap.lights.off(dump.lights.taklys)`
- **an area or a floor**: `c.tap.lights.off(dump.areas.stue)`. HA works out
  what is in it when you press, so a light added to the room later is
  included.
- **a set** from `q.from(…).where(…)`. The press acts on the members the set
  holds at that moment, in one call. A button that turns off only the lights
  that are on:

  ```pkl
  local lit = q.from(hass.lights(dump.areas.stue.all)).where(q.eq(q.stateProp, "on"))
  c.button("Stue av", c.tap.lights.off(lit))
  ```

  If the set is empty when you press, nothing is called and the press still
  counts as done. Every possible member must be in the service's domain, so a
  `light/*` service cannot take a switch; use a `homeassistant/*` service to
  mix domains. A set that could never hold anything fails the build.

The server only runs the calls a dashboard declares, and only for someone who
can view that dashboard. A set can only ever reach the entities you listed
when you built it.

## Choosing one of several values

`c.select(…)` shows a set of values and sets the one you press. Point it at
what has the values:

```pkl
c.select(dump.entities.robot_mode)               // a select entity
c.select(dump.lights.stue.effects)               // a light's effects
c.select(dump.climates.bad.hvacModes)            // a climate's HVAC modes
c.select(dump.climates.bad.presets)              // its presets
c.select(dump.fans.vifte.presets)                // a fan's presets
```

Only what the entity actually has can be chosen: a climate that reports no
presets has no `presets`, so asking for them is an error in the editor, before
anything runs. `.options(List("eco", "comfort"))` shows a subset, in your
order, and `.style("dropdown")` or `.style("cycle")` changes the look from a
row of buttons.

## Choices each viewer makes

Some controls change what one viewer sees, not anything in Home Assistant: a
tab bar, the time window over a chart, which control a light's slider shows.
Each is a *variable*:

- **It is the viewer's own.** Two people on one dashboard each choose for
  themselves, and the choice is kept in the page's address, so a refresh or a
  bookmark keeps it.
- **Some cards come with one:** `(c.tabs) { tabs { … } }`, and a light's
  `c.entitySlider`, which switches between brightness, colour temperature and
  colour as far as the light has them. It opens on brightness;
  `c.entitySlider(l).opensOn(List(l.colourTemp, l.colour))` opens on the one
  the light is in. To make every slider do that, set it once in your
  `components.pkl`: `defaults { light { opensOn = List("colourTemp", "colour") } }`.
  `.opensOn(List())` puts one slider back on brightness.
- **A chooser** is a bar of buttons over the cards beneath it. Two come
  ready-made, each with the cards that follow it:

  ```pkl
  (c.windowChooser) { children { c.historyChart(s).fromChooser() } }

  (c.controlChooser) { children {
    q.from(dump.lights.all).render((l) -> c.entitySlider(l).fromChooser()).build()
  } }
  ```

  Press `7d` and every chart redraws at a week; press colour and every light
  shows its colour, or its brightness, greyed, if it has none.
  `.defaultingTo("7d")` picks where a chooser starts.
- **A light group** works the same way: `c.entitySlider(group)` has its own
  switch, and rows that are `.fromChooser()` follow it instead of having one
  each.
- **Your own** is a chooser over a `c.select`, read by the cards below it. It
  starts on the first option:

  ```pkl
  local view = c.variable("view")

  (c.chooser(c.select(view).options(List("simple", "detail")))) {
    children {
      c.iff(view.valueIs("detail")).then(c.historyChart(dump.sensors.ute)).`else`(c.title("Ute"))
    }
  }
  ```

  To put the select somewhere other than above its cards, declare the
  variable on any card around both, `vars { view.defaultingTo("simple") }`,
  and place `c.select(view)` where you want it.
  `c.iff(view.valueIs(…))` shows its `then` at that value and its `else` at
  any other; with no `else` it shows nothing there.
- **The nearest declaration wins.** A card that declares the same name lower
  down makes a separate choice for its own part of the page.
- **In a set, each member chooses for itself.** A variable declared in the
  card a set renders is that member's own: every light in
  `q.from(dump.lights.all).render(c.entitySlider)` switches its own control.
  A member that leaves the set and comes back, or changes card, still shows
  what was chosen for it.
- **The address names a card by its place on the page.** To keep bookmarks
  working when you add cards above it, give the card that holds the choice, or
  one around it, an id: `(c.column) { id = "lights" … }`.
- **A choice can stay out of the address**, for one a reload may forget, such
  as each light's control in a long list: `view.defaultingTo("simple").notInUrl()`.
  A link that names it still works.
