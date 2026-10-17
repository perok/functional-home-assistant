# Plan: a call over a query set (#583)

A tap's call can act on a `q.from(…).where(…)` set. A press acts on the members
present at that moment, and the label can read the same set.

```pkl
local allOff = q.from(hass.lights(dump.areas.stue.all))
  .where(q.eq(q.stateProp, "on"))
  .where((e) -> if (e == dump.entities.light_relative_bibliotek)
    q.not(q.entity(dump.entities.sun).stateIs("below_horizon")) else true)

c.button("All off", c.tap.call("light/turn_off", allOff))
  .secondary(c.expr(#"string(off) + ' on'"#))
  .expressionValues(new Mapping { ["off"] = allOff.count() })
  .disabled(allOff.none())
```

## What exists, and is reused as it is

- **The set as data.** `allOff.count()` is a `CountRef`, which is a `pred.Tally`:
  the candidates fixed at build, plus a live guard for each candidate the build
  could not settle. A call takes that same value.
- **Its evaluation.** `Conditions.present(tally, states)` counts the present
  candidates; the press filters with the same `isPresent`, so a label counting
  this tally and a press acting on it cannot disagree about the rule. Only the
  moment differs: the label shows the last render, the press reads the store at
  the press.
- **The label.** Expression values (ADR 0034) already take a tally. Nothing new:
  the author passes the same `local` to the call and to the count.

## What is new

### Pkl

- `core/tap.pkl`: `Target` gains `pred.Tally`. For a tally, `routeSlots` emits
  `targetKind = "set"` and `targetId` = the first 16 hex characters of the
  sha256 of the tally's JSON. The id is only a key: two buttons over the same set
  share it.
- The tally itself rides on the node as the derived value `__call` (a node holds
  one tap's route, so one name is enough). `tapMod.tapValues(t)` returns it, and
  each card that takes an author's tap spreads it into `derivedValues`: button,
  pill, tile, switch and the slider head. The wheel, a select's options and a
  tab build their own taps on an entity or a variable, never a set. A card that
  forgets fails `validate` (below), so the omission can't ship silently.
- `components/tap.pkl`: `call(service, on: core.Target|q.EntitySet)`, and
  `c.tap.lights.on|off|toggle` take a set too. An `EntitySet` is lowered with
  `.count()`.
- **Build errors:**
  - No candidates: the call acts on nothing.
  - A candidate whose domain isn't the service's, except for a `homeassistant/*`
    service, which takes any domain.
- `refusedStates` stays entity-only. A set has no single state to refuse on; the
  author's `.disabled(set.none())` is the guard.

### Scala

- `ha-api` `ServiceTarget` gains `Entities(ids: List[String])`. This is HA's own
  `entity_id: [...]`. `key`/`id` move from the enum's constructor onto `json`.
- `TapCall.target` becomes `ServiceTarget | CallSet`, where `CallSet(id)` names
  the set by its route id.
- `Dashboard`:
  - `callSets: Map[String, Predicate.Tally]` is read off each set-targeted node's
    `targetId` slot and its `__call` value.
  - `calls` emits `Set(id)` for those nodes.
  - `validate` rejects a `set` route with no `__call` tally, and one id naming two
    different tallies.
- `watchedEntities` adds each call set's candidates and the entities its guards
  read (ADR 0030). A guard-only entity such as the sun is otherwise not in the
  store, and its guard is false.
- `Permission` carries `sets: String => Option[Tally]` beside `calls`, read off
  the renderer as `calls` is.
- **`Server.tapCall`, for `kind == "set"`:**
  1. Admit `(service, CallSet(id), dataKey)` as today.
  2. Look up the tally.
  3. Filter its candidates with `Conditions.presentOf`, the rule
     `Conditions.present` counts by, against the store's current states.
  4. If none are present, refuse through `actionRefused` and make no HA call.
     The message says that nothing matched.
  5. Otherwise make one `callService` with `ServiceTarget.Entities(present)`.

  The browser never sends an entity id; the server only ever sends a subset of
  the candidates fixed at build (ADR 0023).

### Docs

- **ADR 0003:** a set can be a call's target as well as a rendering.
- **ADR 0016:** the domain check.
- **ADR 0023:** sets in the allow-list.
- **ADR 0024:** a press on an empty set is refused.
- **ADR 0030:** guard entities are subscribed.
- **ADR 0034:** an example with the label and the call over one set.
- **The architecture doc:** unchanged; it doesn't cover the call route.
- **`terminology.md`:** a tap's target can be a query set.
- **The module's `CLAUDE.md`:** the route line (`kind` is
  `entity`/`area`/`floor`/`set`).

## Tests

- **Pkl facts:**
  - A set call emits the `set` route and the `__call` value.
  - Two equal sets share an id.
  - An empty set and a domain mismatch are each a build error.
  - `homeassistant/toggle` over mixed domains builds.
- **`Dashboard`:**
  - `calls` and `callSets` read a set call.
  - `validate` catches a missing `__call` value and an id clash.
  - `watchedEntities` includes a guard-only entity.
- **Functional, against `FakeHomeAssistant`:**
  - A press sends ONE call listing the present candidates.
  - Flipping the guard's entity changes the list on the next press.
  - An empty set refuses, with no recorded call.
  - An undeclared set id is refused.
  - The label and the press agree after a state change.
- **Live HA:** a set-call dashboard against the real dump (local, gitignored)
  rendered "turns off 2 of 7" and a `/set/<id>` route naming no entity. No
  press: a press would switch real lights.

## Open

- A success answer ("Turned off 3 lights", from the issue). The toast is the
  error channel today (ADR 0024, red), so a neutral answer is a new look and a
  new signal. Proposed: left out of this PR. The label already says what a press
  will do, and the state change shows what it did.
