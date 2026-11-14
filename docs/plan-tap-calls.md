# Plan: a tap names a declared call, and a call can exclude

Three parts in one round:

- **A. A call is declared once, as value expressions, and the page names it by id.** This is
  the design round `plan-value-model-follow-ups.md` lists as "the tap call design". It is the
  maintainer's idea: the call id is the only thing in the template besides live values.
- **B. HA 2026.11's `exclude`** (#692): a call on a floor or a room can leave entities out.
  What it leaves out is fixed, or picked at the press by a value expression ("the kitchen
  light, while it is night").
- **C. Numbers:**
  - a value the page posts, typed and checked by its declaration;
  - the `number` slot type that slot-views point 4 deferred to this round;
  - point 4's own check.

B needs A: a press has to name which exclusion applies, and today's URL names a call only by
service, target and value key. C's posted value lives in A's declaration. C's `number` slot
type needs neither.

Nothing here is built.

## Today

`tapRoute` writes six route slots: `service`, `targetKind`, `targetId`, `targetSet`, `dataKey` and
`dataValue`. Three template pieces spell them into a URL: `tap.serviceClick`, the slider's
`commitOnRelease` and the wheel's `commit`. The URL is
`sse/call/<slug>/<domain>/<service>/<kind>/<id>`. The server rebuilds
`TapCall(service, target, dataKey)` from it and admits the call only if `Dashboard.calls` has that
key (ADR 0023).

A set call's `targetSet` carries its tally as a JSON string, and the press narrows it to the
members present then (`Renderer.pressed`). The value is free: `Call.with(key, v)` splices `v`
into the page, and the server forwards whatever the body's one key says.

A service the state picks (the four `CallByState` domains) is a handler signal holding a
`Match`'s output, spliced into the URL (ADR 0016).

## A. A declared call, named by id

**The declaration.** Every part of an HA call is a value expression (`ValueExpr`, ADR 0035):

- `service`;
- each target key;
- each `exclude` key (B);
- each `data` key.

Target and `exclude` keys are HA's own names, `entity_id`, `area_id` and `floor_id`. Each holds a
list of value expressions, each a `text` or a `list(text)`, and the key's ids are their
concatenation. A bare literal is a literal expression, so `"entity_id": ["light.kitchen"]` is
valid. A set is its tally, a `Present`, which is already a `list(text)`.

`device_id` and `label_id` join later as keys in the target, in `exclude` and in `ServiceTarget`,
with nothing else changing; the dump has no labels yet. A fixed `.with` value is a literal under
its `data` key, and a call may carry any number of `data` keys.

**Every entity read is named.** A slot's wire leaves out `of` where it is the card's subject.
A declaration has no subject, and the id is a hash of it: two locks' declarations would
otherwise be equal and share an id.

**The id** is the first 16 hex characters of the SHA-256 of the decoded declaration, encoded
as JSON with its keys sorted, so the order of two `.with` calls does not move it. Scala
computes it in the `Ready` walk, so Pkl never hashes, and a set's members cost no hashing in
the eval. Pkl's `setId` goes the same way.

- equal calls on two cards share one id;
- an edit elsewhere keeps the id;
- an edited call gets a new id, and a stale page's press with the old one is refused, as a stale
  URL is today.

**Pkl.** A node places its calls by name, and may place several (a slider's commit and its own
switch, say):

- A tap writes a `CallSlot` carrying `hidden declares: CallDecl`. The node's wire field `fixed
  calls` is computed from its call slots, keyed by name, as `templateSlots` is, so no card
  changes.
- Each call has two names the renderer fills, as it fills `{{id}}`: `<name>.callId`, the id,
  and `<name>.seen`, what the press confirms (below).
- The template places one piece per call, `tapMod.post(call)`. It writes the URL
  `'sse/call/{{dashboardSlug}}/{{{tap.callId}}}?node={{id}}'` and builds the body from the same
  call: `posted` from its `Posted` sources, and `seen: {{{tap.seen}}}`. It replaces the three
  spellings above, so the slider and the wheel stop knowing the route.
- A `Posted` source carries, hidden from the wire, the page expression that gives its value:
  the slider's `Number($slide)`, or `[Number($slide), 100]` for a hue. The card that builds the
  source writes it there, so a key and its value are one Pkl value, and no card spells a body
  key.

**What the page sends.** The press posts the call id, the node, and a body of two halves:

- `posted`: the values the card supplies, the drag's brightness or the wheel's hue. These are
  the inputs. Each is read by a `Posted(key, type)` source (C).
- `seen`: the output, as the page holds it, of each field of what the call DOES whose reads the
  node paints, keyed by field: `{service: "lock/unlock"}`.

**Which fields are seen is decided per node, at build.** For each node and each call it places,
the `Ready` walk takes the call's `service` and each `data` field that reads state. A read is an
entity and the part read: its state, one attribute, or the whole. A field is seen on that node
when a painted slot of the node, or of a node inside it, reads one of its reads: what is inside
the pressed element is what the viewer pressed. A painted slot is a template slot that is not a
`Handler`, which paints nothing (`tapDisabled`). The cell's live classes and styles
(`cellClassWhen`, `cellStyle`) do not count. Moving them onto the slot model is a follow-up.

A field that reads a fetch is never seen. The press would answer the fetch again, and a history
window moves between the render and the press, so the two answers need not agree. It is
decided at the press.

- **A seen field** gets a slot the walk adds: the field's own value expression, as a `Handler`
  signal. The template has no hole for it and the card never declares it; it rides the node's
  seed and the signal frames like any signal slot. It is named by what it reads, as every
  display signal is, so equal fields on two nodes share one signal. It moves only when the
  field's output moves, in the same frame as the slots that paint its reads (ADR 0016).
  `<name>.seen` is filled with its reads: `{service: $_e.lock.front_door.t9c2e}`.
- **A field that is not seen** gets no signal. The viewer never saw what it reads, so it is
  decided at the press.

A literal field and a posted value are never seen. Target and `exclude` are not either: what a
call reaches is decided at the press, as a set call is (ADR 0023). A "lights off" whose label
counts the lights it reaches is never refused for a light changing, and a node that sees nothing
posts `seen: {}`. This slot replaces today's state-picked `service` route slot, so a page carries
the signals it carries today.

**What the server does at the press:**

1. **Look up the id** in `Dashboard.calls`, and which of its fields the node sees, the node as
   it stands now (a set member is its current clause). A miss on either is refused: admission by
   declaration is ADR 0023's rule, and a node that does not place the call has nothing to check
   against.
2. **Decode each `posted` value** as its source's declared type, and run the fields. A field
   that ends with no value (a failed `Check`, C) refuses the press and names the field.
3. **Evaluate every field now.** A field that reads a fetch has it answered first, as a set
   call's guard has today (`Server.decided`). Each field the node sees is converted to the slot
   value its signal carries (text or a boolean, and a number after step 5) and must equal what
   `seen` posted for it. `seen` must name exactly those fields. A missing or extra key is
   refused, and that is what a set member's page posts when its case changed after the page drew
   it. A refusal names the field and both values: `service: lock/unlock shown, lock/lock now`.
4. **Call HA** with what the server evaluated.

**Trust.** The server only ever runs its own evaluation of a declared call:

- the call id selects among the dashboard's declared calls;
- `seen` can only refuse;
- `posted` values are the one client input that reaches HA, which is why they are typed and
  checked (C).

`seen` is a race guard. A press that races a state change the node shows is refused with a toast
(ADR 0024) rather than acting on state the screen did not show. A lock's double-tap therefore
refuses the second tap, where today it sends unlock twice; neither does the opposite of what was
shown. ADR 0016 becomes "you act on what you saw, or not at all".

The service no longer needs listable outputs, so a CEL may pick it. That closes #533.

**The wire.** The ids and signal names are illustrative, and the before forms are in
`snapshots/taps.json` and `sliders.json`.

A lock tile, `c.entityCard(door).tapAction(c.tap.toggle(door))`:

```jsonc
"templateSlots": {
  "value": { "source": { "kind": "Entity" }, "signal": { "kind": "Text" } },   // paints the state
  "icon":  { "source": { "kind": "Entity" }, "steps": [Match …], "signal": { "kind": "Attr", "name": "class" } }
},
"calls": { "tap": {
  "service": { "source": { "kind": "Entity", "of": "lock.front_door" },
               "steps": [{ "kind": "Match", "cases": { "locked": "lock/unlock" }, "otherwise": "lock/lock" }] },
  "target":  { "entity_id": ["lock.front_door"] } } }
// build: service reads lock.front_door's state, which `value` paints → service is seen; the
//        walk adds it as a Handler slot, signal $_e.lock.front_door.t9c2e. The icon does not matter.
// page:  @post('sse/call/taps/4be03a91c2d0e6f7?node=c_6', {payload: {seen: {service: $_e.lock.front_door.t9c2e}}})
// press: the service evaluated now must be "lock/unlock", the value the signal held
```

A slider, `c.entitySlider(dump.lights.kjokken)`:

```jsonc
"calls": { "commit": {
  "service": "light/turn_on",
  "target":  { "entity_id": ["light.kjokken"] },
  "data": {
    "brightness": { "source": { "kind": "Posted", "key": "brightness", "type": "int" },
                    "steps": [{ "kind": "Check", "steps": [{ "kind": "Compare", "op": "gte", "to": 1 }] },
                              { "kind": "Check", "steps": [{ "kind": "Compare", "op": "lte", "to": 255 }] }] },
    "transition": 1 } } }
// build: no field of what it does reads state → no seen
// page:  @post('sse/call/sliders/51aa8e2f07c3d916?node=c_0_head_0', {payload: {posted: {brightness: Number($_c_0_head_0__slide)}, seen: {}}})
```

A plain call carries only literals and posts nothing. A set call's target is
`"entity_id": [{ "source": { "kind": "Present", … } }]`, decoded as an object where today it is
a string inside a slot.

**Scala.**

- `Dashboard.calls: Map[CallId, CallDecl]` is the allowlist, read off the nodes' `calls`.
- `Ready` compiles every field, computes each id, and adds a `Handler` slot for each field a node
  sees.
- The route is `POST /sse/call/:slug/:callId`.
- Every call field's entities join `watchedEntities`, which narrows what the feed asks HA for.
- `validate` refuses:
  - a `Posted` source outside a call;
  - a declaration whose entity read names no entity;
  - a call field that reads a node variable. The press is answered as a decision, and a decision
    reads no viewer's variable. A later round can allow it by having the press carry its viewer;
  - a `data` key named like a target key or `exclude`, since HA merges a call's target into its
    data.

**Goes:**

- the six route slots, the state-picked `service` handler slot, and their `readByRenderer`
  entries;
- `TapCall`, `TapCall.targetOf`, `CallSet`;
- `declaredCalls`' slot reading;
- `validate`'s `callSetErrors`;
- `jsonLiteral`'s splice;
- `withData`;
- `Server.callData`'s one-key rule;
- the route's `:domain/:service/:kind/:id`;
- Pkl's `setId`.

**Tests:**

- a property: equal declarations share an id, and different ones never do;
- a property: reordering a declaration's `data` keys keeps its id;
- a property: a node sees exactly the `service`/`data` fields that share a read with a painted
  slot of it or of a node inside it, never one that reads a fetch;
- `WireShapeSuite` covers `CallDecl`;
- presses that are refused:
  - an unknown id;
  - a node that does not place the call;
  - a `seen` that does not match now;
  - a `seen` with a key missing or one too many, as a set member posts after its case changed;
  - a forged `seen`;
  - a posted value of the wrong type;
  - a missing posted key;
- a press that is not refused: a set call whose node counts the set, after a member changed.

**Measure:** every tap's bytes change, so run RenderBench before and after (`page`,
`tickRender`), plus the goldens.

**Goldens:** they hold call ids numbered by first appearance (`call_1`, …), and
`dashboardGoldensVs` numbers them the same way. A change to one declaration then shows in the wire
snapshot, not in every golden that renders a tap.

## B. Exclude

**HA** (core#187290, in 2026.11): a target takes `exclude:` with the target's own keys.

- It is resolved every time the call runs, so a light added to an excluded room later stays
  excluded.
- An exclusion that matches nothing has no effect.
- An exclude-only target is invalid.
- `cv.ENTITY_SERVICE_FIELDS` gained it, and in 2026.11.1 the WS `call_service` validates
  `target` with exactly that. So our call path takes it as it is, and so does the REST path,
  which merges the target into the data.

**Pkl.** `Call.exclude(x)` takes an entity, a room, a floor or a set:

```pkl
// fixed: HA resolves the room at every call
c.tap.lights.off(dump.floors.loft).exclude(dump.areas.soverom)

// decided at the press: left out while the condition holds
c.tap.lights.off(dump.floors.loft)
  .exclude(q.from(List(dump.lights.kjokken)).where((e) -> q.entity(dump.entities.binary_sensor_natt).stateIs("on")))
```

The `where` belongs to the set, not to the call. The exclusion IS the set, and what it leaves
out is its members present at the press. A guard can then differ per member (each light's own
motion sensor), and the same set can be counted in a label.

Any condition is a guard: the simple shapes, CEL, an entity named with `q.entity`, a count. Time
is only an example of one. An HA helper such as Times of the Day or Schedule turns time into an
entity's state, which the store already follows.

A shortcut for one entity under one condition, whose lambda ignores its member, is a later
design. So is one guard shared by many candidates: the wire writes a set's guard once per
candidate today, which matters for big sets, not for B.

**The wire.**

```jsonc
"exclude": { "area_id": ["soverom"],
             "entity_id": [{ "source": { "kind": "Present", "candidates": ["light.kjokken"],
               "when": { "light.kjokken": { "source": { "kind": "Entity", "of": "binary_sensor.natt" },
                                            "steps": [{ "kind": "Compare", "op": "eq", "to": "on" }] } } } }] }
```

**At the press**, the server evaluates `exclude` with the rest, unconfirmed (A), and sends it to
HA as `exclude`, which HA resolves against the target. A key whose expressions give no ids is
left out, and `exclude` goes when no key is left, so HA never sees `entity_id: []`. A target
left with no key is the `NoContent` that a set target with no member present is today.

The entities a guard reads join `watchedEntities`, as a set call's guards do.

**ha-api.** `ServiceTarget` becomes HA's shape: id lists per key, plus an `exclude` of the same
shape.

**No HA version check.** HA before 2026.11 refuses `exclude`, and that is the ordinary refused
press (ADR 0024).

## C. Numbers

**A posted value.** `Posted(key, type)` is a source that reads the value the press posted under
`posted.<key>`, decoded as `type` (a `ValueType`: `int`, `real`, `bool`, `text`, `list(real)`). It
is not `Var`: a node variable is held by the server per viewer across requests and read by any
slot, while a posted value lives for one request and only its call reads it. The key is the
page's name, separate from HA's field, so `brightness_pct` can read `Posted("pct", int)`.

- **The slider** posts its drag as `int`, checked to its range.
- **The wheel** posts `[h, s]` as `list(real)`.

**A `Check` step.** It passes the value through while its test holds, and otherwise ends with no
value. The test is steps over the incoming value: Compare, Match or CEL. Two bounds are two
Checks in a row.

"No value" already exists: unparseable input to `Percent` or `Duration` gives it, and a slot's
`default` fills it. So in a slot a failed `Check` shows the default. At a call, a field with no
value refuses the press (A).

The range comes from the Pkl numbers the slider draws, so the two cannot disagree.

**The `number` slot type.** `slotMod.number(name)`:

- the build refuses a reading that is not `int` or `real`, as `bool` refuses a non-bool;
- `jsValue` and a signal carry it unquoted, and a text hole prints it.

`SlotValue` grows a number. The first customer is the slider: its `min`/`max` slots, the seed
`{slide: '180'}` and the `Number($slide)` that undoes it. Whether `Number(…)` can go also depends
on what the pinned bundle's `data-bind` writes for a range input, which is spiked first.

**Point 4:** `validate` refuses a text slot's `{{x}}` inside a Datastar expression attribute
(`data-on:*`, `data-effect`, …), where it is a string pasted into JavaScript. The rule is to
place a `jsValue`, or a number or bool slot.

## Steps

1. **ha-api `ServiceTarget`:** HA's shape with `exclude`. Pure, JSON-tested.
2. **Declared calls** (A):
   - the Pkl `CallDecl`, `CallSlot` and node `calls`;
   - fields as value expressions;
   - the `Posted` source, typed;
   - `posted`, and each node's `seen` decided in the `Ready` walk;
   - the Scala decode, the id computed there, `Dashboard.calls` by id, the route and
     `tapMod.post`;
   - the deletions;
   - bench and goldens. Closes #533.
3. **The `Check` step** (C), with the slider and the wheel checked to their ranges.
4. **Exclude** (B), fixed and decided, plus `watchedEntities`. A `UseCaseSuite` case covers a
   floor, an excluded room and a guarded light. Closes #692.
5. **The `number` slot type** (C), with the slider moved onto it.
6. **Point 4's check**, if 5 lands clean.

**Docs, with the steps:**

- ADR 0023: admission by declared id, and the trust paragraph above;
- ADR 0016: "you act on what you saw, or not at all", with the `Match` in the declaration;
- ADR 0024: the mismatch refusal, and one effect of it. Datastar retries a press whose answer
  was lost. If HA had acted, the retry is refused once the state has moved, so the viewer sees a
  refusal for a press that worked. A literal service has no `seen`, so its retry acts again, as
  today;
- ADR 0035: the `Posted` source and the `Check` step;
- ADR 0017: a `Handler` slot the build adds;
- `docs/architecture-rendering-pipeline.md`: the route, the press path and the slots the build
  adds, in the same commit as each;
- `writing-dashboards.md`: exclude;
- `writing-components.md`: `tapMod.post` and the number slot;
- `terminology.md`: declared call, call id, posted, seen, painted slot, exclusion;
- the module `CLAUDE.md` route paragraph.

## Decided

All on 2026-11-14:

- An exclusion is decided at the press.
- C is what "the number addition" meant.
- No shortcut for one entity yet.
- No HA version check.
- Devices and labels come later, and the shape allows them.
- The id is a content hash, computed by Scala from the decoded declaration as JSON with sorted
  keys.
- A node may place several calls, each by name, with `<name>.callId` and `<name>.seen` filled
  by the renderer.
- Every field is a value expression: the `Match` is reused, with no `ByState` type.
- The server runs its own evaluation:
  - `seen` holds the output of each field of what the call does (`service`, `data`) that reads
    something the node paints, decided per node at build. Each is a `Handler` slot the build
    adds, and a mismatch is refused;
  - painted means a non-`Handler` template slot of the node or of a node inside it; cell
    classes and styles do not count yet;
  - `seen` names exactly the fields the node sees, compared as slot values, and a refusal names
    the field and both values;
  - a field reading nothing the node paints, a field reading a fetch, and what the call reaches
    are decided at the press;
  - only `posted` values are client input to HA.
- A call field may read a fetch, answered at the press, and is then never seen.
- A `Posted` source carries its page expression, so a card never spells a body key.
- An id key that comes out empty is left out, and so is an empty `exclude`.
- Putting every tap on one post with `conn` is a later step, not this plan.
- `Posted(key, type)` is the source for a posted value, not `Var`.
- Every entity read in a declaration names its entity.
- Validation is a `Check` step, not a call-level condition.
- No call field reads a node variable yet; the shape allows it later.
- `tapMod.post` builds the body from the call.
- `exclude` goes to HA for every kind of target, and HA resolves it.
- The order is A with C's posted value, then the `Check` step, then B, then the `number` slot
  type, then point 4.
