# Plan: functional end-to-end tests with a stubbed Home Assistant

Status: **plan, not yet implemented** (per the `docs/plan-*.md` convention). This
document is the agreed design for reshaping the `fh-datastar-view` test suite; the
"Work plan" section at the end is the checklist to execute against.

## Problem

The runtime's job is a behaviour: *a live Home Assistant changes, and the browser sees
the change* — page loads reflect current state, state-changed events push SSE fragments,
and clicking a control calls a service back into HA. Today there is **no test that
exercises that whole loop**. What we have instead:

- `ServerSuite` / `RendererSuite` reach *into* the machine. Each test hand-assembles a
  `Dashboard` (a `cards` Map + a `LayoutNode` tree + `surfaces`), hand-builds
  `EntityState` maps, seeds `StateStore.inMemory`, wires a bare `Server(...)`, and calls
  internal methods (`sharedPatches`, `renderNodeById`, `flipStateGroup`). They are precise
  and worth keeping as **unit** tests of the diff/churn invariants — but they are not
  end-to-end, and their ceremony (the same `cards`/`LayoutNode`/`SlotSource` scaffolding
  re-declared per suite) obscures the behaviour under test.
- The only `HomeAssistantApi` in the tests is `ServerSuite.StubApi`, whose every method
  raises `NotImplementedError`. It exists to prove *the SSE path never touches HA* — the
  opposite of a fixture. There is nothing that stands in for a *live* HA with real entities
  and a timeline of changes.
- "Third-party state" leaks in two ways the user flagged: (1) `sbt dashboardBuild` and any
  manual run need the live instance at `192.168.1.174:8123`; (2) each suite invents its own
  ad-hoc entity/dashboard shapes, so there is no single, reviewable picture of "the system
  under test."

## Goal

A layer of **functional end-to-end tests** that:

1. Own **no third-party state**. Everything the test needs — the entities, their initial
   readings, the sequence of changes over time, the dashboard — is static, in-repo, and
   readable in one place. No network, no live HA, no `192.168.*`.
2. Drive **high-level behaviour** through the real wiring: `StateStore.create` → `Server`
   routes → HTTP/SSE, exactly as `ServerApp` assembles them. Assertions are at the HTTP
   boundary (response HTML, streamed SSE fragments, recorded service calls), not on private
   methods.
3. Stand on a **`FakeHomeAssistant`** — a real implementation of `HomeAssistantApi[IO]`
   seeded from a static fixture that models a live system and can emit changes over time,
   replacing the "everything throws" `StubApi` for the paths that matter.

The existing fine-grained suites are **narrowed and de-duplicated**, not deleted: the
churn/membership-delta/flip invariants they pin are real and hard to re-derive from the
outside. They move onto a shared testkit so the scaffolding stops being copy-pasted.

## The seam we build on

The runtime touches HA through exactly three `HomeAssistantApi[IO]` methods (verified in
`StateStore.scala` and `Server.scala`):

| Method | Used by | Role |
|---|---|---|
| `getStates: IO[List[GetStatesData]]` | `StateStore.seed` | the initial full snapshot |
| `event(Some("state_changed")): Resource[IO, QueueSource[IO, Event]]` | `StateStore.create` background fiber | the live change feed |
| `callService(domain, service, entityId, data): IO[Json]` | `Server.callService` (action POST) | control → HA |

The other twelve methods (device/entity registry, manifests, templates, triggers…) are
**not on the runtime hot path** — `ServerApp` gets its dashboard from the Pkl build, not
from these. So `FakeHomeAssistant` implements the three above with real behaviour and
leaves the rest raising `NotImplementedError` (same discipline as `StubApi`: an unexpected
call is a loud test failure, not a silent stub).

`StateStore.create(api)` is the exact production entry point — the fake plugs in there and
the whole store→topic→renderer→SSE machine runs unmodified. `StateStore.inMemory` stays as
the *micro*-seam for tests that only need to poke one `update`.

## Design

### 1. The fixture — one static picture of a live system

A single object, `HouseFixture` (working name), under a new
`src/test/scala/fh/view/testkit/`. It declares a small but cross-domain house — enough to
exercise the interesting render paths, small enough to read at a glance. Suggested cast:

- `light.kitchen` (on, `brightness: 180`, `friendly_name`)
- `light.living_room` (off — exercises the off/`null`-brightness default path)
- `sensor.outside_temp` (`12.4`, `unit_of_measurement: °C`, `device_class: temperature`)
- `climate.hallway` (`heat`, `current_temperature`, `temperature`)
- `binary_sensor.front_door` (off — for dynamic-group membership tests)
- `media_player.tv` (`paused` — generic-entity fallback)

Each entry is a plain testkit type:

```scala
final case class FixtureEntity(
    entityId: String,
    state: String,
    attributes: Map[String, Json] = Map.empty
)
```

This is the **single source of truth**. From it we derive, mechanically, both faces HA
presents:

- `toGetStatesData: GetStatesData` — for `getStates` (the smithy4s type; `state` and
  attributes go through `smithy4s.Document`, `friendly_name`/`device_class` into the typed
  fields, everything else into `unknown`). A small builder in the testkit; write it once.
- `toEventState(...) : Event.EventData` — for a `state_changed` event when this entity
  changes (`old_state`/`new_state` are `EventDataState`, full attributes).

Because both come from the same list, "the dump the dashboard was built against" and "the
live state the runtime serves" cannot drift — the drift that live-HA testing invites is
designed out.

> Round-trip check to keep the builder honest: `HouseFixture` → `toGetStatesData` →
> `StateStore.seed`'s conversion → `EntityState` must reproduce the original state +
> attributes. One testkit-level test pins that, so the rest of the suite can trust the
> fixture.

### 2. `FakeHomeAssistant` — the stubbed real system

```scala
final class FakeHomeAssistant private (
    stateRef: Ref[IO, Map[String, FixtureEntity]],
    events:   Queue[IO, Event],
    calls:    Ref[IO, Vector[ServiceCall]]
) extends HomeAssistantApi[IO]:

  // --- the three real methods ---
  def getStates = stateRef.get.map(_.values.map(_.toGetStatesData).toList)

  def event(event: Option[String]) =
    // hand back the SAME queue the timeline pushes onto; the store's fiber drains it
    Resource.pure(events)  // QueueSource view

  def callService(domain, service, entityId, data) =
    calls.update(_ :+ ServiceCall(domain, service, entityId, data)).as(Json.obj())

  // --- test-driving surface (not part of the trait) ---
  def emit(entityId, newState, attrs): IO[Unit]  // mutate stateRef + enqueue a state_changed Event
  def recordedCalls: IO[Vector[ServiceCall]]

  // --- the other twelve: NotImplementedError, like StubApi ---
```

Notes that matter:

- **`event` returns the live queue.** `StateStore.create` subscribes by draining this
  queue in a background fiber; `emit` pushing onto it *is* "a change over time." A test
  says `fake.emit("light.kitchen", "off", Map.empty)` and the store applies it exactly as a
  real WS frame would.
- **Determinism.** Topic/queue delivery only reaches already-attached consumers.
  `StateStore` already exposes `changeSubscribers` (a `Stream[IO,Int]`) precisely so a test
  can await the background fiber before emitting — reuse it. No sleeps.
- **`callService` records, does not simulate HA.** Baking "turn_on ⇒ state on" into the
  fake would encode HA's semantics we don't own. Default behaviour: record the call and
  return `{}`. A test that wants the round-trip drives it explicitly:
  `POST /sse/action/...` → assert `recordedCalls` → `fake.emit(...)` the resulting state →
  assert the SSE fragment. (Optional later: a pluggable `onCall` handler for tests that
  want the fake to auto-emit; keep it opt-in.)
- **`ServiceCall`** is a small testkit case class `(domain, service, entityId, data: Json)`.

### 3. The dashboard under test — real, from the same fixture

For a test to be genuinely end-to-end the dashboard should be *built*, not hand-assembled.
Two tiers, both fixture-derived:

- **Tier A (preferred, reuses the Pkl track):** a tiny fixture entry `.pkl` under
  `src/test/resources/dashboards/` (or a temp dir, mirroring `PklBuildSuite.copyLib`) that
  references the fixture entities, evaluated through the real `DashboardBuild.reevaluate`
  against a `lib/dump.pkl` generated from `HouseFixture`. This is the truest end-to-end: Pkl
  → model → renderer → SSE, no live fetch. `PklBuildSuite` already proves fake dumps + real
  lib modules work; this extends that to the *runtime* half.
- **Tier B (fallback for focused behaviour):** a hand-built `Dashboard` via shared testkit
  builders (below). Lower fidelity but zero Pkl/dump ceremony — good for a test whose point
  is one render path, not the authoring layer.

Start Tier B (fast to land, unblocks the pattern), add one Tier A smoke as the capstone.

### 4. Shared testkit builders (kill the copy-paste)

Extract the scaffolding that `RendererSuite`/`ServerSuite` re-declare into
`testkit/DashboardBuilders.scala`:

- `card(name, template, slots*)`, the common `col`/`row`/`card`/`btn` card set
- `component(card, slots)`, `col(kids*)`, `row(kids*)` layout helpers
- `st(id, state, attrs*)` → `EntityState`
- a `serve(dashboard, fake): Resource[IO, Client-ish]` helper that assembles the **real**
  `Server` the way `ServerApp` does (via `Server.resource`, running
  `sharedPatchPublishers`) against the fake, returning something a test can send requests
  to and read SSE from.

`ServerSuite`'s `awaitMarker`/SSE-collection helper and the `changeSubscribers`/`subscribers`
readiness gates move here too — they are the reusable "drive an SSE connection
deterministically" primitives.

### 5. The functional tests (`src/test/scala/fh/view/functional/`)

Each reads as a behaviour, top to bottom, against `HouseFixture` + `FakeHomeAssistant`:

1. **Initial render reflects the snapshot.** `GET /` → HTML contains the kitchen light on,
   the outside temp `12.4 °C`, the off living-room light showing its default.
2. **A state change pushes a fragment.** Open `/sse/dashboard/<slug>/patch`; await the
   store subscription; `fake.emit("sensor.outside_temp", "13.1", …)`; assert the SSE stream
   delivers a patch whose fragment shows `13.1`. (The real diff/cache path decides *what*
   ships — the test only asserts the observable outcome.)
3. **An unchanged emit ships nothing.** Emit the same value; assert no fragment (pins the
   `StateStore.update` "publish only on real change" contract, end-to-end).
4. **A control calls a service.** `POST /sse/action/light/toggle/light.kitchen` → assert
   `recordedCalls` has exactly that call; response is the empty SSE ack.
5. **A value control carries data.** `POST /sse/action/light/turn_on/light.kitchen/brightness/200`
   → assert the recorded `serviceData` is `{ "brightness": 200 }`.
6. **Round-trip.** Action POST, then `fake.emit` the consequent state, then assert the SSE
   fragment reflects it — the full control→HA→feed→browser loop, no live HA.
7. **Dynamic-group membership over time** (if a group is in the fixture dashboard): an
   entity crossing the query boundary via `emit` produces an add/remove patch.

## What changes, concretely

| File | Change |
|---|---|
| `testkit/HouseFixture.scala` | **new** — the static system + `GetStatesData`/`Event` builders |
| `testkit/FakeHomeAssistant.scala` | **new** — `HomeAssistantApi[IO]` impl, `emit`, `recordedCalls` |
| `testkit/DashboardBuilders.scala` | **new** — shared card/layout/state builders + `serve` + SSE helpers |
| `functional/*Suite.scala` | **new** — the behaviour tests above |
| `runtime/ServerSuite.scala` | narrow to the diff/fan-out unit invariants; drop `StubApi` (use the testkit), drop the re-declared scaffolding |
| `runtime/RendererSuite.scala` | keep the render/transform/dispatch units; pull scaffolding from the testkit |
| `build/PklBuildSuite.scala` | decoupled from the shipped dashboards + theme: full-pipeline + wire-snapshot tests now run against TEST-OWNED fixture entries (`fixtureFeatures`/`fixtureSurfaces` via `PklFixture`) with a dummy theme, and the snapshot strips `theme` — so real-dashboard edits and theme CSS no longer break it (browser smoke will cover shipped/demo dashboards + design). Inline-probe tests unchanged |
| `build/BuildPhaseSuite.scala` | unchanged (already fake-dump based) |

## Non-goals

- Not removing the wire-format snapshot safety net — it stays as the *authoring/composition*
  contract guard, but is decoupled from the shipped dashboards + theme CSS: it snapshots
  test-owned fixture entries with `theme` stripped (see the `PklBuildSuite` row above).
- Not testing the browser (Datastar attribute *behaviour* in a real DOM) — that is the
  separate Playwright smoke plan (`docs/plan-playwright-smoke-tests.md`).
- Not simulating HA service semantics inside the fake (see §2).

## Risks / watch-items

- **Building `GetStatesData`** (smithy4s + `Document`) is the fiddliest part; write and
  test the builder first (the round-trip check in §1), then everything downstream is plain.
- **SSE readiness**: always gate on `changeSubscribers`/topic `subscribers` before emitting;
  never `sleep`. The pattern already exists in `ServerSuite` — lift it verbatim.
- **`event` ignoring its filter arg**: the real impl hardcodes `state_changed` anyway
  (`HomeAssistantApi.fromLowLevel`), so the fake ignoring `event`'s argument matches
  production.

## Work plan (execution checklist)

1. [ ] `FixtureEntity` + `HouseFixture` + `toGetStatesData` builder; round-trip test through
   `StateStore.seed`'s conversion.
2. [ ] `FakeHomeAssistant` (three real methods + `emit`/`recordedCalls`; rest `NotImplementedError`).
3. [ ] `DashboardBuilders` testkit: card/layout/state helpers + `serve` + SSE-await helpers
   (lift from `ServerSuite`).
4. [ ] First functional suite: initial render + state-change-pushes-fragment + no-op emit
   (Tier B dashboard).
5. [ ] Service-call functional tests (toggle, value-carrying, round-trip).
6. [x] One Tier-A test: a fixture `.pkl` built via `DashboardBuild` against a
   `HouseFixture`-derived dump, served against the fake. *(Done:
   `FixtureEntity.toDumpEntry`/`HouseFixture.transformedDump` derive the authoring
   dump from the same fixture the runtime serves; `testkit/PklFixture` builds a
   real `Dashboard` through `SourceEval.eval` → `hoistInlineSurfaces` → decode;
   `functional/PklDashboardBehaviourSuite` renders + streams it against the fake.)*
7. [x] Narrow `ServerSuite`/`RendererSuite` onto the testkit; delete duplicated scaffolding
   and the local `StubApi`. *(Done: `testkit/DashboardBuilders` holds the shared
   `st`/`lit`/`col`/`row`/`component` constructors both suites re-declared; `ServerSuite.StubApi`
   is gone — the unit tests now stand up the testkit `FakeHomeAssistant` (still raises on any
   registry call). Bespoke card maps + dashboards stay per-suite, since their exact HTML is the
   assertion. No test removed — the churn/flip/membership invariants are all preserved.)*
8. [x] `sbt 'fh-datastar-view/testFull'` green (135 tests); `sbt scalafmt`.
