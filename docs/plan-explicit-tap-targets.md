# Plan — a tap names what it acts on

Issues #389 (area and floor taps, landed as #434) and #329 (comment 5744000081:
components tied to a domain, devices later). Builds on ADR 0016 (what a tap does),
ADR 0019 (the guard) and ADR 0023 (what a tap may reach).

## Why

A service tap does not know its entity. `c.tap.service("lock/lock")` is a service
name and nothing else; the card it sits on supplies `{{entity_id}}` from its own
subject slot, and the inert check and a state-picked service read that subject
too. Three consequences:

- **A tap means nothing on its own.** `c.button("Lås", c.tap.service("lock/lock"))`
  is a build error only because `Button` happens to demand an entity; on a card
  with a different subject it locks whatever that card shows.
- **The base button is not basic.** `Button` carries `entity`, `lit` and a
  friendly-name label so that a tap can borrow its entity. What should be a
  button that knows nothing is half an entity card.
- **Two routes, two allowlists.** An entity tap may call ANY service on any entity
  the dashboard names (`Permission.mayAct`); an area tap only the exact
  combination the build declared (`mayCall`, #434). The stricter one is the right
  one for both.

The namespace shows the same split: `c.tap` is one flat list of target-less
verbs (`service`, `serviceValue`, `toggle`), target-carrying ones (`areaCall`,
`floorCall`, `lightsOff`…), and navigation, with `moreInfo` outside it on `c.`.

## The design

**A tap is a value that names its target.** Two variants, rendered in one place
(`core/tap.pkl`'s `tapRoute`):

```pkl
typealias Target = hass.Entity|hass.Area|hass.Floor

abstract class TapAction { busy; busyVisual; href; inlineSurfaces }
class Call extends TapAction {      // perform-action, toggle
  service: String|slotMod.Slot      // a literal, or picked by the target's state
  target: Target
  dataKey: String?; dataValue: String?
  inertWhile: Listing<String>
}
class Click extends TapAction {     // navigate, open/close a surface
  onclick: slotMod.Slot|String
}
```

- **The route slots are the target's, not the card's.** A `Call` emits
  `service`, `targetKind` (`entity`/`area`/`floor`), `targetId` and `dataKey`;
  the URL is built from them alone. The inert slot and a state-picked service
  slot carry `entityId = target.entity_id`, so they read the TARGET's state
  whatever the card displays.
- **One route, one allowlist.**
  `POST /sse/call/:slug/:domain/:service/:kind/:id[/:key/:value]`, allowed only
  for a `(service, target, dataKey)` some node of that dashboard declares
  (`Dashboard.calls`, read off the same four slots; a state-picked service
  declares every arm of its match). `sse/action` and `sse/target` go, and so
  does the "any service on a named entity" rule. The slider's commit posts to
  the same route through the same slots. The value stays free, as today.
- **The author's namespace is verbs that take their target** (HA's
  `tap_action` variants):

  ```pkl
  c.tap.toggle(e)                       // the domain's own, or homeassistant/toggle
  c.tap.moreInfo(e)
  c.tap.call("scene/turn_on", dump.scene.kveld)
  c.tap.call("light/turn_on", l).with("effect", "colorloop")
  c.tap.navigate("under"); c.tap.openPopup("detail"); c.tap.closePopup()
  c.tap.lights.off(dump.areas.stue)     // typed: LightEntity|Area|Floor
  c.tap.locks.openLatch(l)              // typed: LockEntity (`open` is a keyword)
  ```

  Deleted: `service`, `serviceValue`, the `toggle` constant, `stateService` as an
  author verb (it stays in the core for `byDomain`), `areaCall`, `floorCall` and
  the flat `lightsOff`/`lightsOn`/`lightsToggle`. `c.moreInfo` moves under
  `c.tap`. A domain namespace exists where a typed verb is wanted (lights and
  locks today); `call` covers the rest.

  `c.tap` becomes a dashboard-tier facade (`components/tap.pkl`), since
  `moreInfo` is a component and the core cannot import one (ADR 0015).
- **The base button knows nothing.** `c.button(label, action)` and `c.pill` lose
  `entity` and `lit`. An ENTITY button is one layer up — `c.entityButton(e)`:
  friendly-name label, the entity's icon, `lit` while on, `defaultTap(e)` — and
  the slider's head actions are built on it. `c.toggle(e)` and the entity card
  stay entity components, and their default tap is `defaultTap(e)`, a value
  that names `e`.
- **Room left for HA's other axes.** `hold_action`/`double_tap_action` are more
  `TapAction` properties on a card, and `confirmation` a field on `TapAction`;
  neither is in scope. Devices (#329) follow the entity-button pattern: a
  component one layer up that takes a `hass.Device` and picks its taps.
- **More target kinds later.** HA also targets a device, a label, and
  `entity_id: all` (every entity of the service's domain). Each is one more
  member of `Target` and one more `kind` on the route, allowed by the same
  exact-declaration rule — `c.tap.lights.off(dump.house)` would be the house-wide
  one. Not in scope; `all` needs the most care, since `homeassistant/turn_off`
  on it is every entity in the house.

## What it costs

- Every authored tap names its target once more:
  `c.entityCard(l).tapAction(c.tap.toggle)` becomes `…(c.tap.toggle(l))`. That
  repetition is the point — the tap can then sit on any card.
- A dashboard whose tap reaches a service the build never saw is refused. The
  only thing that did that is a hand-edited URL.

## Steps (one stacked PR each)

1. **This plan, plus the spike**: `Call` carries a target; `c.tap.toggle(e)` and
   `c.tap.call(service, target)` exist beside the old verbs; a button with no
   entity toggles its target, and its inert check reads the target. Old verbs
   keep working by having the card fill a missing target from its entity.
2. **One route, one allowlist**: `sse/call`, `Dashboard.calls` over every target
   kind and the slider commit, `Permission.mayCall` the only action check;
   `sse/action`, `sse/target` and `mayAct` deleted. ADR 0023 rewritten.
3. **The namespace**: `components/tap.pkl` as `c.tap`, the verbs above, the
   domain namespaces; the old verbs and the card-fills-the-target fallback
   deleted; every call site, demo and test fixture migrated. ADR 0016 rewritten.
4. **The base button**: `Button`/`Pill` without `entity`/`lit`,
   `c.entityButton(e)`, `sliderAction` on it.
5. **Close**: module `CLAUDE.md`, pipeline doc and terminology checked; this plan
   deleted.
