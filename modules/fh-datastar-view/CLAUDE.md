# fh-datastar-view — the Datastar dashboard

A simpler HA web frontend (port of the TS prototype in `../ha-frontend`): a two-phase dashboard
where authors write Pkl and the server keeps the rendered HTML live with Datastar.

Its ADRs are in [`docs/adr/`](../../docs/adr/README.md) — they record the design decisions
(entity card + value transforms [JSONata → CEL, ADR 0027], surfaces/tabs, dynamic groups, the slot
model) with their
rationale. The repo-wide ADR routine is in the root `CLAUDE.md`.

[`docs/architecture-rendering-pipeline.md`](../../docs/architecture-rendering-pipeline.md) is the map of the RUNTIME half —
how a state change becomes bytes, what is shared per slug vs. per connection, and the three node
kinds (static, dynamic, flip). It is current-state and must move with the code, in the
same commit; ADRs that change the pipeline update it too.

#### Workflow for changes here

1. Read the relevant ADR(s) first; for Pkl work also read ADR 0006, whose gotchas list is the
   spike-verified record of what the language actually does, before writing any Pkl. For anything in
   `fh/view/runtime` read `docs/architecture-rendering-pipeline.md` — and update it alongside the change.
2. Verify with `sbt 'fh-datastar-view/testFull'` — the suites build **fake dumps** in temp
   dirs and run the real library modules through the full pipeline, so **no live HA is
   needed** for tests. (`sbt dashboardBuild` *does* need the live instance — it fetches the
   entity dump.)

   For changes to the Pkl authoring library itself there is a pure-Pkl suite (`facts` +
   `examples`, no dump), and **`sbt` runs it** — `PklLibraryTestSuite` calls
   `Evaluator.evaluateTest`, which IS the runner the CLI calls, through the pinned `pkl-core`.
   So it needs no `pkl` on PATH, and the "CLI must match the `pkl-core` pin or you are testing
   semantics you do not ship" hazard is gone by construction.

   ```bash
   sbt 'fh-datastar-view/testOnly *PklLibraryTestSuite'   # just the Pkl suite, ~2s
   ```

   The CLI is still the tool for **authoring `examples`**: the Scala runner never overwrites a
   baseline (a suite that rewrites what it checks against always passes), so accepting new
   example output stays a deliberate local act.

   ```bash
   pkl test --overwrite modules/fh-datastar-view/src/test/pkl/*.test.pkl
   ```

   **A `-D` or env var on the `sbt` command line does not reach the tests here** — measured,
   both silent no-ops. The long-lived sbt server captures its environment when it STARTS, which
   is the same fact the snapshot-flag warning below records from the other side: exported before
   the server starts it sticks forever, set on a client invocation afterwards it never arrives.
   Neither is a way to tell a test something. The established answer in this repo is an sbt
   `Command` that sets and clears `sys.props` in a `try/finally` — see `dashboardSnapshotsUpdate`
   in `build.sbt`.

   **`*.test.pkl`, not `*.pkl`** — the glob both runners use. The directory also holds fixture
   modules (`site-kitchen.pkl`, `site-attic.dashboard.pkl`) which are not test modules, so the
   wider glob reports two `–– Pkl Error ––` blocks and **exits 1 on a fully passing suite**
   ("100.0% tests pass" and a red exit, together). A signal that is already noisy when nothing
   is wrong cannot tell you when something is.

   **Every `.pkl` edit is covered whenever you run the module's tests**, comment-only edits
   included — which matters because `lib/` is packaged into the content-versioned
   `@fh-dashboard`, so any byte moves the package hash.

   The tests live outside `lib/` deliberately: `LibPackage` packages that directory into the
   content-versioned `@fh-dashboard` package, so a test module inside it would move the package
   hash and re-evaluate every dashboard. They import the library by relative path for the same
   reason. CI runs them in the `parallel:` block of `cicd.yml`.
3. For refactors that must not change behavior (authoring-API changes, ergonomics work): the
   evaluated `{cards, card}` JSON is the contract. The safety net is the **wire-format
   snapshots** in `PklBuildSuite` (`src/test/resources/snapshots/`): they byte-identity-check
   the evaluated demo entries, so `sbt 'fh-datastar-view/testFull'` catches any drift.
   Regenerate them deliberately when the wire format is *meant* to change, with
   **`sbt dashboardSnapshotsUpdate`** — then read the JSON diff before committing it.

   **Use the command; do not hand-roll the flag.** Two footguns it exists to close, both of
   which have fired: a shell `FH_UPDATE_SNAPSHOTS=1` export sticks to the long-lived sbt
   server forever, and a hand-written `; put ; testFull ; remove` chain skips its `remove`
   when the test task fails — sbt aborts the rest of the chain — leaving the server in
   regenerate mode where every later run reports green *while rewriting files*. The command
   is a `Command` with `try/finally` (`build.sbt`), so it clears either way. If you suspect
   a leak: `sbt 'eval sys.props.get("FH_UPDATE_SNAPSHOTS")'` should say `None`.

   The **visual PNG baselines have a separate gate** (`dashboardVisualSnapshotsUpdate` /
   `FH_UPDATE_VISUAL_SNAPSHOTS`) precisely so the routine wire regeneration cannot touch
   them — a local rebaseline records this machine's font rasterization, which CI does not
   share. Normally you do not run it at all: let CI fail and read its before/after artifact.
   The backend model (`Dashboard.scala`) should not need to change for
   authoring-layer work (the layout-cell fields — `Cell`, `CardDef.wrapAsCell`
   — were the sanctioned structural exception; see ADR 0008).
4. Visual changes cannot be verified from the terminal — ask the user to confirm in the
   browser (`sbt dashboardServe`), per ADR 0006.
5. Datastar questions (attribute syntax, SSE semantics): use the `datastar` skill — it points
   to context7 (`/websites/data-star_dev`) for general docs, plus pinned-bundle corrections and
   project conventions context7 won't have. Attributes use colon syntax (`data-on:click`, not
   `data-on-click`).
6. Browser-test questions (the `smoke` suites): read context7 (`/websites/playwright_dev`)
   before writing one — the Java binding is the minority dialect, most examples are the TS
   test-runner's, and the difference bites. Four things it will not tell you, each of which
   has already cost a debugging session here:

   - **A retrying assertion is not a synchronization point.** `assertThat(x).containsText(…)`
     passes the instant it holds, so a test that clicks and then asserts an UNCHANGED state
     asserts nothing — it is answered by a page that has not acted yet. Wait for the request
     the click makes (`waitForResponse`, or `waitForRequest` when the response is aborted
     away), then assert.
   - **Playwright's Java client dispatches event callbacks only while the calling thread is
     inside one of its calls.** A `page.onRequestFailed` collector polled from a plain `IO`
     never fills, and the test hangs to its timeout rather than failing.
   - **Datastar retries a failed action POST** (two attempts, measured). "The tap could not be
     sent" is not one event, so a test that races it is asserting against a page mid-retry.
   - **Service workers are blocked in `SmokeSuite`'s contexts** and should stay blocked:
     `fhRegisterSw` runs on localhost, which is a secure context, so otherwise a worker claims
     every smoke page mid-test.
7. Formatting (Scala only; there is no formatter for the Pkl sources) is handled by the
   `PreToolUse` hook in `.claude/settings.json`, which runs the standalone `scalafmt` CLI
   before every `git add`. `project.git = true` in `.scalafmt.conf` covers every tracked
   source in one pass, so nothing reaches CI's `scalafmt --test` unformatted.

#### Key files

| File | Role |
|---|---|
| `fh/view/model/Dashboard.scala` | Wire model `{cards, card}`, `LayoutNode` (incl. `Dynamic`), `Predicate` AST, `validate` |
| `fh/view/build/SourceEval.scala` | The authoring-language seam: `.pkl` → `PklBuild` (Pkl is the only evaluated language) |
| `fh/view/build/PklBuild.scala` / `PklDump.scala` | Pkl evaluation (pkl-core 0.32.1) + typed dump generation — rendered to text and packaged, never written as a loose file. **Touching how the dump is TYPED** — capability classes, a modelled domain's value groups, the codegen `warnings` pass, adding a domain — **read ADR 0013 first**: it owns that design, the per-domain coverage table and the add-a-domain recipe |
| `fh/view/build/LibPackage.scala` / `AddonBootstrap.scala` | The server boot path: `@fh-dashboard` packaged into a persistent cache, then the workspace seeded — on EVERY start, add-on and local `sbt dashboardServe` alike (repo resources as the bundled lib, appdirs cache, workspace `dashboard-local-dev`). **Touching seeding, module resolution or the workspace scaffold — read ADR 0010 first**: it owns the write-once rule for user files, package-form as the ONE resolution mode, the `.fh/base.pkl` + `.fh/machine.json` split that keeps the scaffold byte-identical everywhere, and why a missing `moduleCacheDir` is a hard error rather than a fallback |
| `fh/view/build/DumpPackage.scala` | The dump as a content-versioned package (`fh-home@1.0.0-g<hash>`; the lib is versioned the same way, base from `lib/PklProject`), the ONLY form it takes anywhere: `seedFromText` builds + seeds it into the cache and rewrites `.fh/pins.json` on every dump render (server startup + `DumpRefresh`). Consumed by the instance's own eval AND by laptops, via `/system/pkl/packages`. **ADR 0010** owns the packaging and pinning design |
| `scripts/fh` (repo root) | The laptop CLI: `init`, `pull`, `push` (`--slug`, `--write`, `--watch`), `init-lsp-fix`, `update`. Typelevel toolkit + decline + in-process pkl-core, installed by curl from GitHub raw (`update` sha256-compares against the repo copy); needs only scala-cli. Its own suite is `scripts/fh.test.scala` (weaver), run with `cd scripts && SCALA_TEST_MODE=true scala-cli test .` — the script gates its dispatcher behind that variable — and it is a CI step. **What each command does and why is ADR 0010** (`push --write` sending SOURCE, the `Analyzer.importGraph` watch set, the pkl-CLI bug behind `init-lsp-fix`); that pushing `site.pkl` installs every dashboard it names is ADR 0021 |
| `fh/view/build/RegistryDump.scala` | **The** dump — there is no second path. Built from the WS registries joined onto the `subscribe_entities` snapshot, which is the SPINE (a LEFT join from states; the registry also lists disabled entities no dashboard can render). Also owns the shared `transform`/`entityKey`/`slug` keying. `build` is pure, so `RegistryDumpSuite` tests it with no HA. Attributes are filtered to `CapabilityAttributes`, widenable via `FH_DUMP_ATTRIBUTES` (comma-separated, additive) — **before adding one, read ADR 0013**: the filter protects the dump's CONTENT HASH, not just freshness, so a volatile attribute re-evaluates every dashboard on every change |
| `fh/view/build/DumpRefresh.scala` | Runtime dump refresh, validate-then-swap: unchanged ⟺ same content-version; else re-evaluate every entry against the new dump in a temp copy of the workspace and swap the `.fh/pins.json` pin only if nothing that builds today breaks. Driven by HA registry events (`watch_registry` option) + `POST /system/dump/refresh` (the /edit button). **ADR 0010** owns the pin discipline and why the previous immutable cache version is the only trail kept |
| `fh/view/runtime/Renderer.scala` / `Server.scala` / `StateStore.scala` | Live re-render, SSE patch diffing, WS-fed state |
| `src/js/` + `package.json` + `vite.config.ts` | The frontend, bundled by **vite 8** into MANAGED resources (`project/NpmPlugin.scala`, `frontendInstall`/`frontendBundle` — a `resourceGenerators` entry, so a plain compile builds it and **node + npm are a build requirement**). ONE build, three entries: `shell.ts` (inlined into every page by `Server.pageInto`), `editor/app.js` (CodeMirror + lsp-client bundled IN — no vendor file, no CDN, no import map), `editor/overlay.js`. Outputs are **content-hashed under `web/` with a `build.manifest`**; nothing spells a filename out — `FrontendAssets` reads the manifest and everything asks by ENTRY NAME (`Server.UrlSyncScript`, the `editAssets` overlay tag, the `__APP_JS__` placeholder in the editor `index.html`), and `Server` serves `/web/:file` `immutable` guarded by that same manifest. Deliberately **not `build.lib`**: lib mode refuses multi-entry for `iife`/`umd`, and `isEsLibBuild` hard-forces `minifyWhitespace: false` (to keep pure annotations for a downstream bundler we do not have), which shipped `app.js` at 654 kB where `rollupOptions.input` emits 421 kB. `shell.js` and `overlay.js` are classic scripts and work as `es` output ONLY because they import nothing; rollup never duplicates code, so one shared module splits a chunk and gives both a real `import`, breaking every page silently. The `fh-assert-self-contained` vite plugin FAILS THE BUILD on that, off rollup's own `chunk.imports`/`exports`, and the document's last line calls `fhScroll` only `if(window.fhScroll)` so anything the build cannot see still names itself in the console. Nothing built is committed; new code is TypeScript (`tsc --noEmit` runs as part of the build), the ported editor sources stay JS |
| `resources/dashboards/lib/` (the `@fh-dashboard` package) | THREE tiers by audience: **`core/`** — `node`, `css`, `slot`, `text`, `icon`, `tap`, `surface`, `predicate` — is what a COMPONENT author imports; **`layout.pkl`** (Row/Column/Grid) and **`components.pkl` + `components/`** (`text`, `entity`, `control`, `slider`, `surface`, `light`, `moreinfo`) are what a DASHBOARD author imports, `components.pkl` being a FACADE that declares no cards; **`recipes.pkl`** is whole opinionated sections. `internal/dump-base.pkl` is generator-facing, and `entry.pkl` stays at the root because it is what every entry amends. **Adding a module, or re-exporting through the facade — read ADR 0015 first**: it owns the tiering and the two rules a re-export cannot break, both of which exist to keep editor completion working THROUGH the module |
| `resources/dashboards/lib/{hass.pkl,hass/light.pkl,tokens.pkl}` | Pkl domain schema (`hass.pkl` stays at the package ROOT — every generated dump emits `import "@fh-dashboard/hass.pkl"`, and that URI identity is load-bearing; the vendored per-domain constants live under `hass/`) + shared HA-named design tokens. `hass.pkl` gives every SCOPE the same five names — `lights`/`sensors`/`switches`/`generic`/`all` — on `Area` (generator-filled), `Floor` (derived from its areas) and `Device` (type tests over its entities), matching the dump's house-wide lists, so `q.from(...)` takes any scope |
| `resources/dashboards/lib/internal/dump-base.pkl` | The house-wide lists as a CONTRACT (`open module`, `List()` defaults) that the generated `@fh-home/dump.pkl` **extends**. **ADR 0013** owns why the lists are declared here rather than emitted per home, and why `extends` and not `amends` |
| `resources/dashboards/lib/query.pkl` | The candidate-set query surface (`q.from(...).where(...).render(...)`), imported as `@fh-dashboard/query.pkl`: `where`/`orderBy`/`limit`/`caseOf`/`render`, the aggregates (`count`/`any`/`none`/`all` — these are also what an `If` condition is built from), nested sets, `q.entity(e)` for naming a DIFFERENT entity than the member, and `q.prop`/`q.attr`/`q.optional` for names. The wire classes live in `core/predicate.pkl` — query.pkl depends on the CORE, never on the shipped cards. **Plain Pkl stays the first answer**: a `for` over a typed dump list is still the right way to render a fixed set of lights, and this earns its place only when membership must react to live state. **ADR 0003** owns the build-time/live fold and how a property name resolves |
| `resources/dashboards/lib/hass/actions.pkl` | **What a TAP means, per domain**, vendored: a `Call` (a build-time literal like `light/toggle`), a `CallByState` for the four domains whose service the live state picks, or **absent**. Adding a domain is one row, and nothing in Scala knows an HA domain. **ADR 0016** owns why absence is the load-bearing case and which absences are deliberate |
| `resources/dashboards/lib/hass/light.pkl` | HA's `light` domain model VENDORED — the `ColorMode` union + `LightEntityFeature` bits. `HaLight.scala` is the generator's copy and `HaLightSuite` asserts the two agree. Imported by `hass.pkl` **with an `as` alias** — Pkl binds an import to its FILE name, so the alias keeps `light` from reading as a light ENTITY; a `///` doc comment on an import is also a parse error. **ADR 0013** owns why copying HA's `*EntityFeature` flags is safe, and the pattern other domains follow |
| `resources/dashboards/lib/core/css.pkl` | The base stylesheet EVERY dashboard gets whatever its theme (ADR 0020): the `fh-` layout contract, the `--fh-*` colour variables a card's CSS is written against, and the classes the runtime itself emits or binds (busy states, offline banners, toast). `entry.pkl` puts it on `Dashboard.css`, and the renderer emits it FIRST — so a theme can override it but never drop it |
| `resources/dashboards/lib/theme.pkl` | The theme CONTRACT (`open class Theme`, the `sliderHoldScript` gesture, and the `hidden classes` a theme uses to get its OWN class names spliced into card markup) and the theme-author guide; implementations are the `theme-*.pkl` siblings. A theme is now the PAINT layer only — the layout contract is `core/css.pkl`'s and each card's structure is its own `cardDef.css` |
| `resources/dashboards/lib/theme-beer.pkl` | BeerCSS MD3 theme, the DEFAULT (via entry.pkl) and only shipped implementation — read ADR 0026 + the `beercss` skill first; its module doc explains the body-specificity color bridge + the amendable `md3Light`/`md3Dark` palettes. Also loads **MDI** (`@mdi/font`, pinned) because HA's own entity `icon` attribute is an MDI name — off the critical path, via `Theme.deferredStylesheets` (preload + `onload` swap + `<noscript>`), since nothing about the LAYOUT depends on it; its doc carries the ~394 KB cost and the build-time SVG-inlining plan that should replace it outright |
| `resources/dashboards/lib/site.pkl` | The ENTRYPOINT base (ADR 0021) — the workspace's one `site.pkl` amends it and names every dashboard in `dashboards` (key = slug), plus site-wide settings (`default` today, auth next). Its mapping default is an `entry`, so a key amends into existence; a key may also be assigned an imported module (`import("x.pkl")` — the CALL form, the declaration form does not parse in a value position) |
| `resources/dashboards/lib/entry.pkl` | ONE dashboard — a `dashboards` value `amends` it, setting only `card` (+ optional `title`/`surfaces`/`theme`) |
| `resources/dashboards/lib/PklProject` | The `@fh-dashboard` package manifest — the shared lib, packaged into the cache by `LibPackage`. (The top-level consumer `PklProject` + `home/` are gone: workspaces are bootstrapped package-form; the repo `lib/` is bundled-lib SOURCE, not a path-form checkout.) |
| `resources/dashboards/site_default.pkl` | The seeded starter SITE — what a fresh workspace's `site.pkl` is ([[AddonBootstrap.starterSite]], read off the jar's own resources) |
| `resources/dashboards/pkl-demo.pkl`, `pkl-tabs.pkl` | Demo dashboard modules — a `dashboards` key has to point at one for it to be served |
| `resources/dashboards/*.jsonnet`, `components.libsonnet` | **Inert porting references only** — no longer evaluated; do not extend (see below) |
| `src/test/.../PklBuildSuite.scala` | The Pkl track's main safety net (fake dumps, full pipeline) |
| `src/test/.../WireShapeSuite.scala` | The wire shape is declared TWICE — a Pkl class in the library's modules, a Scala case class in `Dashboard.scala` — and nothing made them agree; the snapshots only noticed when a fixture happened to exercise the drifted field. This reflects over both (`pkl:reflect` vs `productElementNames`) and names the mismatch. Compares NAMES not types on purpose; a documented asymmetry is excluded with its reason (`SlotSource.literal` has no Pkl field — a constant slot is a bare string) |

A two-phase dashboard frontend. Authors write a dashboard as **Pkl** (ADR 0006); the server
renders HTML and keeps it live with [Datastar](https://data-star.dev) (SSE HTML-fragment patches
+ action POSTs).

- **Evaluation** (`DashboardBuild`): fetches the live entity dump (`RegistryDump`),
  seeds the typed dump as the `@fh-home` content-versioned cache package
  (`DumpPackage.seedFromText` — no loose file), then evaluates
  the entry `.pkl` **in-process via pkl-core** (`PklBuild`, through the `SourceEval` seam) into the
  `{ cards, card }` model — a shared library of named cards (Mustache templates) plus a
  **recursive layout tree** (`card` = its root) of component nodes that reference cards by name.
  Pkl does **composition only** and emits Mustache template strings + static node params; it
  never injects live values, and authors never write node ids (the backend derives stable,
  location-based ids while recursing — `LayoutNode.pathId`).
- **Build phase** (`fh.view.build`, `BuildApp` / `sbt dashboardBuild`): evaluates + persists the
  `dashboard.json` artifact for inspection/CI. The runtime does not need it. **No argument** —
  there is one entrypoint (ADR 0021) and the artifact is the whole site; a dashboard that fails to
  build fails the build.
- **Runtime phase** (`fh.view.runtime`, `ServerApp` / `sbt dashboardServe`): evaluates the **same
  entrypoint in memory on startup** (so pkl-core *is* on the startup path — but never on the live
  hot path), pre-compiles the Mustache templates (mustache.java — `com.github.spullara.mustache.java`,
  not jmustache; its `execute(Writer, ctx)` is what lets `Renderer` push into a caller's buffer —
  `Templates`), seeds all entity state from
  `/api/states` and keeps it live from the `state_changed` WS stream (`StateStore`, a `Ref` +
  fs2 `Topic`, full attributes; publishes only on real change). On each change it re-renders the
  affected components (`Renderer`, reverse index `entityId -> generated id`) plus the
  query-affected dynamic groups — **per-entity**: an in-place member tick morphs one
  `{gid}_{entity}` child, small membership deltas patch `remove`/`before`/`append`, and only
  a host whose whole content arrived or left at once, or a post-reload group,
  repaints wholesale — and pushes only the fragments whose HTML actually changed (`Server`
  keeps a per-node last-rendered cache; http4s ember).
- **Phase discipline**: leaf templates escape `{{slot}}` values; container templates splice their
  children unescaped via `{{#children}}{{{html}}}{{/children}}`; other raw author values (action
  URLs, ids) use `{{{...}}}`. Pkl sources live in `src/main/resources/dashboards/` (the seeded
  starter + demo modules, plus `lib/*.pkl`); the dump is a cache package (never on disk in the repo) and
  `dashboard.json` is generated + gitignored.
  The old `*.jsonnet`/`*.libsonnet` files also still sit here as **inert porting references**
  (the five real dashboards are being hand-ported to Pkl) — the backend never evaluates them and
  they must not be extended.
- Interactivity uses the WS `call_service` command (added to `ha-api`'s `CommandPhase` +
  `HomeAssistantApi.callService`). `POST /sse/action/:slug/:domain/:service/:entityId` triggers a no-data
  service; the value-carrying variant `.../:entityId/:key/:value` builds `service_data` (the value
  rides in the URL path, since Datastar template-literal URL interpolation isn't confirmed in v1 —
  use `'.../key/' + $signal` concatenation client-side). The resulting state change flows back over
  the persistent SSE stream.
  The `:slug` is what BOUNDS the call (ADR 0023): the action is refused unless that dashboard
  NAMES the entity, so admission to one dashboard is not admission to the whole house. A module
  does not know its own slug, so the renderer supplies it: `dashboard_slug` (a CEL binding) in a
  tap's transform, `{{dashboardSlug}}` (a Mustache var) in a card's own template — two
  spellings because there are genuinely two phases, each named after the one that fills it. The
  slug is applied in `DashboardBuild.decode` BEFORE validation, so a `Validated` is final and a
  `fh push --slug` rename cannot leave a compiled tap URL naming the old dashboard.
  The surface taps carry it too — `POST /sse/surface/:slug/open/:id`, `POST /sse/popup/:slug/close`
  — for a second reason (ADR 0024): a `conn` this process has forgotten (an idle page outliving
  its session's linger) can then be RE-MINTED rather than dropped, the same thing the stream route
  already does. Before that, such a tap answered 204 and did nothing at all, while the client's own
  signal assignment still moved the URL. Standing principle from the same ADR, aspirational and
  not enforced: **the DOM we send should be as usable as possible without JS** — signals for
  liveness and effects, not for the core meaning of a tap.
  A surface tap also does NOT set `ui_<group>` itself any more (ADR 0025): it writes a pending
  signal `_<group>__pending` — what it ASKED for — and `swapHost` commits `ui_<group>` for what it
  actually did, which is what the URL mirror follows. A selection display reads
  `$_<group>__pending || $ui_<group>`, so the press is still instant while the committed value can
  never claim a panel this DOM does not have. Pending clears by the commit catching up, by the
  server clearing it in a refusal, or when the stream that would have carried the commit is down.
  This does NOT replace ADR 0019's `busy` for service taps — a
  service call has no committed selection to catch up to; the two mechanisms coexist deliberately.
  **A refused action answers 200 carrying `datastar-patch-signals`, never 4xx** (ADR 0024): the
  request was served and the OPERATION failed, which is page state. One helper (`actionRefused`)
  answers every refusal — HA rejecting a call, an entity this dashboard does not name, an unknown
  surface, a `conn` on another slug — patching `_<node>__error` on the control that was pressed,
  clearing `_<group>__pending`, and setting `_toast` to HA's own message. Both ids ride in the
  action's query string (`?node=&group=`), read off `data-fh-node` at click time. The bundle parses
  a response body only on exactly 200, so a 4xx could never carry any of it; the client-side
  `failedOn`/`pendingFail` handlers remain for the genuine non-200 remainder.
- Cards (`lib/components/`, re-exported by `lib/components.pkl` — ADR 0015): `fhgrid`/`fhrow`/`fhcol` containers, `sectionTitle`, `entityCard`,
  `button`, `pill`, `slider` — each is a typed card class carrying its own `cardDef` (Mustache template +
  declared slots), and the emitted `cards` registry is derived by `pkl:reflect`; slots are checked
  by `Dashboard.validate`. Call-style factories / classes return layout nodes referencing a card
  by name; a new container/leaf kind is one class, no Scala change. Datastar attributes use
  **colon** syntax (`data-on:click`,
  `data-bind`, `data-signals`). `SlotSource.default` fills absent/null attributes (e.g. brightness
  when a light is off).
- **Signal slots (ADR 0017)**: a slot with `signal = true` carries its value as a Datastar signal
  instead of as bytes in the element, so a change to it costs one
  `datastar-patch-signals` frame for the batch rather than a re-rendered card each. The card places
  the renderer-supplied binding — `\(slot.signalBind("value"))` in Pkl, `{{{value__bind}}}` in the
  emitted template — beside the ordinary `{{value}}` hole. A display signal is named by WHAT IT
  READS — `_e.<domain>.<object_id>.<transform>` — so one entity on three cards is one signal and one
  frame entry (#134); dots are PATH separators, so a frame carries nested JSON and never a flat
  dotted key. A two-way `SignalBind.Bind` is the exception and keeps the node-scoped
  `_<nodeId>__<slotName>`: an input writes it back, so sharing it would let one card's drag drive
  another's readout (ADR 0025). TWO render forms: the DOCUMENT form
  (value inline + a node-level `data-signals` seed on the `.fh-cell` wrapper; what a page load,
  repaint or fill sends, and all a JS-less browser ever gets) and the PATCH form (neither; what
  `renderNodeById` sends, which is why the digest stands still and the morph is suppressed).
  The slot's value names the BINDING KIND (`SignalBind`, one string on the wire): `text`
  (`data-text`), `style:<prop>` (`data-style:<prop>`, custom properties included), `attr:<name>`, or
  `bind` (`data-bind`, two-way on a form control). Every kind reads the signal bare — the VALUE
  carries its own unit (`39.37%`, `#ffb46b`), so the transform decides its shape in one place.
  Customers: `entityCard`'s `value` (text), and all four of the slider's moving slots — `state`
  (text), `value` (`attr:value`), `fill` (`style:--_end`) and
  `fillColor` (`style:background`). The slider's `value` is SERVER-ONLY (ADR 0025): the input is
  `data-bind`-ed to a separate client-owned `_<id>__slide`, because `data-on-signal-patch` fires on
  local writes too, so one signal cannot tell the server's write from the drag's — and while the
  drag wrote the server's slot, a FAILED commit left the thumb where the finger was, with `holds`
  stale so no correcting frame ever came. Two handlers reconcile: adopt on a server write, and the
  same restore on a refusal or a dead stream. A DRAG paints the fill (and a percent readout) itself, from
  `data-on:input` — the style plugin re-applies its property whenever anything else writes the
  `style` attribute, which `beer.min.js` does on every move, so without that the whole gesture
  showed nothing until release (ADR 0017). The contract is a NEGATIVE one — a broken
  implementation still updates the card, because the morph the frame was meant to replace is still
  being sent — so `SignalSlotSuite` asserts what is *not* on the wire.
- **Every node is a cell (ADR 0008)**: the renderer wraps every component in an id'd `.fh-cell`
  (the real flex/grid item and Datastar morph target; `CardDef.wrapAsCell = false` is the rare
  opt-out — the tab anchors). `Grid` (`.fh-grid`, 12 columns, cells default to half — HA
  `grid_options` semantics) is the default container; per-node sizing rides in the wire-level
  `cell.classes` via the HA-flavored builders `columns(n)`/`fullWidth()`/`hug()`/`centered()`/`cellClass`
  on the Pkl `LayoutNode` base (chain them AFTER card-specific builders). Candidate sets flow
  their members the same way (`.fh-group`).
- Candidate sets: a `LayoutNode.SetNode` carries a STATIC candidate list (decided at build time
  from the dump) plus per-candidate guarded renderings; the runtime decides only PRESENCE (the
  first clause whose `when` holds; none = not rendered) and ORDER. Authored through
  `@fh-dashboard/query.pkl`. The query-driven `LayoutNode.Dynamic` it replaced is deleted — see
  ADR 0003 for why, and `docs/adr/0003-candidate-sets.md` for the derivation.
- **Pkl authoring (ADR 0006)** — the authoring language: dashboards are [Pkl](https://pkl-lang.org),
  typed cards + editor completion. `fh.view.build.SourceEval` is the (Pkl-only) seam;
  everything downstream is source-agnostic. Pkl library modules live in `dashboards/lib/`
  (`hass.pkl` hand-written domain schema, the `core/` kit + `components/` families behind the
  `components.pkl` facade — ADR 0015, the `theme.pkl` contract +
  the `theme-beer.pkl` implementation, `tokens.pkl`, the `site.pkl` entrypoint base + the
  `entry.pkl` dashboard scaffold). ONE top-level entrypoint, `site.pkl`, `amends
  "@fh-dashboard/site.pkl"` and names every dashboard in its `dashboards` mapping — the KEY is the
  slug (ADR 0021), and each value is an `entry` (amended inline, or `import("x.pkl")`ed from a
  module that `amends "@fh-dashboard/entry.pkl"` and sets only `card` + optional
  `title`/`surfaces`/`theme`). Any other top-level `*.pkl` is an ordinary module. Membership is
  live: adding or removing a key hot-reloads like any other edit. The `@fh-home` dump is a TYPED dump generated by `PklDump` from the live
  fetch and seeded as a cache package (no file on disk). Feature surface: containers (grid/row/column) + the layout-cell builders
  (`columns`/`fullWidth`/`hug`/`centered`/`cellClass`, ADR 0008), sectionTitle/entityCard/button/pill/slider,
  a slider with `members` — the SAME card, holding member rows that are ORDINARY nodes: the head and
  the members are two REGIONS of it, so a master state change never repaints a row. The head is
  itself structure (#151): its label and second line are a `sliderText` leaf (the only bytes on the
  row that move — everything else is a signal or a literal), and its buttons are a `sliderAction`
  each, in an `actions` region, so two of them can be in flight without disabling one another.
  `.actions(…)` is the list; `.tapAction(…)` is the one-button shorthand and stays first. Every slider
  carries its entity's own `iconFor` badge unless told otherwise (`icon = null` for none), and
  `icon`/`secondary`/`tapAction` are optional pieces a plain row simply doesn't carry
  (`c.slider(master).withSubSliders(rows)` is the chain form; `.readout(…)` picks what a line reads out —
  `"percent"`/`"state"`/`"none"`, or any `expr`/`exprOf`, the names being shorthands for the two
  readings that need the card's resolved axis config, which `percentExpr`/`valueExpr`/`minExpr`/
  `maxExpr` expose for splicing — and defaults by shape: a head with rows under it reads out nothing;
  a light whose only colour mode is `onoff` renders the same row as a TOGGLE — full-bleed fill in the
  accent, the track itself a button, no range input — so a group member that cannot dim still
  looks like the rows beside it),
  **what happens to a label that does not fit** (`core/text.pkl`): every card that shows text places
  the same two boxes — the outer one the card's layout sizes, the inner one a marquee can move — and
  the dashboard picks `wrap`/`clip`/`scroll` once on its entry's `textOverflow` (`clip` by default),
  any node overriding it with `.textOverflow(…)`. The mode travels as INHERITED custom properties, so
  the nearer ancestor wins rather than stylesheet order; `scroll` moves by exactly the overflow
  (`min(0px, calc(100cqw - 100%))`, the box as a query container against the run's own width), so a
  label that fits sits still and nothing measures anything. A tab bar is deliberately outside it — it
  scrolls sideways, so a tab is reached rather than shortened,
  expr/exprOf,
  the `c.tap` namespace (`service`/`serviceValue`/`stateService`/`byDomain`/`toggle`/`navigate`/the popup ones — no `Tap` suffix, the namespace carries it), **the default tap** (ADR 0016 — an entity card is clickable
  by a default derived from its OWN entity: its domain's service where it has one, more-info where
  it does not, and `tapAction = null` to opt out entirely. Every route is a build-time literal except the
  four `CallByState` domains. `c.tap.toggle` is now the explicit escape hatch, not the default,
  and a `c.button`/`c.pill`/`c.toggle` with no action and no entity is a BUILD error rather than a
  post HA rejects), capability-conditional composition off the dump's groups
  (`c.slider(l.colourTemp)` / `c.effectPills(l.effects)` — a card takes the capability GROUP, which
  carries its `owner`, so ONE argument is both the values and the subject: the entity is named once,
  capabilities are discovered by completion on `l.`, and passing a group the entity lacks is a
  nullability mismatch pkl-lsp reports BEFORE eval. `lightControls` is the `when`-per-capability
  shortcut. Do not "simplify" this into a builder method or a selector enum — both hide the choice
  from static analysis; ADR 0013 "Shapes considered" has the four attempts),
  tabs, popups/surfaces, more-info (`c.entityCard(e) |> c.informative`, or the `c.moreInfo(e)` tap:
  an INLINE popup holding the entity's card, its domain controls, and `c.entityInfo(e)` — the id plus
  every attribute it reports, as one live text block, since a template cannot loop over attributes.
  It is what a tap on a non-actionable entity does instead of nothing — `c.informative` is now
  only needed to FORCE more-info on a card whose domain does have an action),
  candidate sets (`q.from(...).where(...).render(...)`), conditional sections (`` c.iff(cond).then(..).`else`(..) `` — state-activated
  surfaces on the tabs machinery, ADR 0007; `cond` NAMES its entities, via `q.entity(e)` or a
  `q.from(...)` aggregate — a comparison that names none is a validate error), three-tier slider config — see ADR 0006 for the deliberate API shape
  (`openPopup`/`openPopupInline` split, `cssClass`) and Pkl gotchas before extending. `PklBuild`
  renders the evaluated module to JSON backend-side (no `output` blocks in entries) and watches the
  precise `Analyzer.importGraph` import set. The old `*.jsonnet`/`*.libsonnet` sources remain on
  disk as inert porting references only (the five real dashboards are being hand-ported); they are
  never evaluated and must not be extended.

#### Pkl: verify semantics empirically, never from intuition

Pkl (pinned: pkl-core **0.32.1**) has unusual semantics; wrong guesses compile into confusing
errors. When unsure, **run a 2-minute spike** instead of reasoning from analogy: a scratch dir
with a `lib.pkl` + `entry.pkl` and a scala-cli runner —

```scala
//> using dep org.pkl-lang:pkl-core:0.32.1
import org.pkl.core.*
@main def run(): Unit =
  val ev = EvaluatorBuilder.preconfigured().build()
  try ev.evaluate(ModuleSource.path(java.nio.file.Path.of("entry.pkl")))
       .getProperties.forEach((k, v) => println(s"$k = $v"))
  finally ev.close()
```

Gotchas spiked on 0.31.1 and carried forward to the 0.32.1 pin — 0.32.x changed no evaluator
semantics, and the suite still pins the ones it covers (full list: ADR 0006, "Pkl authoring
gotchas"):

- Amending ANY parent that isn't a `new` expression **requires outer parens** — method-call
  results (`(c.entityCard(e)) { ... }`), qualified reads (`(c.row) { ... }`), even bare
  in-scope names. Parens-free is a parse error.
- A typed-object amend body accepts only **properties**: bare elements ("Object of type
  `Row` cannot have an element") and `["key"]` entries (Mapping/Dynamic only) are errors.
  So there is no trailing-block call form (`row { a b }` is unreachable); comma-free
  children go through a Listing-typed property (`children { ... }`).
- A Mapping `default` enables **amend-into-existence**: `["detail"] { ... }` on an absent
  key instantiates the default and amends it (how `surfaces`/`tabs` avoid `new`); a
  `Listing`-valued default lets that body add elements directly (no `children` key).
- **Late binding is the core mechanism**: amending a `hidden` prop re-derives everything
  computed from it (that's how card `slots` recompute). Amending a function *parameter*
  (`(n) { entity = ... }`) also works.
- Methods and properties live in **separate namespaces** — `function slider(e)` and a
  function-valued property `slider` can coexist; call syntax picks the method.
- Inside a `new {}` body, `this` rebinds to the new object — capture the outer receiver
  with `let (l = this)` when writing fluent methods on classes.
- **Never name a parameter or local after the property it initialises.** In
  `new Thing { items = items }` the RHS binds to the object's OWN member, not the parameter,
  and recurses ~10 000 deep. The trace points at the field, not the cause, so it reads like a
  cycle in your data. This is the single most frequent trap here — it hit EIGHT times in one
  session (`items`, `op`, `orderBy`, `shapes`, `entities`, `value`, `id`, `agg`). Suffix the
  parameter (`cmpOp`, `xs`, `shapeDefs`).

  **Qualifying with `this` does NOT escape it.** `new { agg = this.agg }` recurses just the same,
  because `this` inside a `new {}` body is the object being built. Capture the receiver first:
  `let (l = this) new { agg = l.agg }`. That is the same `let (l = this)` the fluent-method entry
  above calls for, and it is needed for plain field copies too, not only for method chaining.
- A module-level function called from inside a **class body** must be `const` (the error says
  so, and offers self-import as the alternative).
- Classes are **closed for extension** by default — `open class` (or `abstract class`) to
  subclass. Only stdlib members may have **type parameters**: user-defined generics
  (`class Box<T>`) are rejected outright. That does NOT force `Any` everywhere: a marker
  supertype (`abstract class Candidate { entity_id: String }`) states what a container requires,
  and callers recover the concrete type by annotating the lambda parameter — `(e: LightEntity) -> …`
  works, and a WRONG annotation is caught at application.
- **A typealias may not be cyclic** — "Type alias definitions must not be cyclic". An alias whose
  union mentions a class that contains the alias is rejected, and INLINING the union does not
  help, because the union still closes the loop. Keep the alias for the non-recursive positions
  and let the one or two that would close it take `Any`, with a comment saying why.
- Reserved words that bite as field, property or METHOD names: **`case`**, **`out`**, **`is`**
  (the type-test operator), `import`, `else`, `when`. Backtick them or pick another name —
  `shape` rather than `case`, `stateIs` rather than `is`. Backticking reads badly at the CALL
  site, so for a method prefer renaming.
- `getProperty(name)` / `getPropertyOrNull(name)` / `hasProperty(name)` let the build read a
  property BY NAME — the basis for resolving a named property against candidates.
- Structural equality holds for independently-built objects, and `Map`/`distinct`/`groupBy`
  dedupe by it — so canonicalising a term tree in Pkl is practical.
- `pkl test` gotchas: an `examples` block writes typed instances to `.pcf` as untyped
  `new { … }`, which then **fails to re-read** ("Please specify a parent explicitly"), so the
  expected file can never assert on a second run — capture `new JsonRenderer {}.renderValue(x)`
  instead, which round-trips as a String. And `///` doc comments are a parse error on entries
  inside a `facts` block; use `//`.
- Required (no-default) class properties are **lazy**: a missing value errors only when
  forced, and the trace points at the class definition, not the author's dashboard line.
- `and` / `or` / `not` are legal method names (the operators are `&&`/`||`/`!`).
- Function-valued properties on a *rendered* module cannot be exported — mark them `hidden`.
- `Mapping` preserves insertion order; structurally-equal duplicate keys are a build error.
- `|>` binds looser than call/amend; `Mixin<T>` values and Mixin-returning methods chain
  as pipe stages.

#### Design docs and plans

The repo-wide rule (plans are deferred, ADRs are rewritten in place, discuss before rewriting)
is in the root `CLAUDE.md`. This module currently has no plan documents: every decision that
was in one lives in `docs/adr/`, and `docs/architecture-rendering-pipeline.md` is the shape of
the running system.

