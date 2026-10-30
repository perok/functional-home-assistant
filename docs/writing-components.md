# Writing your own components

This guide is for people who want to add a new kind of card to a dashboard.
For writing the dashboards themselves, see
[Writing dashboards](writing-dashboards.md).

You do not need to change any Scala. A component is a Pkl class. You write the
HTML, say which values it needs, and the server does the rest.

---

## The idea in one minute

A component has two halves.

**The card** is the HTML, with holes in it. It is written once and shared by
every copy of the card on the page.

**The node** is one copy of that card, with the holes filled in.

The holes are called **slots**. A slot can be a fixed piece of text, or it can
read a value from a Home Assistant entity and stay up to date.

Here is the smallest component that works:

```pkl
module mycards

import "@fh-dashboard/core/node.pkl" as nodes
import "@fh-dashboard/core/slot.pkl" as slotMod

const local message = slotMod.slot("message")

class Banner extends nodes.Node {
  card = "banner"
  cardDef = new nodes.CardDef {
    template = """
      <div class="banner">\(message)</div>
      """
  }
  hidden text: String
  slots {
    message.of(text)
  }
}

function banner(t: String): Banner = new Banner { text = t }
```

Four things are going on:

- `const local message = slotMod.slot("message")` names the slot, once. Every
  other place uses `message`, so a typo is a Pkl error at that line, in the
  editor too. Give it a name none of the card's own properties has: inside the
  class, a property of the same name wins.
- `card = "banner"` is the card's name. It has to be unique across every
  component module the dashboard loads.
- `cardDef` is the shared half: the HTML, shared by every banner. `\(message)`
  writes the hole, `{{message}}`.
- `slots { message.of(text) }` is the per-copy half: what this particular
  banner says. A slot whose value is `null` is left out.

The build holds the two halves to each other. A slot placed outside every
section, like `message` here, must be one the banner sets. Wrap anything
optional: `\(icon.ifSet("<i>\(icon)</i>"))` shows the icon only when this node
sets it, and `ifUnset` is the other half. `ifTrue`/`ifFalse` test a yes/no
value instead. Where one is the other's alternative, write both in one call:
`icon.ifSetElse("<i>\(icon)</i>", "<span>\(label)</span>")`, or `ifTrueElse`. For a switch of your own card's, such as a size or a direction,
`flag.setIf(isDown)` sets the slot only while it holds, and the template reads
it with `flag.ifSet(…)`. A piece that is always there needs no section: set it as
finished text, `""` when there is nothing, as `historyReadings` sets `postfix`
to `" °C"`. And every slot you set must be placed somewhere in the template.

The `banner(...)` function at the bottom is just a friendlier way to write
`new Banner { text = "..." }`. Every shipped component has one.

## Using it

Import your module and use the card:

```pkl
import "components.pkl" as c
import "mycards.pkl" as mine

card = (c.column) {
  children {
    mine.banner("Hello")
  }
}
```

There is nothing to register: the build finds each card on the nodes that use
it. A card class may also be written in the entry itself, as a `local class`.

If your card should follow the workspace's `defaults` (set in its
`components.pkl`), have your function take them: `mine.banner("Hello",
c.defaults)`. Pkl has no way for a card to see them otherwise, which is also
why the shipped cards are handed them by `c`. Your own defaults are just your
function's parameters.

---

## Filling holes with live values

A fixed string is fine for a title. Most cards want a value from an entity.

```pkl
import "@fh-dashboard/core/slot.pkl" as slotMod

hidden entity: hass.Entity

slots {
  message.of(new slotMod.Reading {
    entityId = entity.entity_id
    transform = "state"
  })
}
```

`transform` says what to show. Leave it out and the slot shows the entity's
state. It can be a fast read from `core/simple.pkl`, which runs without an
expression engine — `simpleMod.attr("battery")`, `simpleMod.attrUnit("temperature")`
(the attribute with its unit), `simpleMod.duration(60)` — or, for a yes/no slot,
a condition such as `q.entity(door).stateIs("on")`. Either may read another
entity than the slot's: a condition names its own, and a fast read takes
`entity`, as in `(simpleMod.attr("battery")) { entity = phone.entity_id }`.

For anything more, `transform` is a small expression (CEL):

```pkl
transform = "state + ' °C'"
transform = "attr['friendly_name']"
transform = "state == 'on' ? 'Running' : 'Idle'"
```

It is checked when the dashboard is built, names and types both, so
`state + 1` fails there rather than on the page. What it gives back must be
something a card can show: text, a number or a true/false. A list or a map
fails the build; `str(…)` turns one into text.

### How often should it be read?

Every reading has a `reads` setting. It matters, so it is worth understanding.

| `reads` | What it means | Use it for |
|---|---|---|
| `live` (default) | Read every time, and a change to the entity redraws the card | A temperature, a state readout |
| `onRender` | Read every time, but changes do not redraw anything | A friendly name, a unit |
| `once` | Read one time and reused forever | Something that depends on *which* entity this is, not on its state |

The rule of thumb: if the value can change and you want to see it change, use
`live`. If it can change but nobody needs to watch it, use `onRender` — it costs
nothing. Use `once` only for things that genuinely cannot move, like a service
name worked out from the entity's domain. A renamed entity will not update a
`once` value until the whole dashboard rebuilds.

### Values that change a lot

If a value changes often, redrawing the whole card each time is wasteful. Mark
the slot as a **signal** instead, and only that one value is sent:

```pkl
(temperature) {
  value = new slotMod.Reading { entityId = entity.entity_id }
  signal = "text"
}
```

Then place both the value and its binding in the template:

```pkl
<span \(temperature.bind)>\(temperature)</span>
```

The `\(temperature)` part is what a browser sees on first load. The
`\(temperature.bind)` part is what keeps it fresh afterwards. You need both,
and the build refuses the binding inside `temperature.ifTrue(…)`: a signal
slot's value is blank between redraws, so that section would vanish with the
binding that refills it. `ifSet` is safe.

### Reading an entity's history

An expression can also read what a sensor (`hass.SensorEntity`) has recorded,
by a name you give it:

```pkl
import "@fh-dashboard/providers.pkl" as p

peak.of(new slotMod.Reading {
  entityId = entity.entity_id
  transform = "str(math.greatest(past.transformList(i, p, true, p[1])))"
  expressionValues { ["past"] = p.history.of(entity, p.history.raw(7.d)) }
})
```

`past` is a list of `[time, value]` pairs, oldest first: time in milliseconds,
value a number. `p.history.raw(…)` is every recorded reading, and HA deletes
those after `purge_keep_days` (10 by default).
`p.history.statistics(30.d, "hour")` is HA's hourly averages instead, kept for
good, one point per hour; add `.orRaw()` to read the recorded readings of a
sensor that has no statistics, or `past` is empty.

It is fetched when the slot is read — when its entity changes, or when the
card is drawn — and kept a while (a 720th of the span for readings, at least
a minute; one period for statistics), so reading it on every change costs
nothing. It does not change on its own: nothing redraws a card because
new history was written. Use `transformList(i, p, keep, value)` to filter and
map; this CEL has no `filter` or `map`.

A card of your own that takes a window the way `c.history.chart` does takes
`p.HistoryWindow` and resolves it with `p.historyWindow(w)`: either a window,
or a function handed the providers, so its users need no import.

### A value only while something holds

`valueWhile` on a reading says when it has a value at all. Until then the value
is `""`, the expression does not run, and its history is not read:

```pkl
peak.of(new slotMod.Reading {
  entityId = entity.entity_id
  valueWhile = q.entity(entity).gt(q.prop("state"), 0)
  transform = "…"
  expressionValues { ["past"] = p.history.of(entity, p.history.raw(24.h)) }
})
```

It takes what `active` and `disabled` take: `true`/`false`, a condition
(`q.entity(e).gt(…)`, `.stateIs(…)`, `q.from(…).any()`), or a reading that is a
`bool` (`slotMod.expr("int(state) > 0")`). A condition is the fast choice, and
one that names no entity reads the slot's. A reading cannot use a fetch.
Your template decides what `""` looks like; the progress bar hides.

---

## Making a card clickable

Set a tap on your node, fold its slots in, and place the click in the template:

```pkl
import "@fh-dashboard/core/tap.pkl" as tapMod

hidden tapAction: tapMod.TapAction

slots {
  ...tapMod.tapSlots(tapAction)
}
```

```pkl
<button data-on:click="\(tapMod.guardClick)\(tapMod.click)"\(tapMod.refusedAttr)\(tapMod.guard)>
  \(label)
</button>
```

That is the whole thing. You do not write any signal names, and you do not need
a `tapMod.busy.ifSet(…)` section around anything — the pieces bring their own, so you
place them whether or not this particular card guards its tap.

**Do not build the click yourself.** The slots carry more than the click: the
service and what it acts on, which the server checks every press against, and
for a call over a set, the set itself. A card that leaves them out has a
button the server refuses.

`refusedAttr` disables the control while the tap would be refused (its entity is
unavailable, say). It is for a `<button>` or other form control; on an element
that cannot be disabled, use `\(tapMod.refusedNow("fh-disabled"))` in its
`class` instead, as the tile does.

Between them they give you everything a working control needs:

- a second click is ignored while the first is still in flight
- the control dims while it waits
- if the action is refused, the control gets a red outline and a message
- the outline clears the next time you press it

If the tap is not a guarded one, both pieces come out empty and your card
renders exactly as if none of this existed.

**One exception.** If your card has an icon and you want it to spin during a
slow action, you place that yourself, in the icon's `class` (`glyph` being your
icon's slot):

```pkl
\(tapMod.busyVisual.ifSet(glyph.ifTrue(" " + tapMod.busyShapeClass)))
```

The spinner is separate because its class name comes from the theme, and only
your template knows which element holds the icon.

---

## Cards that hold other cards

A container declares **regions** instead of slots:

```pkl
cardDef = new nodes.CardDef {
  template = """
    <div class="panel">{{{children}}}</div>
    """
  regions = new Mapping {
    ["children"] = new nodes.Region {}
  }
}
```

Anything the author puts in `children` is rendered and dropped in at
`{{{children}}}`. Place each region exactly once, with three braces, and not
inside a `{{#…}}` section; the build says so if you don't. A slot cannot share
a region's name.

---

## Styling

Put your card's CSS on the card itself:

```pkl
cardDef = new nodes.CardDef {
  template = "..."
  css = """
    .banner { padding: 8px; background: var(--fh-surface); }
    """
}
```

Two rules keep your card working under any theme:

- Write **structure** — size, spacing, layout. Leave colours and fonts to the
  theme where you can.
- When you do need a colour, use the `--fh-*` variables, never a colour name
  from a CSS framework. Someone may be using a theme that never loaded it.

A theme may rename the `fh-` classes your template writes. It sees a class
written in the template text, and a class held in a slot made with
`slotMod.cssClass("size")` instead of `slotMod.slot(…)`. Use that for any slot
you place inside `class="…"`, and put a whole class name in it
(`size.of("fh-size-small")`), not a piece the template completes.

---

## Escaping: two kinds of hole

- `\(name)` (`{{name}}`) escapes the value. Use this for anything a person
  typed or anything that came from Home Assistant.
- `name.raw` (`{{{name}}}`) does not escape. Use it only for values that are
  meant to be markup or code — a URL you built, a click expression. A region
  is always placed this way.

When in doubt, use two braces.

---

## Things that will trip you up

These are real, and each one has cost somebody an afternoon.

**Keep every HTML attribute on one line.** You can break lines between tags
freely, but a line break *inside* an attribute value breaks it.

**Put parentheses around anything you amend.** This is a Pkl rule, not ours:

```pkl
(c.column) { children { ... } }   // works
c.column { children { ... } }     // parse error
```

**Never name a parameter after the property it sets.**

```pkl
function make(items: Listing) = new Thing { items = items }   // infinite loop
function make(xs: Listing)    = new Thing { items = xs }      // fine
```

In the first one, `items` on the right refers to the object's own `items`, not
to your parameter. Pkl recurses about ten thousand levels deep and the error
message points at the wrong place entirely.

**A name your file did not write is invisible in nested code.** A property your
file only inherits (from the module it amends) works at the top level and fails
inside `{ … }` with "Cannot find property", and the editor shows no error. Import
what you use (`import "components.pkl" as c`) rather than relying on an inherited
name.

**Some words are reserved**: `case`, `out`, `is`, `import`, `else`, `when`.
Pick another name rather than escaping them with backticks — backticks read
badly wherever the name is used.

---

## Checking your work

```bash
sbt 'fh-datastar-view/testOnly *PklLibraryTestSuite'   # fast, no browser
sbt 'dashboardServe <your-workspace>'                  # then look at it
```

The Pkl suite catches structural mistakes in seconds. Anything about how a card
*looks* needs a browser — open the dashboard and check.
