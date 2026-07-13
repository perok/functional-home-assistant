# Plan: Playwright smoke tests for the Datastar dashboard

Status: **plan, not yet implemented**. Depends on the `FakeHomeAssistant` + `HouseFixture` +
`TestServer` testkit from [`plan-functional-e2e-tests.md`](plan-functional-e2e-tests.md) — that
plan lands first; this one reuses its fake and server wiring to drive a real browser against
deterministic, static state.

Playwright runs **in-JVM** as a Scala munit suite via the first-party
[`com.microsoft.playwright:playwright`](https://playwright.dev/java/) library — **not** a separate
node/TypeScript project. See "Why in-JVM, not a node project" below for the rationale; it is the
decision that shapes the whole plan.

## Why Playwright at all

The Scala functional tests (plan 1) cover the server side of the loop end-to-end: state in →
SSE fragment out, click → service call. What they *cannot* see is the half that only exists in
a browser:

- Datastar actually parsing the `data-*` attributes (colon syntax: `data-on:click`,
  `data-signals`, `data-bind`) and wiring the DOM.
- An SSE `datastar-patch-elements` frame actually **morphing** the live DOM in place (not just
  being emitted on the wire).
- Signal-driven UI with no server round-trip: tab switching, popup open/close, brightness
  slider binding — the interactions ADR 0002/0005/0006 describe, which the CLAUDE.md notes
  "cannot be verified from the terminal."

These are exactly the regressions a wire-level test misses: a fragment ships correctly but the
`data-on:click` selector is wrong, or a multi-line SSE patch drops a continuation line in a real
`EventSource` (a class of bug `ServerSuite` already found once at the string level — Playwright
would catch it at the DOM level). **Smoke** scope: a handful of high-value "the whole thing is
wired and breathing" checks, not exhaustive UI coverage.

## Why in-JVM, not a node project

The functional suite already boots the **real** `Server` against `FakeHomeAssistant` in the test
JVM (`TestServer`, plan 1). Java Playwright lets the smoke suite drive a real Chromium against
*that same in-process server*, which collapses two test harnesses into one and — decisively —
**deletes the entire `/__test__/*` control plane**. A separate node process can only reach the
server over HTTP, so it needs a back-channel to inject state changes and read recorded calls; an
in-JVM test holds the `FakeHomeAssistant` reference directly:

| Concern | node project (old plan) | in-JVM Scala (this plan) |
|---|---|---|
| Trigger a live change | `POST /__test__/emit` → shim → `fake.emit` | `fake.emit(...)` **directly** — deterministic, no serialization |
| Assert a service call | `GET /__test__/calls` (JSON) | `fake.recordedCalls` **directly**, as a typed `Vector[ServiceCall]` |
| Per-test isolation | `POST /__test__/reset` | fresh `TestServer` resource per test — already the functional-suite pattern |
| Fixture / dashboard | re-declared in TS | reuses `HouseFixture` / a fixture Pkl dashboard as-is |
| Build & CI | sbt **and** node toolchain, `npm ci`, two runners | one sbt build, one runner (`testFull`), no node |
| Language | TS specs + Scala server main | Scala throughout (the project's only language) |

The one thing a browser test must add over `TestServer` is a **real bound TCP port** (the current
`TestServer` runs the http4s app in-process via `app.run(req)`, with no socket a browser could
reach). That is a small wiring addition (below), not a reason for a second process.

Trade-off accepted: browser automation in Scala is slightly chattier than in TypeScript, and the
JS trace-viewer/codegen tooling is more polished. Neither outweighs deleting the control plane and
the second toolchain. There is no existing TS test culture in this repo to match — it is Scala end
to end. `com.microsoft.playwright:playwright` is first-party Microsoft, on Maven Central (repo1,
proxy-allowed; latest `1.61.0` at time of writing), with a synchronous API that fits munit.

## Shape

```
modules/fh-datastar-view/
  src/test/scala/fh/view/smoke/
    Browser.scala        # munit Fixture: one Playwright + Chromium per suite (launch/close)
    SmokeSuite.scala      # base: boots a bound TestServer + a Page, per-test isolation
    RenderSmokeSuite.scala       # page loads, seeded entities present, no console errors
    LiveUpdateSmokeSuite.scala   # fake.emit -> DOM morphs (retrying assertion)
    ControlSmokeSuite.scala      # click a control -> recordedCalls -> round-trip morph
    UiSmokeSuite.scala           # tabs / popup / slider signals (no server round-trip)
```

No node project, no `package.json`, no `playwright.config.ts`, no `SmokeServerApp`, no
`/__test__/*` routes.

## The bound server (small `TestServer` addition)

`TestServer` (plan 1) exposes `server: Server` and drives it via `app.run(req)` — enough for HTTP
assertions, but a browser needs a listening socket. Add a resource that binds the **same**
`server.routes` via ember on an ephemeral port and yields the base URI:

```scala
object TestServer:
  /** Bind `server.routes` on 127.0.0.1:0 (OS-assigned port) via ember and yield
    * the running [[TestServer]] plus its base `Uri`. The browser navigates here;
    * state is still driven in-process through `fake.emit`. */
  def served(dashboard: Dashboard, entities: List[FixtureEntity])
      : Resource[IO, (TestServer, Uri)]
```

It reuses `TestServer.resource` for the wiring and adds only the `EmberServerBuilder` bind
(`host"127.0.0.1"`, `port"0"`, `server.routes.orNotFound`), reading the actual bound port back
from the `Server` handle. Nothing production changes; this is a test-scope convenience alongside
the existing `resource`.

### Assets / offline

`Server` defaults to `AssetCache.empty` (page references the Datastar CDN). Playwright *can* be
allowed to reach the CDN, but a hermetic CI run should serve Datastar locally. Two options, decide
at implementation:

- Reuse `AssetCache.build` (the runtime's own local-cache path) seeded from the CDN once, or
- Vendor a pinned `datastar.js` into `src/test/resources/` and have the bound `TestServer` serve
  it at the asset path.

Prefer local-vendored for hermeticity. Watch-item, not a blocker.

### Dashboard under test

Reuse plan 1's Tier-A path: the fixture `.pkl` built through `DashboardBuild` against a
`HouseFixture`-derived `lib/dump.pkl`, so the smoke run exercises real Pkl → model → renderer →
templates. It should include one of each interaction class so `UiSmokeSuite` has something to
click: a **tabs** surface, a **popup** trigger, a **slider** (brightness), and a plain
**stateCard** (for live-update). If the Tier-A capstone isn't ready, `FixtureDashboard` (Tier B)
covers render/live-update/control; the tabs/popup/slider checks wait on the richer Pkl entry.

## Browser lifecycle (munit fixtures)

- **Suite fixture** (`Browser.scala`): one `Playwright.create()` + `chromium.launch(headless)` per
  suite, closed in teardown. Cheap page creation per test off the shared browser.
- **Per-test fixture** (`SmokeSuite`): acquire `TestServer.served(...)` (fresh fake + server, so
  recorded calls and seeded state never bleed), open a `BrowserContext` + `Page`, `page.navigate`
  to the base URI, hand the test `(page, fake)`. Release both in teardown.

`FunFixture.map2` composes the two; a `ResourceFixture`-style bridge runs the cats-effect
`Resource[IO, (TestServer, Uri)]` acquire/release around each test.

## Browsers & the sandbox

- **This environment**: Chromium is preinstalled at `/opt/pw-browsers`;
  `PLAYWRIGHT_BROWSERS_PATH` + `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` are set. Java Playwright honors
  both, so it should find the preinstalled browser with no download. **Spike this first** (a
  10-line `chromium.launch()` that opens `about:blank`): confirm the library's bundled driver is
  compatible with that Chromium build. If the driver pins an incompatible version, set `channel` or
  `executablePath` to `/opt/pw-browsers/chromium` — do **not** run `playwright install`.
- **CI (GitHub Actions)**: the Java driver fetches its own browser on first run unless cached; add a
  cached `chromium` install step (the driver's `install` command, or the Maven Playwright CLI).
  Same cost as the node CLI — not a node-vs-JVM difference.

## The suites (smoke-level, one behaviour each)

1. **`RenderSmokeSuite` — the page is alive.** Navigate to the base URI. Assert the shell
   rendered, the kitchen light card shows "on", the outside-temp card shows `12.4 °C`, and there
   are **no console errors** (register a `page.onConsoleMessage` collector; fail on `error`). No
   interaction — "server up, template rendered, Datastar loaded."
2. **`LiveUpdateSmokeSuite` — SSE morphs the DOM.** Load the page; `fake.emit("sensor.outside_temp",
   "13.1", …)` **in-process**; `assertThat(tempCard).hasText(Pattern.compile("13.1"))` (Playwright's
   retrying assertion waits for the SSE patch to land — no manual sleeps). The check that only a
   browser can make: the frame is not just sent, it is *applied*.
3. **`ControlSmokeSuite` — click → HA → back.** Click the kitchen light toggle. Await
   `fake.recordedCalls` containing `ServiceCall("light", "toggle", "light.kitchen", …)` (poll the
   `IO` with a retrying helper, or assert after a DOM-observable settle). Then `fake.emit` the
   resulting `off` state and assert the card flips to "off" — the full browser round-trip.
4. **`UiSmokeSuite` — signal-driven UI, no round-trip.** Tabs: click tab B, assert panel B is
   shown and A hidden (the `data-signals`/cookie tier). Popup: click the trigger, assert the dialog
   opens; close it, assert gone. Slider: set brightness (prefer `fill`/keyboard over drag for
   headless reliability), assert the value-carrying action fired with the right `service_data` via
   `fake.recordedCalls`. These exercise the Datastar attribute wiring that has no server-side
   observable.

Keep each suite to its one assertion cluster; smoke suites earn their keep by being fast and
rarely flaky.

## CI integration

Extend `.github/workflows/cicd.yml`'s `ci` job (JDK 21 already present — **no Node needed**):

1. Cache + install the Playwright-for-Java browser (`chromium` only) before the test step. On the
   preinstalled-browser sandbox this is a no-op; on CI it's one cached step.
2. The smoke suites run **inside `sbt 'fh-datastar-view/testFull'`** — they are ordinary munit
   suites, no separate invocation. Optionally scope a `sbt 'fh-datastar-view/testOnly *Smoke*'`
   step *after* `testFull` for a legible, separately-reported smoke gate (so a browser flake is
   distinguishable from a unit failure), but they need no special runner.
3. Upload Playwright traces (enable `context.tracing().start(...)` on failure) as an artifact
   `if: failure()`.

## Determinism & anti-flake rules

- **Never `sleep`.** Use Playwright's retrying `assertThat(locator)` for anything SSE-driven; the
  patch lands when it lands and the assertion waits. For `recordedCalls` (an `IO`, not a locator),
  use a bounded retry-until helper, never a fixed delay.
- **Drive time through `fake.emit`**, not real timers — the fake is the only source of state
  change, so every test is a closed system (the plan-1 "no third-party state" property, extended to
  the browser).
- **Fresh server per test** (`TestServer.served` per test) so recorded calls / seeded state don't
  bleed — replaces the old `/__test__/reset`.
- Single browser per suite, fresh `BrowserContext` per test (isolates cookies/`data-signals`
  storage — matters for the tabs cookie tier).

## What this is NOT

- Not a replacement for the Scala functional tests — those stay the fast inner loop; Playwright is
  the thin outer verification that the browser half is wired.
- Not visual-regression / screenshot diffing (could come later; out of scope for smoke).
- Not run against a live HA — the `FakeHomeAssistant` is the whole point.
- Not a node/TypeScript project — see "Why in-JVM" above.

## Open decisions (resolve at implementation)

- Datastar asset: local-vendored vs `AssetCache` vs allow-CDN (prefer local-vendored for
  hermeticity).
- The retry-until helper for `recordedCalls`: a small `IO` combinator vs asserting only after a
  DOM-observable settle point (prefer gating on a DOM change the click also causes, so there's no
  timing guess at all).
- Whether the slider set is reliable headless via `fill`/keyboard; fall back only if needed.
- Driver/browser compatibility on the sandbox — resolve with the launch spike before writing suites.

## Work plan (execution checklist)

1. [ ] Spike: `com.microsoft.playwright:playwright` dep in `fh-datastar-view` test scope;
   `chromium.launch()` opens `about:blank` against the preinstalled `/opt/pw-browsers` Chromium.
   Resolve `channel`/`executablePath` if the bundled driver mismatches.
2. [ ] `TestServer.served`: ember-bind `server.routes` on port 0, yield `(TestServer, Uri)`.
3. [ ] `Browser` suite fixture + `SmokeSuite` base (per-test bound server + `Page`, console-error
   collector, cats-effect `Resource` bridge).
4. [ ] `RenderSmokeSuite` + `LiveUpdateSmokeSuite` (prove server-up + SSE-to-DOM morph).
5. [ ] `ControlSmokeSuite` (click → `recordedCalls` → round-trip morph).
6. [ ] Tier-A fixture Pkl dashboard with tabs/popup/slider; `UiSmokeSuite`.
7. [ ] Local Datastar asset serving for hermeticity.
8. [ ] CI: cached `chromium` install step + trace artifact on failure; optional `testOnly *Smoke*`
   gate after `testFull`.
