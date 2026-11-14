# Plan: a tap names a declared call, and a call can exclude

Three things in one round. B needs A, because a press has to name which exclusion applies,
and today's URL names a call only by service, target and value key. C's call value lives in A's
declaration. C's `number` slot type needs neither.

A. **A call is declared once and the page names it by id**, the design round
   `plan-value-model-follow-ups.md` lists as "the tap call design". This was the maintainer's idea:
   the call id is the only thing in the template besides live values.
B. **HA 2026.11's `exclude`** (#692): a call on a floor or room can leave entities out. Those are
   either fixed, or the ones a value expression picks at the press ("the kitchen light, while
   it is night").
C. **Numbers:** the `number` slot type that slot-views point 4 deferred to this round, and a
   call's value typed by its declaration ("services/values are another layer").

Nothing here is built. The parts are A, B and C; steps and questions are numbered.

## Today

`tapRoute` writes six route slots: `service`, `targetKind`, `targetId`, `targetSet`, `dataKey` and
`dataValue`. Three template pieces spell them into a URL: `tap.serviceClick`, the slider's
`commitOnRelease` and the wheel's `commit`. The URL is
`sse/call/<slug>/<domain>/<service>/<kind>/<id>`. The server rebuilds
`TapCall(service, target, dataKey)` from it and admits the call only if `Dashboard.calls` has that
key (ADR 0023).

A set call's `targetSet` carries its tally, which the press narrows to the members present then
(`Renderer.pressed`). The value is free: `Call.with(key, v)` splices `v` into the page, and the
server forwards whatever the body says.

An exclusion has nowhere to go:

- the URL cannot carry a condition;
- the allowlist key cannot tell apart two calls that differ only in what they leave out.

## A. A declared call, named by id

**Pkl.**

- `tapRoute` writes one placed slot, `callId`. It also writes the call's declaration on that
  slot: a `CallSlot extends Slot` with a `hidden declares: Mapping<String, CallDecl>`.
- The node's wire field `fixed calls: Mapping<String, CallDecl>` is computed from its slots, as
  `templateSlots` is. No card changes.
- `CallDecl` is the call as data: `service`, `target`, `exclude` (B) and `data` (C).
- The id is `renderValue(decl).sha256.take(16)`, as `setId` is today:
  - equal calls on two cards share one id;
  - an edit elsewhere in the dashboard keeps the id;
  - an edited call gets a new id, which a stale page's press is refused for, as a stale URL is
    today.
- It is not node-keyed: `pathId` ids move on edit, and the maintainer ruled out a build
  parameter on the press.

**The wire.** A declaration uses HA's target keys, each a list, so `device_id` and `label_id`
can join later as keys and need no new shape. A set is our own key: it is narrowed at the
press. Today the set's tally is a JSON string inside a slot; here it is decoded as an object.

```json
"templateSlots": { "callId": { "source": "9c1e0b7a44d2f310", "type": "textRaw" } },
"calls": {
  "9c1e0b7a44d2f310": {
    "service": "light/turn_off",
    "target": { "floor_id": ["loft"] },
    "exclude": { "area_id": ["soverom"], "sets": [ { "kind": "Present", "candidates": ["light.kjokken"], "when": { … } } ] }
  }
}
```

`data` is `{"kind": "Fixed", "key", "value"}` or `{"kind": "Supplied", "key", "type", …}` (C).

**A service the state picks** (the four `CallByState` domains): `callId` is a handler signal over
the same `Match`, but its arms are ids and each arm's call is declared. ADR 0016's "you send what
you saw" holds as it does today: the signal arrives in the frame that paints the state.

**The template** places one piece, `tapMod.post(value)`: `@post('sse/call/{{dashboardSlug}}/' +
callId.jsValue + '?node={{id}}', …)`. It replaces the three spellings above, so the slider and
the wheel stop knowing the route.

**Scala.**

- `Dashboard.calls: Map[CallId, CallDecl]` is the allowlist, read off the nodes' `calls`. In
  `Ready`, each entry's set and exclusion are compiled beside it.
- The route is `POST /sse/call/:slug/:callId`. A lookup miss is the refusal it is today.
- `validate` refuses one id with two different declarations; content ids make that a collision
  or a hand-built node.

**Goes:**

- the six route slots and their `readByRenderer` entries;
- `TapCall`, `TapCall.targetOf`, `CallSet`;
- `declaredCalls`' slot reading;
- `validate`'s `callSetErrors`;
- the route's `:domain/:service/:kind/:id`.

**Measure:** every tap's bytes change. Run RenderBench before and after (`page`, `tickRender`),
plus the goldens, which a press changes wholesale.

## B. Exclude

**HA** (core#187290, in 2026.11): a target takes `exclude:` with the target's own keys (`entity_id`,
`device_id`, `area_id`, `floor_id`, `label_id`).

- It is resolved every time the call runs, so a light added to an excluded room later stays
  excluded.
- An exclusion that matches nothing has no effect.
- An exclude-only target is invalid.
- `ENTITY_SERVICE_FIELDS` gained it, and in 2026.11.1 the WS `call_service` validates `target`
  with exactly that, so our call path takes it as it is. So does the REST path, which merges
  the target into the data.

**Pkl.** `Call.exclude(x)` takes an entity, a room, a floor or a set:

```pkl
// fixed: HA resolves the room at every call
c.tap.lights.off(dump.floors.loft).exclude(dump.areas.soverom)

// decided at the press: left out while the condition holds
c.tap.lights.off(dump.floors.loft)
  .exclude(q.from(List(dump.lights.kjokken)).where((e) -> q.entity(dump.entities.binary_sensor_natt).stateIs("on")))
```

The `where` belongs to the set, not to the call: the exclusion IS the set, and what it leaves
out is its members present at the press. A guard can then differ per member (each light's own
motion sensor), and the same set can be counted in a label. A shortcut for one entity under
one condition, whose lambda ignores its member, is a later design.

A set lowers to its tally (`Value.Present`): candidates, and a guard per candidate. Any
condition is a guard: the simple shapes, CEL, an entity named with `q.entity`, a count. So the
exclusion is whatever the value expressions can decide. Time is only an example: an HA helper
such as Times of the Day or Schedule turns it into an entity's state, which the store already
follows. Nothing new is needed in the value language.

**At the press**, decided then by the set call's rule rather than ADR 0016's "you send what
you saw". ADR 0016's case is a service that inverts, where fresh state does the opposite of what
was shown. An exclusion is membership, which a set call already decides at the press.

- fixed exclusions go to HA as they are;
- each set is narrowed by `presentOf`, the code a set call uses, to `exclude.entity_id`;
- for a target that is already an entity list (a set call), the exclusion is subtracted here,
  and an empty result is the `NoContent` it is today, rather than a call HA would answer with
  "nothing referenced".

**Safety:** an exclusion only narrows a call and never reaches the browser, so admission is
unchanged. Entities a guard reads join `watchedEntities`, as a set call's guards do.

**ha-api.** `ServiceTarget` becomes HA's shape: id lists per key, plus an exclude of the same
shape. `Entities` is a list.

**Not now, but the shape allows it:** devices and labels, in the target and in `exclude`. HA
takes `device_id` and `label_id` in both. The dump has devices; labels would first need to join
the dump. Each would be one more key in the declaration and in `ServiceTarget`.

**No HA version check.** HA before 2026.11 refuses `exclude`, and that refusal is the normal
refused-press path (ADR 0024).

## C. Numbers

**A call's value is declared.** `CallDecl.data` takes one of two forms:

- **fixed:** `.with("effect", "colorloop")`. The value is in the declaration and the page sends
  nothing. `dataValue` and `jsonLiteral`'s splice go.
- **supplied by the card:** a key and a type. The slider sends a `number`, with the range it
  draws; the wheel sends a list of two numbers. The server checks the body against the
  declared type and range, and refuses a mismatch as ADR 0024 refuses anything else.

**A `number` slot type.** `slotMod.number(name)`:

- the build refuses a reading that is not `int` or `real`, as `bool` refuses a non-bool;
- `jsValue` and a signal carry it unquoted, and a text hole prints it.

`SlotValue` grows a number. The first customer is the slider: its seed `{slide: '\(valueSlot)'}`
and `Number($slide)` at the commit.

**Point 4, after it:** `validate` refuses a text slot's `{{x}}` inside a Datastar expression
attribute (`data-on:*`, `data-effect`, …), where it is a string pasted into JavaScript. The rule
is to place a `jsValue`, or a number or bool slot.

## Steps

1. **ha-api `ServiceTarget`:** HA's shape with `exclude`. Pure, JSON-tested.
2. **Declared calls and the id route** (A): Pkl `CallDecl`/`CallSlot`/node `calls`, the Scala
   decode, `Dashboard.calls` by id, the route, `tapMod.post`, deletions. Bench and goldens.
3. **Typed values** (C, first half): fixed values leave the page; card values are checked.
4. **Exclude** (B): fixed and decided; `watchedEntities`; a `UseCaseSuite` case with a floor, an
   excluded room and a guarded light.
5. **The `number` slot type** (C, second half) and the slider moved onto it.
6. **Point 4's check**, if 5 lands clean.

**Docs, with the steps:**

- ADR 0023: admission by declared id;
- ADR 0016: the handler signal holds ids;
- ADR 0024, if the route moves its refusal wording;
- `writing-dashboards.md`: exclude;
- `writing-components.md`: `tapMod.post`, the number slot;
- `terminology.md`: declared call, call id, exclusion;
- the module `CLAUDE.md` route paragraph.

**Issue:** close #692 with step 4.

## Questions

Decided on 2026-11-14:

- an exclusion is decided at the press;
- C is what "the number addition" meant;
- no shortcut yet;
- no HA version check;
- devices and labels come later, with the shape allowing them (B);
- the call id is the first 16 hex characters of the SHA-256 of the declaration as the wire
  carries it, as `setId` is today.

Open:

1. **Order:** land C's call value with the declared calls in step 2 (recommended), since its
   `data` field is shaped there. The `number` slot type depends on nothing and stays after
   exclude.
2. **A service the state picks:** one declaration whose `service` is the `CallByState` table,
   with the page sending the state it showed (recommended), instead of an id per arm.
3. **`data` as a map of keys,** each fixed or supplied, so one call can mix the two: a drag's
   brightness with a fixed transition.
