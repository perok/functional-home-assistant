# agentbox: browser tests via a Playwright sidecar sharing the box's netns

> Work in flight. On completion its decisions move into `docs/adr/` and this file is deleted.
>
> Supersedes an earlier unimplemented design (in-image Chromium: nixpkgs runtime libraries +
> fontconfig + a `devbox browser-install` subcommand). That approach is dropped — see
> "Why not in-image".

## Goal

`sbt fh-datastar-view/testFull` runs the browser smoke suites **inside the agentbox**, so
behavioural regressions are catchable and debuggable from the agent's own environment rather than
only in CI. Today the only way to test in the box is `--exclude-tags=Slow`, which skips exactly
those suites.

Priority: **the behavioural tests working matters; pixel parity with CI does not.**
`ComponentVisualSuite` stays CI-authoritative either way, consistent with the standing "never
rebaseline locally" rule.

## Approach

Run the official `mcr.microsoft.com/playwright:v1.62.0` image as a **sidecar**, and have the JVM
`connect()` to its Playwright server instead of launching a local browser. The two containers
**share one network namespace**, so the sidecar's browser reaches `TestServer`'s ephemeral
loopback port with no tunnel and no published port.

The sidecar runs `chromium.launchServer({ args })` from a small Node script rather than
`npx playwright run-server`, because only the former can apply the tests' curated launch flags —
see step 1.

## Why not in-image

The superseded design added ~20 nixpkgs library attrs, four font packages, a `makeFontsConf`, a
launcher shim to keep `LD_LIBRARY_PATH` off the global env, and a `devbox browser-install`
subcommand — and still left Chromium's sandbox as an open question, plus permanent version drift
(nixpkgs ships `chromium-1228`; Playwright 1.62.0 wants `chromium-1234`).

The sidecar removes all of it. `mcr.microsoft.com/playwright:v1.62.0` exists on the registry and
matches `build.sbt`'s Java client pin exactly, so the browser version is pinned by the same line
CI reads.

## Why not a browser on the host

Considered and rejected. A Playwright server has no ACL or restricted mode, so a host-side one
lets the box drive a browser running as your user — `file:///home/<you>/.ssh/...` included,
i.e. everything the box deliberately has no mount for. The box's own LAN egress is already open
(the wrapper passes no `--network`), so LAN reach was NOT the differentiator; **host filesystem
access** is, and that is precisely what the box exists to prevent. In a container the browser is
confined by the same tool that confines the box.

Also rejected: `connectOverCDP`. Playwright's docs call it "significantly lower fidelity",
Chromium-only, and warn that functionality breaks when the browser was not launched with
Playwright's curated arguments. `exposeNetwork` is a `connect()` option and is not available on
it. `connect()` is the supported path.

## Verified facts

- `mcr.microsoft.com/playwright` publishes `v1.62.0` (and `v1.62.1`); `build.sbt` pins the Java
  client at `1.62.0`. Major/minor must match for `connect()`.
- `TestServer.scala` binds `host"127.0.0.1"` / `port"0"` — loopback, OS-assigned. This is why a
  browser outside the netns cannot reach the page under test, and why shared netns removes the
  problem instead of tunnelling around it.
- **`-p` is rejected under `--network=container:`** — verified locally:
  `docker: Error response from daemon: conflicting options: port publishing and the container
  type network mode`. Ports belong to the namespace OWNER. This dictates the container ordering
  below.
- `SmokeSuite.beforeAll` builds the driver env as `sys.env ++ Map(...)`, so an env var set by the
  wrapper reaches the test code unchanged.
- The wrapper currently ends in `exec docker run --rm --init` with **no `--name`** and no
  `--network`.
- `flake.nix`'s README records a deliberate "no devShell" decision. Unchanged by this plan: the
  sidecar is started by the existing wrapper, not by a shell the user enters first.

### From the spike (2026-08-25)

Run against `mcr.microsoft.com/playwright:v1.62.0` with `chromium.launchServer`, from a JVM inside
the agentbox image joined with `--network=container:`:

- **It works.** `connect()` from inside the box reaches the sidecar over the shared namespace, and
  pages render. Browser is `151.0.7922.34` from `chromium-1234` — the revision the Java client
  wants, with no version negotiation needed.
- **`launchServer` accepts the curated args**, so the determinism flags survive.
- **A shared browser is safe for parallel suites.** Two independent `Playwright` clients connected
  at once; closing one left the other working (`isConnected() == true`), and a third client
  connected afterwards. `close()` disconnects, it does not kill the server. This was the open
  question and it is now settled.
- **The box needs `PLAYWRIGHT_NODEJS_PATH`.** This is the one real image change. The Java driver
  ships its own `node`, which cannot execute under nix; without the override
  `Playwright.create()` dies with *"Failed to read message from driver, pipe closed"* — the exact
  symptom `CLAUDE.md` currently attributes to "an environment with no browser driver". The image
  already carries `nodejs_24`, and pointing the driver at it fixes it outright.
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` alone does NOT fix it.

## Plan

### 1. The test-side branch — one env var carrying the endpoint

In `SmokeSuite.beforeAll`, branch on `sys.env.get("FH_PLAYWRIGHT_WS")`:

- set   -> `playwright.chromium().connect(endpoint)`
- unset -> today's `.launch(...)`, unchanged

Carry the **endpoint**, not a boolean "in agentbox" flag: presence is the branch and the value is
the thing needed, so there is no second variable to keep consistent with the first. Named `FH_`
rather than `AGENTBOX_` because it is a contract into this repo's test code, not a knob on the
box.

Accepted, with eyes open: **the box exercises a path CI never runs.** That is the real cost of
this approach. Mitigation is that the branch is one `if` in `beforeAll` and CI keeps the `launch`
side, so a regression in shared code still fails CI; only the connect path itself is box-only.

**The curated launch args do not survive `connect()`.** Settled by the API surface, not a guess:
`ConnectOptions` carries only `headers`, `slowMo`, `timeout` and `exposeNetwork` — there is no
args list, because the server owns browser startup. So `npx playwright run-server` would silently
drop `--disable-lcd-text`, `--force-color-profile=srgb` and the rest.

The sidecar therefore starts the browser with **`chromium.launchServer({ args: [...] })`** from a
small Node script rather than `run-server`, so the same arg list the tests use is applied
server-side. Keeping that list in two places is a drift risk and is the main thing to watch in
review.

`launchServer` yields ONE browser shared by every connection, where `run-server` launches per
connection. Since suites run in parallel and `SmokeSuite.afterAll` calls `browser.close()`, that
had to be checked rather than assumed — the spike confirms `close()` disconnects the client
without killing the server, so parallel suites are safe.

### 2. Wrapper: `AGENTBOX_BROWSER=1`, opt-in

Same `case` shape and error voice as the existing `AGENTBOX_SSH` / `AGENTBOX_GPG` arms. Opt-in
because it pulls a ~2GB image and most sessions do not need it.

### 3. Container ordering — the sidecar owns the namespace

Forced by the `-p` conflict above. The box cannot own the netns, because the sidecar would have
to be started after it, and the wrapper `exec`s.

1. `docker run -d --name agentbox-browser --rm` the Playwright image, carrying `PORT_ARGS`
   (`AGENTBOX_PORT` publishing **moves here** when browser mode is on — the dashboard port is a
   property of the shared namespace now).
2. Wait for the server to accept on `:3000` before starting the box; a fixed sleep will flake.
3. Run the box with `--network=container:agentbox-browser` and
   `-e FH_PLAYWRIGHT_WS=ws://127.0.0.1:3000/`.
4. **Drop `exec`** so a `trap` can `docker rm -f agentbox-browser` on exit. Losing `exec` costs
   direct signal delivery — verify Ctrl-C still reaches the box and does not orphan the sidecar.
5. Reject or reap a stale sidecar from a crashed run (name collision) with a clear message.

Sidecar flags: `--cap-drop=ALL` and `--security-opt no-new-privileges` to match the box if
Chromium tolerates it; Playwright's docs recommend `--ipc=host` for Chromium memory stability —
evaluate whether it is needed, and if it is, say so in the accepted-risks section rather than
adding it quietly.

### 4. Image

One `Env` entry: `PLAYWRIGHT_NODEJS_PATH` pointing at `${pkgs.nodejs_24}/bin/node`, so the Java
driver uses the image's node instead of the one it ships, which cannot execute under nix. Use the
store path rather than `/bin/node` — `contents` already provides `nodejs_24`, and the store path
is the honest dependency.

Nothing else. No new `contents`, no fonts, no `LD_LIBRARY_PATH`, no `PLAYWRIGHT_BROWSERS_PATH` —
the browser is not in this image.

### 5. `flake.nix` README

- `## Use`: `AGENTBOX_BROWSER=1 nix run .#`, then `sbt fh-datastar-view/testFull`; note that
  without it the browser suites still need `--exclude-tags=Slow`.
- `## Shared with the host`: add `AGENTBOX_BROWSER` to the opt-in table; note that under it,
  `AGENTBOX_PORT` publishes from the sidecar.
- `## What the box can reach, and what we accept`: the sidecar is third-party
  (`mcr.microsoft.com`) and shares the box's network namespace, so it reaches whatever the box
  reaches. It has no host mounts. Record the `--ipc=host` decision here if it is needed.

## Files to modify

- `flake.nix` — wrapper only (`AGENTBOX_BROWSER` arm, sidecar lifecycle, ordering, README text).
- `modules/fh-datastar-view/src/test/scala/fh/view/smoke/SmokeSuite.scala` — the `beforeAll`
  branch.
- `build.sbt` — untouched. The version pin stays where CI reads it.

## Implementation notes (landed)

Things the plan did not anticipate, found while building it:

- **The sidecar image has no `playwright` npm package** — only the browsers under
  `/ms-playwright`. The server script needs `playwright-core` installed at start (~14 MB),
  cached in `~/.agentbox/playwright` so only the first start pays for it.
- **`docker logs -f | grep -m1` is the wrong readiness check.** `grep` leaves at the first match,
  but `docker logs -f` only learns the pipe is closed when it next writes — and a server that has
  announced itself writes nothing more. The pipeline then blocks for the entire timeout *on
  success*, which reads as a slow start rather than a bug. Replaced with a bounded poll that also
  gives up early if the sidecar has died. Measured: 4s to a usable box, against ~5 minutes before.
- **The box and the host share `target/`** through the `/work` mount, so alternating `sbt` runs
  between them corrupts incremental state, surfacing as a compiler crash that names nothing
  relevant. `sbt <module>/clean` fixes it; documented in the README.
- **`DatastarMorphContractSuite` launches its own browser** and does not extend `SmokeSuite`, so
  the branch had to be a shared helper (`SmokeSuite.connectOrLaunch`) rather than an edit to one
  `beforeAll`.

## Results

- `RenderSmokeSuite`, `UiSmokeSuite`, `ControlSmokeSuite`, `DatastarMorphContractSuite`: **28/28
  pass in the box**, three of them in parallel against the one shared browser.
- `ComponentVisualSuite`: **6/7**. `entity-card-off` differs by 0.308% of pixels against a 0.3%
  budget — font rasterization, exactly the anticipated case. The baseline is NOT regenerated and
  the budget is NOT widened: doing either would spend CI's sensitivity to buy a green box.
- The `launch` path still passes on the host, so CI is unaffected.

## Verification

Spike first; it answers the two unknowns (launch args, sandbox) before any wrapper work:

1. Start the sidecar by hand, run one `connect()` from a JVM in the box, load a page. If this
   fails, the plan is wrong and nothing else matters.
2. `nix build .#` — shellcheck / `bash -n` run on the wrapper at build time.
3. `AGENTBOX_BROWSER=1 nix run .#`, then
   `sbt 'fh-datastar-view/testOnly fh.view.smoke.RenderSmokeSuite'` — smallest browser suite,
   fastest true signal.
4. `UiSmokeSuite` + `ControlSmokeSuite`.
5. `ComponentVisualSuite` — expected to be the one that may differ. **Record the actual result;
   do not rebaseline locally.**
6. Without `AGENTBOX_BROWSER`: box starts as today, `FH_PLAYWRIGHT_WS` unset,
   `--exclude-tags=Slow` still passes, no sidecar running afterwards.
7. `AGENTBOX_PORT=8080 AGENTBOX_BROWSER=1` together — the dashboard is still reachable, proving
   the port moved to the sidecar correctly.
8. Ctrl-C and a crash mid-run both leave no orphaned `agentbox-browser`.
9. CI is unaffected — the `launch` path is untouched.

**One sbt command at a time** — overlapping runs produce fake 31s timeouts in this repo.
