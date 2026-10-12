# ADR 0009 — How we test: functional tests over a fake HA, then browser smoke

- **Status:** Accepted
- **Date:** 2026-07-14
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The runtime's job is one behaviour: *a live Home Assistant changes, and the
browser sees it* — page loads reflect current state, `state_changed` events push
SSE fragments, and a control click calls a service back into HA. Two failure
modes shaped how we test it:

- **Live-HA leakage.** Anything that talks to the real instance
  (`192.168.1.174:8123`) drags third-party state into the test: it needs the
  network, it drifts, and no two runs are the same. Tests must own *all* their
  state.
- **Reach-in unit tests.** The temptation is to hand-assemble a `Dashboard`,
  seed `StateStore.inMemory`, and call internal methods (`sharedPatches`,
  `flipStateGroup`). Those pin real invariants but read as machine internals, not
  behaviour, and their scaffolding gets copy-pasted per suite.

## Decision

Two layers, one shared testkit, no live HA anywhere in the test tree.

### 1. Functional tests own the world and assert at the boundary

A functional test **builds the world declaratively, drives it through the real
wiring, and asserts only what an outside observer could see** — response HTML,
streamed SSE fragments, recorded service calls. Never a private method.

The world is static and in-repo. Its spine is **`FakeHomeAssistant`**, a real
`HomeAssistantApi[IO]` that implements only the three methods the runtime hot
path touches and raises `NotImplementedError` on the other twelve (an unexpected
call is a loud failure, not a silent stub):

| Method | Role |
|---|---|
| `getStates` | the initial full snapshot, from the seeded fixture |
| `event(Some("state_changed"))` | the live feed — hands back the queue `emit` pushes onto |
| `callService(...)` | records the call, returns `{}` — does **not** simulate HA semantics |

`StateStore.create(api)` is the production entry point; the fake plugs in there
and the whole store → topic → renderer → SSE machine runs unmodified. Time is
driven through `fake.emit(id, state, attrs)` — a test is a closed system. The
fake records service calls but never invents their consequences: a round-trip
test POSTs the action, asserts `recordedCalls`, then `emit`s the resulting state
itself. Baking "turn_on ⇒ on" into the fake would encode HA semantics we don't
own.

`TestServer.resource(dashboard, entities)` is `ServerApp.assemble` with only its edges
stubbed (the HA socket, HA's token endpoint, the asset CDN), driven in-process through
the same `HttpApp` production binds.
`ts.connect(query)` opens a live client; `ts.change(id, state)` and `ts.frame(entities*)`
send one HA frame and return once every held session has pulled it and every client's bytes
have landed, a gate asked of the server, since a client owed nothing receives nothing.
`ts.sentAfter(frames)` is the two together, minus the opening block.

### 2. The fixture and dashboard derive from one source

`FixtureEntity(entityId, state, attributes)` is the single source of truth; both
faces HA presents — `getStates` data and `state_changed` events — derive from it
mechanically, so "the dump the dashboard was built against" and "the live state
served" cannot drift. `HouseFixture` is the shared cross-domain house
(`kitchenLight` on/bri180, `livingRoomLight` off, `outsideTemp`, `hallwayClimate`,
`frontDoor`, `tv`); a round-trip test keeps the smithy4s `GetStatesData` builder
honest.

The dashboard under test is *built*, two tiers:

- **Tier A** — a fixture `.pkl` evaluated through the real `DashboardBuild`
  pipeline against a `HouseFixture`-derived `lib/dump.pkl` (`PklFixture`,
  `SmokeDashboard`). The truest end-to-end: Pkl → model → renderer → SSE, no live
  fetch.
- **Tier B** — a hand-built `Dashboard` via `FixtureDashboard` + the shared
  `DashboardBuilders` combinators. Lower fidelity, zero Pkl ceremony — for a test
  whose point is one render path.

### 3. `Scene` keeps the world minimal and self-consistent

A test declares the cards it exercises; **`Scene` auto-seeds the entities the
dashboard references** (every slot `entityId` + component subject, across the
main layout and surfaces), resolved against `HouseFixture`. So placing a card
brings its entity with it — no hand-listed entity roster to keep in sync.

`Scene.empty` accumulates cards via `.card`; `Scene.of(dashboard)` wraps a
prebuilt (Tier-A) dashboard. `.entity(..)/.entities(..)` add entities *beyond*
what the dashboard names — a control-only click target, a **dynamic-group member
matched by query**, a **state-activated surface's condition entity** — and double
as a resolution source that can restate a registry entity at a non-default state.
A referenced id that nothing supplies fails loudly. `FixtureSeedSuite` pins
`HouseFixture.all` as the guardrail.

### 4. Smoke tests cover only what a browser can see

The functional layer covers the server side of the loop completely. Playwright
smoke tests add the thin outer check that the *browser half* is wired — the part
CLAUDE.md notes "cannot be verified from the terminal":

- Datastar parsing the colon-syntax `data-*` attributes and wiring the DOM.
- An SSE `datastar-patch-elements` frame actually **morphing** the live DOM, not
  just being emitted on the wire.
- Signal-driven UI with no server round-trip: tabs, popup open/close, slider
  binding.

Playwright runs **in-JVM** as a munit suite (`com.microsoft.playwright`), driving
real Chromium against the *same in-process server* via `TestServer.served` (the
one addition over `resource`: an ember bind on an ephemeral port). This collapses
two harnesses into one and deletes any `/__test__/*` control plane — the suite
holds the `FakeHomeAssistant` reference directly, so `fake.emit` and
`fake.recordedCalls` are typed calls, not an HTTP back-channel. State is still
driven in-process; the browser only observes. No node/TypeScript project.

The smoke suites (`SmokeSuite.withPage(scene)`): `RenderSmoke` (page alive, no
page errors), `LiveUpdateSmoke` (emit → DOM morphs), `ControlSmoke` (click → HA →
back), `UiSmoke` (tabs/popup/slider signals), and `ComponentVisualSuite` —
component-level PNG byte-identity snapshots (fixed viewport + `settle` kills
animation/font noise; same `FH_UPDATE_SNAPSHOTS` regenerate gate as the wire
snapshots). Anti-flake rules: never `sleep` (Playwright retrying assertions or a
bounded `eventually` poll), drive time only through `fake.emit`, one browser per
suite with a fresh `BrowserContext` + bound server per test.

#### Known gap: every smoke suite drives ONE page, and never a reconnect

`withPage` opens a single `Page` and holds it open for the test. Nothing here has
two browsers on one dashboard at once, and nothing backgrounds a tab or lets its
SSE stream drop and re-open. So the browser half of the loop is checked only in
its steady state, by one observer.

That is a real blind spot, not a theoretical one: both halves of the bug fixed in
PR #76 lived in it. The page shipped `filterSignals:{include:'null'}` — so every
reconnect arrived carrying no cursor, no `conn` and no tab selection — and a
reconnecting session was answered from `holds` it had never acknowledged, leaving
a returning tab permanently stale. A single always-connected page sees neither,
and the multi-client tests that would have (`LiveStreamSuite`) are wire-level,
where there is no Datastar client to close and reopen a stream.

Closing it means two `Page`s off one `BrowserContext` (two tabs of one browser,
which is also what shares `sessionStorage`), one acting while the other observes,
plus a visibility round trip on the observer. Deliberately not done here: the
server-side contract is now pinned by `AckedResumeSuite` at the level the failure
actually lives, and this would add browser time to every CI run to re-check it
one layer out. Worth adding when a bug is found that only a second real page can
see.

#### Visual snapshots compare exactly, against CI's own render

`VisualSnapshot` fails on any changed pixel. Measured on 2026-10-12 by failing
every snapshot on purpose and counting: CI renders an unchanged page
byte-identically (four of nine snapshots at zero changed pixels), and every
nonzero count was a REAL change the old tolerances had let through — the off
look #527 gave a badge (105–154 px per card, inside a 0.3 % budget) and a
removed 1px hairline (1,955 px, each a channel delta of at most 31, under
pixelmatch's 0.1 threshold). Five baselines had been stale for two PRs, each
one green.

A ratio budget was always the wrong shape: it lets a LARGE snapshot absorb the
regression it exists for (the same 190 missing glyphs failed `lock-controls`
and passed `full-dashboard`). The pixelmatch knobs — threshold, anti-alias
skip, ratio, dimension slack — stay in `VisualSnapshot` at zero, so an
environment that genuinely varies can be loosened with a measured number.

Two consequences:

- **A baseline is CI's image.** A dev machine rasterizes text and the native
  range input differently, so a local visual run fails and says nothing; and
  regenerating locally (`dashboardVisualSnapshotsUpdate`) records that machine.
  Let CI fail and commit its `.actual.png` from the uploaded artifact.
- **A Playwright or Chromium bump fails every snapshot at once.** That is one
  deliberate rebaseline from CI's artifact, in the same PR as the bump.

### 5. Reach-in unit tests stay, narrowed

`RendererSuite`/`ServerSuite` keep the diff/churn/membership/flip invariants that
are real and hard to re-derive from the outside — but pull their scaffolding from
`DashboardBuilders` instead of re-declaring it, and stand up the testkit
`FakeHomeAssistant` rather than a bespoke everything-throws stub. Pure-function
suites (`RendererSuite` probes, `TransformSuite`, `AssetCacheSuite`,
`BuildPhaseSuite`, `PklBuildSuite`) are not `Scene` candidates — `Scene` can't
express their crafted inputs, and shouldn't try.

### 6. A test that names a byte on the wire is not a test you may edit

Refactors here routinely change types that tests construct — a signature, a `LiveSlug` field, a
`FragmentLog` shape. Changing those tests is part of the refactor. What is NOT part of a refactor is
changing a test that asserts on EMITTED SSE OUTPUT: `ServerSuite`'s stream tests,
`DatastarMorphContractSuite`, and the functional suites over the fake HA
(`DashboardBehaviourSuite`, `UseCaseSuite`, `PklDashboardBehaviourSuite`).

The distinction is worth holding while working, because the two look identical in a diff:

> Changing a test that names a TYPE is refactoring. Changing a test that names a BYTE on the wire is
> a behaviour change wearing a refactor's clothes.

A diff in one of those suites is a design question — say what moved and why it is correct — not a
test to update. `PklBuildSuite`'s wire-format snapshots are the same rule one layer down, for the
AUTHORING wire.

### 7. Real time by default, simulated per SUITE, and the real clock is a bug detector

`ServerHarness.simulateTime` is off: a suite runs on the real runtime unless it is ABOUT concurrent
interleavings (`RenderCacheContentionSuite`), where a seeded `TestControl` is what makes one
reproducible. Such a suite cannot FETCH A DOCUMENT: the page route streams its body through
`fs2.io.readOutputStream`, a pipe with two mutually-blocking sides, and `TestControl` ticks one
fiber on one thread — so whichever side is ticked first parks the only thread. The first attempt at
streaming hung 18 of 720 tests at their guards, a different subset per run.

**Per suite, not per test, and that is forced.** A per-test switch looks tighter, but suites reach
a document fetch through shared helpers (`AckedResumeSuite`, `SurfaceTapSuite`), so a per-test scan
mis-classifies them — and a mis-classified test does not fail, it HANGS. What simulated time bought
the suites that fetch documents was only poll-loop acceleration: every sleep in them is 5–300 ms and
the windows they exercise are adoption-window-sized.

**The real clock then found two production bugs that simulated time had been hiding**, which is the
reason to keep it rather than raise the guards:

- a displacement `interruptWhen` applied INSIDE `Server.untilRevoked`'s merge, so the response body
  of a displaced client never ended;
- `reloadRepaints` inferring transitions with `.drop(1)`, which discarded a renderer swap that
  landed before its subscription — a client left on a dashboard that no longer exists.

So: **a suite failure that runs its FULL timeout guard is "the event never arrived", not "slow
under load".** Raising the guard hides it. Chase it.

**A barrier must wait on a BYTE.** Every stream barrier in `SessionLifecycleSuite` originally
waited on session state — `awaitTenure(Held(1))`, `sessions.liveStreams` — and `adoptOrMint` sets
that in the request HANDLER, so all of them are satisfied before the response body has run a step.
Cancel or displace at that point and the bracketed registration never happened. The first byte off
the stream is the only evidence the body is live.

**Reproducing any of this needs a small machine.** These races appear at CI's core count and not on
a 22-core box; `-XX:ActiveProcessorCount=2` in a root `.jvmopts` is what makes them reproducible
(1-in-10 rather than never). Note that `-D` on the sbt command line, `SBT_OPTS` and `-J` all fail
to reach the test JVM — the long-lived sbt server captures its options at startup, and `.jvmopts`
is the one that lands.

**Suites run CONCURRENTLY, and there are two distinct ambient flakes.** `Test / fork` is false but
`Test / parallelExecution` is sbt's default **true** — measured, `show
fh-datastar-view/Test/parallelExecution` — so sbt runs up to one suite per core in the one JVM.
(An earlier version of this section asserted both were false and told the reader that making the
suites serial "proves nothing". That was wrong, and it is why the two flakes below went unseparated
for so long.) On a 22-core box that is far more concurrency than CI's two, so the two machines
reproduce DIFFERENT things, and neither is a superset of the other:

- **A pkl-core race — diagnosed, #226.** pkl's stdlib ASTs are Truffle nodes that specialize as
  they warm and are shared process-wide, so a per-call `Evaluator` does not isolate them; two
  threads evaluating at once can catch one mid-rewrite, surfacing as an NPE inside `pkl.semver` /
  `pkl.Project`. Measured 3 failures in 8 runs parallel, 0 in ~10 serial. `PklBuild.serialized`
  now holds one process-wide claim, and **every path that reaches Truffle takes it** — the two
  test-side evaluators and `LibPackage.effectivePin` (`Project.loadFromPath` evaluates) included.
- **A `PklDashboardBehaviourSuite` tab flake — #293, a test whose premise the harness does not
  guarantee.** The three tests with an off-then-on trigger fail as an ASSERTION ("never saw
  'Outside Temperature' after the opening block"), 4 runs in 12 at 22 cores. `fake.emit` offers to
  a queue and returns, so both events can be in flight before the connection's pull runs — and the
  pull coalesces deliberately (`doorbell.discrete`), so a pull that sees only the settled `on`
  diffs against what the client already has and correctly sends nothing. Spacing the two emits by
  300 ms is 0 failures in 12 at the same load. **A barrier must wait on the CONSEQUENCE**, here the
  `off` render reaching this connection, and that gate belongs in `TestServer` beside `awaitLive` —
  a sleep is what this ADR's anti-flake rules forbid.

  Two things this cost, worth not repeating: `_haDown:false` reads like evidence the feed dropped
  and is not (`session.haDown` starts `None`, so the first health emit always fires on a fresh
  connection), and "it still fails serially" was taken to mean the race was in the server — the
  coalescing window simply does not need suite parallelism, only load.

What still holds from the earlier investigation: the `Evaluator` is per call and `close()`d, module
cache dirs are per test, and concurrent evaluation does not serialize (8 evaluations on 8 threads:
7 ms against 11 ms sequential) — pkl is expensive COLD (769 ms engine build, 1 ms warm). Those
measurements say nothing about the stdlib ASTs, which is where #226's race actually lives.

Do not trust a single green `testFull`.

## Consequences

- **No test touches live HA.** `sbt 'fh-datastar-view/testFull'` is fully
  hermetic (browser install aside). `dashboardBuild` still needs the live
  instance; tests never do.
- **Behaviour reads top-to-bottom.** A functional test names a scene, a trigger,
  and a marker; the real diff/cache/churn machinery decides *what* ships and the
  test asserts only the observable outcome.
- **One fixture, no drift.** Authoring dump and served state derive from the same
  `FixtureEntity` list; `Scene` derives the entity roster from the dashboard.
- **The wire-format snapshots** (`PklBuildSuite`, ADR-adjacent to 0006/0008)
  remain the authoring/composition contract guard — decoupled from the shipped
  dashboards + theme so real-dashboard and CSS edits don't break them.

## Verification

`sbt 'fh-datastar-view/testFull'` green (158 tests): the functional suites
(`DashboardBehaviourSuite`, `PklDashboardBehaviourSuite`, `FixtureSeedSuite`)
drive the whole loop over the fake; the smoke suites drive real Chromium incl. 7
component visual snapshots; the narrowed `RendererSuite`/`ServerSuite` retain the
diff/churn/flip units. CI wiring for the cached Chromium install +
trace-on-failure artifact in `.github/workflows/cicd.yml` is the one open item.
