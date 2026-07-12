# Plan: Playwright smoke tests for the Datastar dashboard

Status: **plan, not yet implemented**. Depends on the `FakeHomeAssistant` + `HouseFixture`
testkit from [`plan-functional-e2e-tests.md`](plan-functional-e2e-tests.md) — that plan lands
first; this one reuses its fake to boot a real server with deterministic, static state.

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

## Shape

```
modules/fh-datastar-view/
  smoke/                       # new, self-contained node project (sibling of editor-src)
    package.json               # @playwright/test only
    playwright.config.ts       # launches the smoke server, headless Chromium, traces on failure
    tests/
      render.spec.ts           # page loads, seeded entities present
      live-update.spec.ts      # emit a change -> DOM morphs
      control.spec.ts          # click a control -> service call recorded -> round-trip
      ui.spec.ts               # tabs / popup / slider signals (no server round-trip)
    fixtures/
      test-server.ts           # start/stop helper + control-plane client
  src/test/scala/fh/view/smoke/
    SmokeServerApp.scala        # boots the REAL Server against FakeHomeAssistant + a fixture dashboard
```

## The smoke server (`SmokeServerApp`)

The keystone. A `main` in **test** sources (so it can depend on the plan-1 testkit) that wires
the production `Server.resource` exactly as `ServerApp` does — but against `FakeHomeAssistant`
seeded from `HouseFixture` and a fixture dashboard — on a fixed port, plus a **control plane**
Playwright drives:

- `POST /__test__/emit` `{entityId, state, attributes}` → `fake.emit(...)`; the change flows
  through `StateStore` → SSE exactly as production. This is how a spec triggers a live update
  deterministically.
- `GET /__test__/calls` → the recorded `ServiceCall`s (JSON), so a spec can assert a click
  reached HA without needing HA.
- `POST /__test__/reset` → clear recorded calls / re-seed (per-test isolation).

The control routes are a separate `HttpRoutes` `<+>`-combined with `server.routes` in the smoke
main only — **they never touch production `Server`/`ServerApp`.** Launched by Playwright via:

```
sbt 'fh-datastar-view/Test/runMain fh.view.smoke.SmokeServerApp'
```

`Test/runMain` compiles + runs a test-scope main, so no prod-code changes and no separate
assembly. Port/host from env (`SMOKE_PORT`, default e.g. 8099).

### Dashboard under test

Reuse plan 1's Tier-A path: a small fixture `.pkl` built through `DashboardBuild` against a
`HouseFixture`-derived `lib/dump.pkl`, so the smoke run exercises real Pkl → model → renderer →
templates. It should include at least one of each interaction class so `ui.spec.ts` has
something to click: a **tabs** surface, a **popup** trigger, a **slider** (brightness), and a
plain **stateCard** (for live-update).

### Assets / offline

`Server` defaults to `AssetCache.empty` (pass-through → page references the Datastar CDN). For a
hermetic CI run the smoke server should serve Datastar locally instead of hitting the CDN — two
options, decide at implementation:

- Reuse `AssetCache.build` (the runtime's own local-cache path) seeded from the CDN once, or
- Vendor a pinned `datastar.js` into `smoke/` and have the smoke main serve it at `/assets/...`.

Watch-item, not a blocker: Playwright *can* be allowed to reach the CDN, but hermetic is better.

## The specs (smoke-level, one behaviour each)

1. **`render.spec.ts` — the page is alive.** Navigate to `/`. Assert the shell rendered, the
   kitchen light card shows "on", the outside-temp card shows `12.4 °C`. No interaction — just
   "server up, template rendered, Datastar loaded (no console errors)."
2. **`live-update.spec.ts` — SSE morphs the DOM.** Load `/`; `POST /__test__/emit` setting
   `sensor.outside_temp` to `13.1`; `await expect(tempCard).toHaveText(/13.1/)`. Playwright's
   retrying assertion waits for the SSE patch to land — no manual sleeps. This is the check that
   only a browser can make: the frame is not just sent, it is *applied*.
3. **`control.spec.ts` — click → HA → back.** Click the kitchen light toggle. Assert
   `GET /__test__/calls` contains `light.toggle` on `light.kitchen`. Then `emit` the resulting
   `off` state and assert the card flips to "off" — the full browser round-trip.
4. **`ui.spec.ts` — signal-driven UI, no round-trip.** Tabs: click tab B, assert panel B is
   shown and A hidden (the cookie/`data-signals` tier). Popup: click the trigger, assert the
   dialog opens; close it, assert gone. Slider: drag/set brightness, assert the value-carrying
   action POST fired with the right `service_data` (via `/__test__/calls`). These exercise the
   Datastar attribute wiring that has no server-side observable.

Keep each spec to its one assertion cluster; smoke suites earn their keep by being fast and
rarely flaky.

## CI integration

Extend `.github/workflows/cicd.yml`'s `ci` job (Node 22 + JDK 21 already present):

1. `npm ci` in `smoke/`, then `npx playwright install --with-deps chromium` (CI only — this
   environment already has Chromium at `/opt/pw-browsers`; locally set
   `PLAYWRIGHT_BROWSERS_PATH`/`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` and skip install).
2. `npx playwright test` — the config's `webServer` boots `SmokeServerApp` via
   `sbt 'fh-datastar-view/Test/runMain ...'`, waits for the port, runs the specs headless.
3. Upload the Playwright HTML report + traces as an artifact `if: failure()`.

Gate it as a distinct step after the existing `sbt testFull` so a smoke failure is legible and
doesn't mask a unit failure. First-class local run: a `sbt`-independent `npm test` in `smoke/`
that assumes the server is already up, plus the `webServer`-managed path for CI/one-shot.

## Determinism & anti-flake rules

- **Never `sleep`.** Use Playwright's retrying `expect(locator)` for anything SSE-driven; the
  patch lands when it lands and the assertion waits.
- **Drive time through the control plane**, not real timers — `POST /__test__/emit` is the only
  source of state change, so every test is a closed system (the plan-1 "no third-party state"
  property, extended to the browser).
- **Reset between tests** (`/__test__/reset`) so recorded calls / seeded state don't bleed.
- One worker or a fresh server per file if the shared control plane proves racy (start
  single-worker; parallelize only if clean).

## What this is NOT

- Not a replacement for the Scala functional tests — those stay the fast inner loop; Playwright
  is the thin outer verification that the browser half is wired.
- Not visual-regression / screenshot diffing (could come later; out of scope for smoke).
- Not run against a live HA — the `FakeHomeAssistant` control plane is the whole point.

## Open decisions (resolve at implementation)

- Datastar asset: local-vendored vs `AssetCache` vs allow-CDN (prefer local-vendored for
  hermeticity).
- Smoke server lifecycle: Playwright-managed `webServer` (simplest) vs a manually-started server
  for local dev (support both).
- Whether `ui.spec.ts`'s slider drag is reliable headless; fall back to a keyboard/`fill`-based
  value set if drag is flaky.

## Work plan (execution checklist)

1. [ ] `SmokeServerApp` (test sources): `Server.resource` against `FakeHomeAssistant` +
   fixture Pkl dashboard + `/__test__/{emit,calls,reset}` control plane; serve Datastar locally.
2. [ ] `smoke/` node project: `package.json`, `playwright.config.ts` (`webServer` →
   `Test/runMain`, headless Chromium, trace on retry), `fixtures/test-server.ts` control client.
3. [ ] `render.spec.ts` + `live-update.spec.ts` (the two that prove SSE-to-DOM works).
4. [ ] `control.spec.ts` (click → recorded call → round-trip).
5. [ ] `ui.spec.ts` (tabs / popup / slider signals).
6. [ ] CI: add the Playwright step to `cicd.yml` `ci`, artifact upload on failure.
7. [ ] Document the local run (`sbt ... Test/runMain` + `npm test`) in the module notes.
