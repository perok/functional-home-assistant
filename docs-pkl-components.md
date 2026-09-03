# Writing your own components

This guide is for people who want to add a new kind of card to a dashboard.

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

class Banner extends nodes.Node {
  card = "banner"
  cardDef = new nodes.CardDef {
    template = """
      <div class="banner">{{message}}</div>
      """
    slots { "message" }
  }
  hidden text: String
  slots {
    ["message"] = text
  }
}

function banner(t: String): Banner = new Banner { text = t }
```

Three things are going on:

- `card = "banner"` is the name. It has to be unique across every component
  module the dashboard loads.
- `cardDef` is the shared half: the HTML and the list of slot names it uses.
- `slots { ["message"] = text }` is the per-copy half: what this particular
  banner says.

The `banner(...)` function at the bottom is just a friendlier way to write
`new Banner { text = "..." }`. Every shipped component has one.

## Using it

Add your module to `componentModules` in your entry file:

```pkl
import "@fh-dashboard/components.pkl" as c
import "mycards.pkl" as mine

componentModules { mine }

card = (c.column) {
  children {
    mine.banner("Hello")
  }
}
```

You have to name your module explicitly. Pkl cannot look at your imports and
find your cards by itself. If you forget, you get a clear "card not found" error
when the dashboard builds — not a broken page later.

Your cards must be in their own file, not written inline in the entry. Classes
in an entry file have to be `local`, and `local` classes are invisible to the
part of Pkl that collects cards.

---

## Filling holes with live values

A fixed string is fine for a title. Most cards want a value from an entity.

```pkl
import "@fh-dashboard/core/slot.pkl" as slotMod

hidden entity: hass.Entity

slots {
  ["message"] = new slotMod.Slot {
    entityId = entity.entity_id
    transform = "state"
  }
}
```

`transform` is a small expression (CEL) that turns the entity into the text you
want. `"state"` is the default and means "whatever the entity's state is". You
can do more:

```pkl
transform = "state + ' °C'"
transform = "attr['friendly_name']"
transform = "state == 'on' ? 'Running' : 'Idle'"
```

### How often should it be read?

Every slot has a `reads` setting. It matters, so it is worth understanding.

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
["temperature"] = new slotMod.Slot {
  entityId = entity.entity_id
  signal = "text"
}
```

Then place both the value and its binding in the template:

```html
<span {{{temperature__bind}}}>{{temperature}}</span>
```

The `{{temperature}}` part is what a browser sees on first load. The
`{{{temperature__bind}}}` part is what keeps it fresh afterwards. You need both.

---

## Making a card clickable

Set a tap on your node, and place two holes in the template:

```pkl
hidden tapAction: tapMod.TapAction

slots {
  ["onclick"] = tapAction.onclick
  when (tapAction.busy) {
    ["busy"] = "1"
  }
  when (tapAction.busy && tapAction.busyVisual) {
    ["busyVisual"] = "1"
  }
}
```

`when` adds the entry only if the condition holds, which is how every shipped
card does it. An absent slot and an empty one mean the same thing here, but
`when` reads better.

```html
<button data-on:click="{{{fh_guard_click}}}{{{onclick}}}" {{{fh_guard}}}>
  {{label}}
</button>
```

That is the whole thing. You do not write any signal names, and you do not need
an `{{#busy}}` section around anything.

The server fills those two holes for you with everything a working control
needs:

- a second click is ignored while the first is still in flight
- the control dims while it waits
- if the action is refused, the control gets a red outline and a message
- the outline clears the next time you press it

If the tap is not a guarded one, both holes come out empty and your card renders
exactly as if none of this existed.

**One exception.** If your card has an icon and you want it to spin during a
slow action, you place that yourself:

```html
{{#busyVisual}}{{#glyph}} \(tapMod.busyShapeClass){{/glyph}}{{/busyVisual}}
```

The spinner is separate because its class name comes from the theme, and only
your template knows which element holds the icon.

---

## Cards that hold other cards

A container declares **regions** instead of slots:

```pkl
cardDef = new nodes.CardDef {
  template = """
    <div class="panel">{{#children}}{{{html}}}{{/children}}</div>
    """
  regions = new Mapping {
    ["children"] = new nodes.Region {}
  }
}
```

Anything the author puts in `children` is rendered and dropped in there.

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

---

## Escaping: two kinds of hole

- `{{name}}` escapes the value. Use this for anything a person typed or
  anything that came from Home Assistant.
- `{{{name}}}` does not escape. Use it only for values that are meant to be
  markup or code — child HTML, a URL you built, a click expression.

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

**Some words are reserved**: `case`, `out`, `is`, `import`, `else`, `when`.
Pick another name rather than escaping them with backticks — backticks read
badly wherever the name is used.

---

## Checking your work

```bash
sbt 'fh-datastar-view/testOnly *PklLibraryTestSuite'   # fast, no browser
sbt dashboardServe                                     # then look at it
```

The Pkl suite catches structural mistakes in seconds. Anything about how a card
*looks* needs a browser — open the dashboard and check.
