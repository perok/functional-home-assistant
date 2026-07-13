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
    SmokeSuite.scala             # base: Playwright+Chromium suite lifecycle (no separate
                                  #   Browser.scala in the end — one class was simpler than two)
                                  #   + a bound TestServer/Page per test, per-test isolation
    RenderSmokeSuite.scala       # page loads, seeded entities present, no page errors
    LiveUpdateSmokeSuite.scala   # fake.emit -> DOM morphs (retrying assertion)
    ControlSmokeSuite.scala      # click a control -> recordedCalls -> round-trip morph
    UiSmokeSuite.scala           # tabs / popup / slider signals (no server round-trip)
    ComponentVisualSuite.scala   # (added) component-level screenshot snapshots
  src/test/scala/fh/view/testkit/
    SmokeDashboard.scala   # the Tier-A Pkl fixture (real theme-beer.pkl + tabs/popup/slider)
    VisualSnapshot.scala   # the ComponentVisualSuite PNG-snapshot gate
  src/test/resources/
    visual-snapshots/       # checked-in PNG baselines
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

- **Local / CI**: install Chromium at the pinned Playwright version so it lands at the driver's own
  revision and Playwright's default Linux path (`~/.cache/ms-playwright`) — e.g.
  `npx playwright@<version> install chromium`, or the driver's own `install` command. The Java
  driver then resolves the executable itself (no `executablePath`, no `PLAYWRIGHT_BROWSERS_PATH`
  override, no `Test / fork`). Version-lock matters: the Node and Java Playwright releases pin the
  same browser revision, so installing at a *different* Node CLI version drops a mismatched build
  (e.g. `chromium-1228` with the newer `chrome-linux64` layout) that the pinned driver may not
  expect.
- **CI (GitHub Actions)**: the Java driver fetches its own browser on first run unless cached; add a
  cached `chromium` install step at the pinned version (see `.github/workflows/cicd.yml`). Same cost
  as the node CLI — not a node-vs-JVM difference.
- **Historical note (spike sandbox)**: the original spike ran in a network-blocked sandbox with
  Chromium preinstalled at a non-default `/opt/pw-browsers` at a revision that mismatched the
  driver's pin, so `PLAYWRIGHT_BROWSERS_PATH` + a dynamic `setExecutablePath` glob were needed and
  `playwright install` was impossible. None of that applies once the browser is installed at the
  default path at the pinned revision.

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
- Not run against a live HA — the `FakeHomeAssistant` is the whole point.
- Not a node/TypeScript project — see "Why in-JVM" above.
- Visual regression WAS added, narrower than a general-purpose tool: `ComponentVisualSuite`
  byte-identity-checks component-level PNG screenshots (not a perceptual/fuzzy diff) against
  checked-in baselines under `src/test/resources/visual-snapshots/`, using the same
  `FH_UPDATE_SNAPSHOTS` regenerate gate as `PklBuildSuite`'s wire-format snapshots. It works only
  because the suite controls every source of rendering noise: a fixed viewport, `SmokeSuite.settle`
  (kills CSS transitions/animations, waits on `document.fonts.ready`), and a fully offline,
  pinned-version asset set (no live CDN to drift under it).

## Open decisions (resolved during implementation)

- Theme assets (Datastar / BeerCSS / the icon font): `TestServer.served` builds the REAL
  `AssetCache` exactly as `ServerApp` does — a JDK http client fetching the theme's pinned CDN URLs
  into a temp dir — so the smoke/visual suites exercise the production fetch + CSS-sub-resource-
  rewrite path unchanged. (An earlier iteration vendored those bytes under `src/test/resources/vendor/`
  and served them through a fake `Client[IO]`; that existed ONLY because a sandboxed session's egress
  policy blocked `cdn.jsdelivr.net`, and was removed once tests run in environments with normal
  egress — dev machines and CI both reach the CDN, and production already depends on the same
  reachability at startup. Versions stay pinned in the URLs, so the fetched bytes are still
  deterministic.)
- The retry-until helper for `recordedCalls`: went with the small `IO` combinator
  (`SmokeSuite.eventually`, an `fs2.Stream.repeatEval` poll with a timeout) rather than gating on a
  DOM-observable settle point — a control click in these suites doesn't always have one available
  before the round-trip, and the combinator is a few lines, reusable, and never flaky in practice.
- The slider: keyboard (`Home`/`End` on the focused `<input type=range>`, which natively jump to
  min/max) proved reliable headless — never needed the `fill` fallback.
- Driver/browser compatibility: the browser is installed at the pinned Playwright version's own
  revision (`playwright install` / the GHA step) at Playwright's default Linux path
  (`~/.cache/ms-playwright`), so the driver resolves its own executable — no `executablePath` needed,
  and no custom `chromium-*/chrome-*` path globbing to drift against Chromium's folder-layout
  renames (`chrome-linux` → `chrome-linux64`). Because the browser is at the default path, no
  `PLAYWRIGHT_BROWSERS_PATH` override — and hence no `Test / fork` / `Test / envVars` — is needed;
  tests run unforked in the sbt JVM. We only pass `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` into the
  driver's own env via `Playwright.CreateOptions` (the ambient process env doesn't carry it — sbt
  2.0's persistent server keeps its start-time env) so it trusts the preinstalled browser rather
  than re-fetching. The earlier sandbox-era approach (`setExecutablePath` globbing
  `chromium-*/chrome-linux/chrome`) was a workaround for a network-blocked sandbox whose
  preinstalled Chromium sat at a non-default path AND mismatched the driver's pin; it is no longer
  used. See `SmokeSuite.beforeAll`.

## Work plan (execution checklist)

1. [x] Spike: `com.microsoft.playwright:playwright` dep in `fh-datastar-view` test scope;
   `chromium.launch()` opens `about:blank` against the preinstalled `/opt/pw-browsers` Chromium.
   Resolve `channel`/`executablePath` if the bundled driver mismatches.
2. [x] `TestServer.served`: ember-bind `server.routes` on port 0, yield `(TestServer, Uri)`.
3. [x] `Browser` suite fixture + `SmokeSuite` base (per-test bound server + `Page`, console-error
   collector, cats-effect `Resource` bridge). *(Landed as `page.onPageError`, not a console-`error`
   collector — see "What this is NOT"-adjacent note in `SmokeSuite`'s doc comment: console `error`
   also covers benign failed-resource-load logs for decorative sub-resources the CDN 404s, which
   would make the gate noise, not signal.)*
4. [x] `RenderSmokeSuite` + `LiveUpdateSmokeSuite` (prove server-up + SSE-to-DOM morph).
5. [x] `ControlSmokeSuite` (click → `recordedCalls` → round-trip morph).
6. [x] Tier-A fixture Pkl dashboard with tabs/popup/slider; `UiSmokeSuite`. *(`SmokeDashboard`,
   built through the real Pkl pipeline like `PklDashboardBehaviourSuite`'s fixture, but with the
   REAL `theme-beer.pkl` — not a dummy theme — since these suites exist specifically to exercise
   real CSS/JS in a real browser.)*
7. [x] Theme asset serving via the real `AssetCache.build` pipeline (JDK client → pinned CDN URLs),
   wired exactly as `ServerApp` — including its CSS sub-resource rewriting. *(Briefly vendored for a
   network-blocked sandbox; reverted to the live-CDN fetch once tests run with normal egress.)*
7a. [x] (Added, beyond the original plan) `ComponentVisualSuite`: component-level screenshot
   snapshots — see "What this is NOT" above.
8. [ ] CI: cached `chromium` install step + trace artifact on failure; optional `testOnly *Smoke*`
   gate after `testFull`. Not yet done — `PLAYWRIGHT_BROWSERS_PATH` + the preinstalled Chromium are
   sandbox-specific; a GitHub Actions runner needs its own cached-install step wired into
   `.github/workflows/cicd.yml`.
