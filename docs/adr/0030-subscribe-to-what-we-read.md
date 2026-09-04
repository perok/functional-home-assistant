# ADR 0030 — Subscribe to what we read

The upstream `subscribe_entities` carries the entities the registered dashboards can
actually be woken by, not the whole house.

## Context

`HomeAssistantApi.entities` sent `subscribe_entities()` with no arguments. Every state
change in the house — 1070 entities on the dev instance, where a dashboard reads a few
dozen — crossed the wire, was parsed, was ingested by `StateStore`, and drove a
publisher pass in `Server.recordFrame` **once per registered slug**.

The relevance check itself was never the problem. Measured (`RenderBench`, JDK 25,
`-f 2 -wi 5 -i 10 -prof gc`):

| bench | µs/op | B/op | the frame touches |
|---|---:|---:|---|
| `publish` | 3.530 | 21,404 | entities the tree reads |
| `publishIdle` | 1.311 | 12,200 | entities it does not |
| `publishSet` | 10.197 | 40,004 | entities the sets read |
| `publishIdleSet` | 1.686 | 12,360 | entities they do not |

An irrelevant frame costs 1.3 µs, and scanning every candidate set to discover none was
touched adds 0.4 µs — `MemberSource.affected` is a map lookup and `groupOf` hits the
cached index. What is not free is the **12.2 kB floor underneath it**:
`Patches.beforeSnapshot` rebuilds the state map and `MemberGraph.syncMembers` builds a
delta entry per set *before* anything asks whether the frame matters, deliberately, so
that membership tracks the state stream rather than who is watching.

A local pre-filter could remove that floor. Filtering HA-side removes it **and** the
serialisation, the bytes, the parse and the ingest above it. Same place, more of it, so
only the second is worth building.

HA supports it, undocumented in the WebSocket reference but plain in
`homeassistant/components/websocket_api/commands.py`:

```python
vol.Required("type"): "subscribe_entities",
vol.Optional("entity_ids"): cv.entity_ids,
**INCLUDE_EXCLUDE_BASE_FILTER_SCHEMA.schema,
```

with the handler gating both the opening snapshot and every later event on
`not entity_ids or state.entity_id in entity_ids`.

## The decision

`HaFeed` holds a `Signal[IO, Option[Set[String]]]` of what is wanted and re-subscribes
when it changes. `ServerApp.narrowFeed` drives it from `LiveSite.watchedEntities`, the
union over every registered slug's `Dashboard.watchedEntities`.

### The set is not `referencedEntities`

`Dashboard.referencedEntities` is the ADR 0023 action bound — "does this dashboard NAME
this entity", the question a `call_service` POST is held to — and it walks what is
**rendered**: slots, subjects, set candidates, clause nodes. It does not descend into

- a set member clause's `when` guard, whose predicate may name an entity the member does
  not render ("show the hall light while the hall sensor is on"), or
- a surface's `Activation.State` condition, the entity a flip hangs on.

Subscribing to that set would leave a dashboard that paints correctly and then **never
reacts**. No rendering test can see it, because the first paint is right; the flip and
the membership change are exactly the two things whose deciding entity is off-screen.

So `Dashboard.watchedEntities` is a separate value — a superset, walking what decides as
well as what is drawn. The two must not be merged in either direction: widening the
action bound would let a dashboard act on an entity it only reads.

### Three constraints the protocol imposes

- **An empty `entity_ids` means EVERY entity, not none.** HA reads it as
  `set(...) or None`. Wanting nothing therefore opens no subscription at all, rather
  than sending an empty list.
- **`None` is the boot value.** `HaFeed.resource` does not hand out a feed until the
  first frame lands, and at that point no dashboard has been built, so nothing knows
  which entities matter — and it is that unfiltered opening frame that fills the store
  the boot is waiting for. `narrowFeed` takes over once the registry exists, and the
  runtime never re-enters "unknown".
- **An absent filter must be omitted, not sent as null.** `cv.entity_ids` rejects null,
  so a derived encoder's `"entity_ids":null` fails the whole subscription — with an
  error that names the filter rather than the encoder. Pinned in
  `SubscribeEntitiesSuite`.

### Rotation is `switchMap`, not `Hotswap`

`Hotswap` acquires the new resource before releasing the old, so a swap overlaps and the
window duplicates events — which the store absorbs, since `EntityState.stale` drops a
re-sent state carrying the same `last_updated`.

The gap `switchMap` leaves is not lossy either, for the same reason a RECONNECT is not:
`subscribe_entities` opens with the full state of its set, so anything that moved during
the window arrives in the new subscription's first frame. `HaFeed.runConnection` already
makes that argument for the outage case.

Both are correct, so this takes the one that is already a stream pipeline — the pump is
`frames.chunks.evalMap(store.applyEntities)` — rather than adding a second mechanism
beside it.

One consequence has to be handled explicitly: under `switchMap`, a subscription that
ends **on its own** (the transport closes every route when the socket dies) would leave
the run waiting for a `wanted` that never arrives, and the feed would stay dark instead
of reconnecting. A rotation does not reach that path, because switching interrupts the
inner stream rather than letting it complete.

### The dump stays unfiltered

`RegistryDump.snapshot` takes its own `subscribe_entities` with no filter, and must keep
doing so: the dump is what an author writes the NEXT dashboard against, so it has to
offer every entity, including the ones nothing references yet.

It is the only other consumer. Every runtime read of the store is `Server`'s, and each is
keyed by an entity a renderer names (`entitiesForNode`, the render states, a session's
snapshot) — nothing iterates the store expecting the house.

## Consequences

- A house whose dashboards read fifty of a thousand entities stops paying for the other
  950 at every layer from the socket up.
- The subscription is now **derived state**, so it moves when the registry does: a
  reload, a `push`, or a `DumpRefresh` swap re-derives the union and rotates the
  subscription. That is why the signal is built from the renderers rather than computed
  once at boot.
- Entities that LEAVE the set keep their last value in the store. Harmless — nothing
  reads them — but the store's contents are no longer "the house". Not yet decided
  whether to prune on narrow.
- **Not verified against a live instance.** Every test is against `FakeHomeAssistant`,
  which applies the filter rather than merely recording it, so a wrong entity set fails
  here rather than only in production. What real HA does with a mid-connection
  re-subscribe carrying different `entity_ids` is still to watch.
