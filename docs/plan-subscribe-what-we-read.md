# Subscribe to what we read

Ask HA for the entities the dashboards actually read, instead of the whole house.

## Why

`HomeAssistantApi.entities` sends `subscribe_entities()` with no arguments, so every state
change in the house crosses the wire, is parsed, is ingested, and drives a publisher pass per
registered slug. On the live instance that is 1070 entities where a dashboard reads a few dozen.

Measured (`RenderBench`, JDK 25, `-f 2 -wi 5 -i 10 -prof gc`):

| bench | µs/op | B/op | the frame touches |
|---|---:|---:|---|
| `publish` | 3.530 | 21,404 | entities the tree reads |
| `publishIdle` | 1.311 | 12,200 | entities it does not |
| `publishSet` | 10.197 | 40,004 | entities the sets read |
| `publishIdleSet` | 1.686 | 12,360 | entities they do not |

So the relevance CHECK is already cheap — 1.3 µs, and scanning every candidate set to find none
touched adds 0.4 µs. What is not cheap is the 12.2 kB floor underneath it: `Patches.beforeSnapshot`
rebuilds the state map and `MemberGraph.syncMembers` builds a delta entry per set before anything
asks whether the frame matters, and `Server.recordFrame` runs once PER SLUG.

A local pre-filter could remove that floor. An HA-side filter removes it **and** the JSON
serialisation, the network bytes, the parse and the `StateStore` ingest above it. Same place, more
of it, so only the second is worth building.

HA supports it, undocumented in the WS reference but plain in
`homeassistant/components/websocket_api/commands.py`:

```python
vol.Required("type"): "subscribe_entities",
vol.Optional("entity_ids"): cv.entity_ids,
**INCLUDE_EXCLUDE_BASE_FILTER_SCHEMA.schema,
```

with the handler doing `entity_ids = set(msg.get("entity_ids", [])) or None` and gating both the
opening snapshot and every later event on `not entity_ids or state.entity_id in entity_ids`.

**An empty list means EVERY entity, not none.** That `or None` is the trap this plan has to design
around, not a detail.

## The set is not `Dashboard.referencedEntities`

`referencedEntities` (`Dashboard.scala:985`) is the ADR 0023 action bound — "does this dashboard
NAME this entity", the question a `call_service` POST is held to. Its walk covers slots, subjects,
set candidates and clause nodes. It does **not** descend into:

- a set member clause's `when` guard, whose predicate may name a different entity
  (`MemberGraph.movedBy` builds that edge and calls it out: without it "the node is never woken by
  the thing it counts")
- a surface's `Activation.State` condition (`SurfaceGraph.stateGroupEntities`)

Subscribing to `referencedEntities` would therefore leave flips and membership changes silently
never firing — the failure would look like a dashboard that renders correctly and then stops
reacting. Two different questions, two different sets; reusing the value would fake one with the
other.

So: a new `Dashboard.watchedEntities`, a static walk that additionally descends into clause guards
and surface activations. Pure, on the model, testable without a `Renderer`, and asserted to be a
superset of `referencedEntities`.

## Shape

1. **`Dashboard.watchedEntities`** — the union above. Property test: every entity any of
   `Renderer.componentsFor`, `MemberGraph.affected` or `SurfaceGraph`'s condition index can be
   woken by is in it.
2. **`subscribe_entities(entity_ids: Option[List[String]])`** in `ws/protocol/client.scala`, and
   `HomeAssistantApi.entities(ids: Option[Set[String]])`. `None` keeps today's whole-house
   subscription; `Some(empty)` must never reach the wire (see the trap above).
3. **`HaFeed` takes a `Signal[IO, Option[Set[String]]]`** of what is wanted and re-subscribes when
   it changes, inside the existing connection.
4. **`ServerApp` derives that signal** from the site registry: the union of every registered
   slug's `watchedEntities`, recomputed on every renderer swap.

### Rotation: `switchMap`, and why not `Hotswap`

`Hotswap` acquires the new resource before releasing the old, so a swap overlaps and the window
duplicates events rather than dropping them — which the store would absorb (`EntityState.stale`
drops a re-sent state carrying the same `last_updated`).

But the gap `switchMap` leaves is not lossy either, for the same reason a RECONNECT is not:
`subscribe_entities` opens with the full state of the subscribed set, so anything that moved during
the window arrives in the new subscription's first frame. `HaFeed.runConnection` already documents
exactly this argument for the reconnect case ("a delta that never arrived ... is superseded by the
next full set rather than replayed").

Both are correct, so this takes the one that is already a stream pipeline — the pump is
`frames.chunks.evalMap(store.applyEntities)`, and `switchMap` over `Stream.resource` keeps it one
mechanism instead of adding a second next to it.

### Boot and the empty set

The subscription cannot start narrow: `HaFeed.resource` does not hand out a feed until the first
frame has landed, and at that point no dashboard has been built, so the wanted set is unknown.

- unknown (boot, nothing registered yet) → `None` → whole house, exactly today's behaviour, so
  `seeded` completes and boot proceeds
- known and non-empty → `Some(ids)` → narrowed
- known and EMPTY (every dashboard removed) → no subscription at all, rather than `Some(Nil)`,
  which HA reads as the whole house

### Registry-driven rebuilds

`DumpRefresh` can rebuild a dashboard, which can change its `watchedEntities`. That is already
covered by deriving the signal from the renderer rather than computing it once: a rebuild swaps the
renderer, the union recomputes, the subscription rotates.

## Open questions

- ~~Does anything else depend on the store holding the whole house?~~ **No.** Every read is
  `Server`'s, and each is keyed by entities a renderer names (`entitiesForNode`, the render
  states, the session's snapshot). `RegistryDump.snapshot` takes its OWN unfiltered
  `subscribe_entities` on the build path, and must keep doing so: the dump is what an author
  writes the next dashboard against, so it has to offer entities nothing references yet.
- Entities that LEAVE the set keep their last value in the store. Harmless (nothing reads them)
  but it makes the store's contents no longer "the house". Prune on narrow, or document?
- **Not yet verified against a live instance.** Every test is against the fake. What HA does with
  a mid-connection re-subscribe carrying a different `entity_ids` is the thing to watch.
