# Plan — a light's axes in its domain, and what its slider opens on

Two problems with one cause: the slider knows the light domain by hand.

1. **It opens on brightness whatever the light is doing.** A lamp left on a colour opens on the
   brightness head, and the viewer cycles to colour to see it. Asked for: a setting on the slider,
   `.opensOn(…)`, giving the order to try.
2. **The axes are spelled in `components/slider.pkl`**, as `if (on is hass.ColourTemp) … else if
   (hue) …` for the service, key, range and attribute, and `availableControls` builds the light's
   list by name. A cover's tilt, a fan's direction or a media player's volume would each add
   another branch to a card that should know no domain.

## The shape

### `hass.Axis`: one class in `hass.pkl` that the capability groups extend

```pkl
/// One value of an entity that a slider can drive: where it is read, how it is
/// written, its range, and, where the entity has several, when it is the one in use.
abstract class Axis {
  owner: Entity
  /// Its main-control name, which is also its value in the slider's `control` variable.
  name: String
  attr: String
  service: String
  key: String
  min: Int
  max: Int
  /// When this is the axis the entity is on now: a live attribute among values. Null is
  /// always.
  inUseWhen: AttrIn?
}

class AttrIn { attr: String; values: List<String> }
```

- `ColourTemp`, `Colour` and a new `Brightness` extend it, each filling the fixed fields
  from the vendored constants. `ColourTemp.inUseWhen` is `color_mode` in `["color_temp"]`, and
  `Colour.inUseWhen` is `color_mode` in `hassLight.COLOUR_MODES`. `Brightness.inUseWhen` is null.
- `LightEntity.brightness: Brightness?` is null unless `supportsBrightness`, like the other
  groups. It shares HA's name, which took two changes:
  - `q.prop` used to read any property of a name as a registry fact, so this one hid HA's live
    `brightness`. A name in `volatileAttrs` is now live, and only a scalar is a fact (ADR 0003).
  - The dump writes a carried attribute as a property of its own name. It now drops
    `brightness` on lights, as it already drops `supported_color_modes`.
- `LightEntity.axes: List<Axis>` lists the non-null ones in HA's order: brightness, temperature,
  colour.
- The hue's two quirks stay hue-specific, not generic: it is read at `hs_color[0]`, and its value
  is sent as `[h, 100]`. So `Colour` overrides two hooks on `Axis`, the read expression and the
  value suffix, rather than adding a branch to the card.

**`inUseWhen` is data, not CEL.** `hass.pkl` and `hass/` hold HA's facts and no CEL today. The
card turns `AttrIn` into an expression. That also keeps the guard (`attr[?'color_mode']…`) in
one place.

**Cover and fan stay as they are.** Their default axis is today's `sliderSpec` row, and this PR
builds it as an `Axis` with owner `e`, so the card reads one shape. A `CoverEntity` with a tilt
axis is a later domain, which then adds a class and nothing to the card.

### The slider reads axes

```pkl
c.entitySlider(l)                              // as today: brightness, then each axis l has
c.entitySlider(l.colourTemp)                   // as today: one axis
c.entitySlider(l.brightness)                   // new: brightness alone, no switch
c.entitySlider(l).opensOn(List(l.colour, l.colourTemp))
c.entitySlider(l).opensOn(List(l.brightness))  // always brightness, as before this
```

- `SlideAxis` becomes `hass.Entity|hass.Axis`. `action`, `key`, `min`, `max` and `valueAttr`
  default from the axis. The `ColourTemp`/`Colour` branches go, and so does `sliderSpec`'s light
  row. `kelvinTrack` and `hueTrack` stay as type tests: they are looks, which belong to the
  card.
- `availableControls` becomes `l.axes.map((a) -> a.name)`.
- **`opensOn(xs: List<hass.Axis?>)`** takes typed axis objects, so `l.colour` on a light without
  colour is a nullability error before eval. A null is dropped, as `.buttons` drops one.
  The first axis whose `inUseWhen` holds when the page opens wins. With none, the slider opens on
  the first control, as today. An axis the switch does not offer is ignored.
- **Default: the axes the light's mode picks between**, its axes that have an `inUseWhen`
  (temperature and colour). So a light opens on the head for the mode it is in, and on
  brightness while it is off or reports no mode. Not `opensOn(l.axes)`: brightness is always in
  use, so listing it first means "always brightness". Every light slider with main controls
  changes its first paint and its wire bytes, and the snapshots move with it.

### Main-control names typed per domain

```pkl
// hass/light.pkl
typealias Control = "brightness"|"colourTemp"|"colour"

// components/its.pkl
typealias MainControl = hassLight.Control     // later: hassLight.Control|hassCover.Control
```

- Each `Axis` subclass fixes `name` to its member of the union, so `.options(List("colour"))` is
  checked in the editor and a typo is an eval error.
- The labels and icons stay in `components/its.pkl`: they are looks, not HA facts.

### Runtime: a node variable can start from state

This is the one runtime change. `opensOn` needs the `control` variable to start somewhere other
than its declared value, chosen per viewer from the light's state.

**Wire.** A declaration is a bare string, as today, or `{"value": "brightness", "startsAt":
{"transform": "<CEL>"}}`. This is the bare-string-or-object rule `SlotSource` already uses, so no
existing dashboard's bytes move. The CEL reads the declarer's subject entity with a slot
transform's bindings (`state`, `attr`). It is compiled at validate, so a bad expression fails the
build.

**When it is evaluated: once, as the session opens. It does not follow later changes.**

- *What it does not follow:* the light changing mode while the page stays open, whether from an
  automation, the HA app, a scene, or ZHA's false `xy` report. The slider keeps the head it
  opened on until the viewer switches or the page reloads. A reconnect carries the committed
  value, so it keeps the head too. On a wall tablet that never reloads, "opens" means when the
  tablet last loaded the page.
- *Why not follow state (a flip, ADR 0007):*
  - **The name.** The setting is what the slider OPENS on.
  - **The head would move under the viewer's finger.** A ZHA group reports `xy` 59 ms after a
    temperature command (`docs/issue-zha-group-colour-mode.md`). A followed default would throw
    the viewer off the temperature head mid-use.
  - **It would need a path that does not exist.** Today only a write moves a variable's panels.
    Following would need state changes to reselect bake groups for every unchosen declaration.
- *Evaluated where:* where the session is created. Today that is where `v.` URL params are
  adopted (`Server`, around `SessionState.initial.copy(…, vars = choices)`). It reads from the
  `StateStore`. The subject is already subscribed (ADR 0030), and ADR 0023 is not touched,
  since the read is of the node's own subject.
- *Held as:* a third layer below the viewer's choices, `Session.starts: Map[VarKey, String]`.
  `VarGraph.env` and `committed` resolve choice, then start, then declared. A start is not a
  choice, so it puts no `v.` param on the URL.
- *A result that is not a value of the bake group* (light unavailable, no colour mode yet) is
  dropped, and the declared value stands. That is the same "refused, keep the declared" a stale
  link gets.

**The URL mirror** compares to the declared value, spelled into the declarer's cached bytes
(`VarKey.urlMirrorAttr`). Two cases break if it stays as it is:

- A start that differs from the declared value would show as a param.
- After a refresh, that param would win over a light that has since changed mode.

The fix is to have the mirror compare to a per-session signal, `_var_<declarer>__<name>__start`.
The shell seeds it beside the committed value, so no per-viewer byte enters a cached node.
`data-fh-url`'s third element is already an expression (`datastar.ts`).

**Limits, stated rather than fixed:**

- A slider a candidate set renders per member declares no `control` (`Node.perMember`), so
  `opensOn` does nothing there; it keeps its first control.
- With `.syncRows()`, the rows start where the head starts: from the group entity's state, not
  each bulb's.

## What changes, by file

| File | Change |
|---|---|
| `lib/hass.pkl` | `Axis`, `AttrIn`, `Brightness`; `ColourTemp`/`Colour` extend `Axis`; `LightEntity.brightness`, `.axes` |
| `lib/hass/light.pkl` | `Control` union |
| `lib/components/slider.pkl` | reads the axis; light branches and `sliderSpec`'s light row gone; `opensOn` |
| `lib/components/its.pkl` | `MainControl = hassLight.Control` |
| `lib/core/variable.pkl` | `declare(value)` gains a starting expression |
| `fh/view/model/Dashboard.scala` | `vars` values: bare string or `{value, startsAt}`; compiled at validate |
| `fh/view/runtime/VarGraph.scala`, `Server.scala`, `Sessions.scala` | `starts` on the session; env order choice → start → declared |
| `fh/view/runtime/Renderer.scala`, shell seed | mirror compares to the `__start` signal |
| `PklDump.scala` | drops a carried `brightness` on lights (`SchemaModelled`); the group itself is derived in `hass.pkl` from `supportsBrightness`, fixed 1–255 |
| `lib/query.pkl` | done first, its own commit: a live name wins over a schema member, and a registry fact holds a scalar |
| `WireShapeSuite` | the new declaration shape |
| ADR 0033 | rule 1 gains a starting value; why it is evaluated once, not followed |
| ADR 0013 | `Axis` among the capability groups, `inUseWhen` as data |
| ADR 0015 | the `MainControl` union as the per-domain pattern |
| `docs/terminology.md` | **Axis**, **starting value** |
| `docs/architecture-rendering-pipeline.md` | where a session's starts are evaluated |

## Tests

- **Pkl facts.** Every axis on a light names a distinct control. `opensOn` builds one expression
  in the given order. `c.entitySlider(l.brightness)` has no switch. A cover's slider still
  posts `position`.
- **Wire snapshots.** The axis refactor leaves them byte-identical: that is the check that it
  changed no output. The default `opensOn` then moves every light slider with main controls,
  and nothing else.
- **Scala.** A declaration with a start resolves to it for a fresh session. A `v.` param wins
  over it. A start outside the group's values falls back to declared. A start puts no param on
  the URL.
- **Smoke.** A colour lamp in `xy` opens on the colour head. Cycling to brightness and refreshing
  keeps brightness. With no param, a refresh opens on colour again.
- **Existing tests I expect to edit, to ask about first:** the `PklBuildSuite` snapshots, and
  `components.test.pkl`'s `s.vars == new Mapping { ["control"] = "brightness" }`, both of which
  the default moves. The smoke fixture's colour lamp reports no `color_mode`, so it still opens
  on brightness and the browser tests stand.

## Order of commits

1. This plan.
2. `q.prop` resolution (ADR 0003), so `l.brightness` can share HA's name.
3. `hass.Axis`, `l.brightness`, the `Control` union, and the slider reading them. No behaviour
   change, and the snapshots stay identical.
4. Runtime starting value: wire, session, mirror.
5. `opensOn` and its default, smoke test, docs and ADRs; delete this plan.
