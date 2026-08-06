# fh-datastar-view — the Datastar dashboard

A simpler HA web frontend (port of the TS prototype in `../ha-frontend`): a two-phase dashboard
where authors write Pkl and the server keeps the rendered HTML live with Datastar.

Its ADRs are in [`docs/adr/`](../../docs/adr/README.md) — they record the design decisions
(entity card + JSONata transforms, surfaces/tabs, dynamic groups, the slot model) with their
rationale. The repo-wide ADR routine is in the root `CLAUDE.md`.

[`docs/architecture-rendering-pipeline.md`](../../docs/architecture-rendering-pipeline.md) is the map of the RUNTIME half —
how a state change becomes bytes, what is shared per slug vs. per connection, and the three node
kinds (static, dynamic, flip). It is current-state and must move with the code, in the
same commit; ADRs that change the pipeline update it too.

#### Workflow for changes here

1. Read the relevant ADR(s) first; for Pkl work also read ADR 0006 and the "Spike results"
   section of `docs/plan-pkl-authoring-ergonomics.md` before writing any Pkl. For anything in
   `fh/view/runtime` read `docs/architecture-rendering-pipeline.md` — and update it alongside the change.
2. Verify with `sbt 'fh-datastar-view/testFull'` — the suites build **fake dumps** in temp
   dirs and run the real library modules through the full pipeline, so **no live HA is
   needed** for tests. (`sbt dashboardBuild` *does* need the live instance — it fetches the
   entity dump.)
3. For refactors that must not change behavior (authoring-API changes, ergonomics work): the
   evaluated `{cards, card}` JSON is the contract. The safety net is the **wire-format
   snapshots** in `PklBuildSuite` (`src/test/resources/snapshots/`): they byte-identity-check
   the evaluated demo entries, so `sbt 'fh-datastar-view/testFull'` catches any drift.
   Regenerate them deliberately when the wire format is *meant* to change — but NOT by
   exporting `FH_UPDATE_SNAPSHOTS=1` into the shell: the long-lived sbt server keeps its
   start-time env forever, leaving the gate silently stuck in regenerate mode. Use the
   scoped form instead:
   `sbt 'eval sys.props.put("FH_UPDATE_SNAPSHOTS", "1"); fh-datastar-view/testFull; eval sys.props.remove("FH_UPDATE_SNAPSHOTS")'`.
   The backend model (`Dashboard.scala`) should not need to change for
   authoring-layer work (the layout-cell fields — `Cell`, `CardDef.wrapAsCell`
   — were the sanctioned structural exception; see ADR 0008).
4. Visual changes cannot be verified from the terminal — ask the user to confirm in the
   browser (`sbt dashboardServe`), per ADR 0006.
5. Datastar questions (attribute syntax, SSE semantics): consult the **local** reference in
   `docs/reference/datastar/` before searching the web. Attributes use colon syntax
   (`data-on:click`, not `data-on-click`).
6. Format with `sbt 'scalafmt; Test/scalafmt'` (Scala only; there is no formatter for the Pkl
   sources). **Both tasks, always** — `scalafmt` covers `Compile` only, so a test-only
   formatting change passes locally and then fails CI, which runs the `scalafmt --test` CLI
   over every file. Verified by misformatting a test source: `sbt scalafmt` leaves it
   untouched, `Test/scalafmt` fixes it. Note the quotes: unquoted
   `sbt scalafmt Test/scalafmt` is a parse error in sbt 2.0.

#### Key files

| File | Role |
|---|---|
| `fh/view/model/Dashboard.scala` | Wire model `{cards, card}`, `LayoutNode` (incl. `Dynamic`), `Predicate` AST, `validate` |
| `fh/view/build/SourceEval.scala` | The authoring-language seam: `.pkl` → `PklBuild` (Pkl is the only evaluated language) |
| `fh/view/build/PklBuild.scala` / `PklDump.scala` | Pkl evaluation (pkl-core 0.32.1) + typed dump generation (rendered to text, packaged — never written as a loose file) |
| `fh/view/build/LibPackage.scala` / `AddonBootstrap.scala` | The server boot path (ADR 0010): `@fh-dashboard` packaged into a persistent cache + workspace seed (write-once user files, NEVER moved/overwritten — old `lib/`/consumer left alone, delete-to-reseed to adopt package-form; the only overwrite-with-backup is a dated `.fh/pins.json.backup.<stamp>`, capped at the newest 50, via [[Pins]]). Runs on EVERY start — add-on AND local `sbt dashboardServe` (repo resources as the bundled lib, appdirs cache, workspace `dashboard-local-dev`). **One resolution mode — package-form, everywhere** (server, `BuildApp`, tests): `@fh-dashboard` AND `@fh-home` are cache packages resolved offline via `moduleCacheDir`; there is NO path-form and NO `home/` folder. The workspace scaffold is BYTE-IDENTICAL everywhere — a STATIC, machine-agnostic `.fh/base.pkl` (reads `moduleCacheDir` + the `http.rewrites` target from `.fh/machine.json`, and both pins from `.fh/pins.json`, all via `pkl:json`) + a user `PklProject` + `.gitignore`; the instance SERVES these to a laptop's `fh init` over `/system/pkl/{base.pkl,PklProject,gitignore}` (no two copies). The two per-machine values (cache dir + instance URL) live in a gitignored `.fh/machine.json` — the ONLY file that differs between the instance and a git copy. A loaded `PklProject` with no `moduleCacheDir` is a HARD ERROR (`PklBuild.cacheDir`), never a silent fallback |
| `fh/view/build/DumpPackage.scala`, `scripts/fh` (repo root) | The dump as a content-versioned package (`fh-home@1.0.0-g<hash>`; the lib is content-versioned the same way — `fh-dashboard@<base>-g<hash>`, base from `lib/PklProject`, hash-suffix to be dropped for normal version bumps once the lib stabilizes), the ONLY form it takes anywhere: `seedFromText` builds+seeds it into the cache and rewrites `.fh/pins.json` on every dump render (server startup + `DumpRefresh`). Consumed by the instance's own eval AND laptops (via `/system/pkl/packages`). Plus the `fh` scala-cli script (`init` fetches the served scaffold verbatim + writes this laptop's `.fh/machine.json` + `.fh/pins.json`; `pull` re-pins `@fh-home`; `push`; `init-lsp-fix` writes the rewrite to `~/.pkl/settings.pkl` (the pkl CLI ignores a project's `http.rewrites` in `project resolve <dir>` mode — how IntelliJ syncs); `update`; Typelevel toolkit + decline + in-process pkl-core, installed by curl from GitHub raw, `update` sha256-compares against the repo copy; needs only scala-cli). Its own suite `scripts/fh.test.scala` (weaver) runs via `cd scripts && SCALA_TEST_MODE=true scala-cli test .` — the script gates its dispatcher behind `SCALA_TEST_MODE`. Also a CI step |
| `fh/view/build/DataDump.scala` | Live entity dump fetch/transform |
| `fh/view/build/DumpRefresh.scala` | Runtime dump refresh, validate-then-swap: unchanged ⟺ same content-version; else temp-copy the workspace, seed the new dump package there, re-eval all entries, swap the `.fh/pins.json` pin only if nothing that builds today breaks. No loose file, no dated backup — the previous immutable cache version IS the trail. Driven by HA registry events (`watch_registry` option) + `POST /system/dump/refresh` (the /edit button) |
| `fh/view/runtime/Renderer.scala` / `Server.scala` / `StateStore.scala` | Live re-render, SSE patch diffing, WS-fed state |
| `src/js/` + `package.json` + `vite.config.ts` | The frontend, bundled by **vite 8** into MANAGED resources (`project/NpmPlugin.scala`, `frontendInstall`/`frontendBundle` — a `resourceGenerators` entry, so a plain compile builds it and **node + npm are a build requirement**). ONE build, three entries: `shell.ts` (inlined into every page by `Server.page`), `editor/app.js` (CodeMirror + lsp-client bundled IN — no vendor file, no CDN, no import map), `editor/overlay.js`. Outputs are **content-hashed under `web/` with a `build.manifest`**; nothing spells a filename out — `FrontendAssets` reads the manifest and everything asks by ENTRY NAME (`Server.UrlSyncScript`, the `editAssets` overlay tag, the `__APP_JS__` placeholder in the editor `index.html`), and `Server` serves `/web/:file` `immutable` guarded by that same manifest. Deliberately **not `build.lib`**: lib mode refuses multi-entry for `iife`/`umd`, and `isEsLibBuild` hard-forces `minifyWhitespace: false` (to keep pure annotations for a downstream bundler we do not have), which shipped `app.js` at 654 kB where `rollupOptions.input` emits 421 kB. `shell.js` and `overlay.js` are classic scripts and work as `es` output ONLY because they import nothing — a shared module would split a chunk and give both a real `import`, so `EditorSuite` guards their absence. Nothing built is committed; new code is TypeScript (`tsc --noEmit` runs as part of the build), the ported editor sources stay JS |
| `resources/dashboards/lib/{hass,components,tokens}.pkl` | Pkl domain schema + card classes (templates live ON the classes, registry derived via pkl:reflect) + shared HA-named design tokens |
| `resources/dashboards/lib/theme.pkl` | The theme CONTRACT (`open class Theme` + the reusable `layoutCss` for the `fh-` layout classes) and the theme-author guide; implementations are the `theme-*.pkl` siblings |
| `resources/dashboards/lib/theme-beer.pkl` | BeerCSS MD3 theme, the DEFAULT (via entry.pkl) and only shipped implementation — read `docs/plan-beercss-theme.md` + the `beercss` skill first; its module doc explains the body-specificity color bridge + the amendable `md3Light`/`md3Dark` palettes |
| `resources/dashboards/lib/entry.pkl` | Entry base module — entries `amends` it, setting only `card` (+ optional `title`/`surfaces`/`theme`) |
| `resources/dashboards/lib/PklProject` | The `@fh-dashboard` package manifest — the shared lib, packaged into the cache by `LibPackage`. (The top-level consumer `PklProject` + `home/` are gone: workspaces are bootstrapped package-form; the repo `lib/` is bundled-lib SOURCE, not a path-form checkout.) |
| `resources/dashboards/pkl-demo.pkl`, `pkl-tabs.pkl` | Pkl entry dashboards (the demo/example entries) |
| `resources/dashboards/*.jsonnet`, `components.libsonnet` | **Inert porting references only** — no longer evaluated; do not extend (see below) |
| `src/test/.../PklBuildSuite.scala` | The Pkl track's main safety net (fake dumps, full pipeline) |

A two-phase dashboard frontend. Authors write a dashboard as **Pkl** (ADR 0006); the server
renders HTML and keeps it live with [Datastar](https://data-star.dev) (SSE HTML-fragment patches
+ action POSTs).

- **Evaluation** (`DashboardBuild`): fetches the live entity dump (`DataDump`, a port of
  `../ha-frontend/script.sh`), seeds the typed dump as the `@fh-home` content-versioned cache package
  (`DumpPackage.seedFromText` — no loose file), then evaluates
  the entry `.pkl` **in-process via pkl-core** (`PklBuild`, through the `SourceEval` seam) into the
  `{ cards, card }` model — a shared library of named cards (Mustache templates) plus a
  **recursive layout tree** (`card` = its root) of component nodes that reference cards by name.
  Pkl does **composition only** and emits Mustache template strings + static node params; it
  never injects live values, and authors never write node ids (the backend derives stable,
  location-based ids while recursing — `LayoutNode.pathId`).
- **Build phase** (`fh.view.build`, `BuildApp` / `sbt dashboardBuild`): evaluates + persists the
  `dashboard.json` artifact for inspection/CI. The runtime does not need it. `BuildApp` honors
  `DASHBOARD_ENTRY` (default `dashboard.pkl` — which errors until that entry is ported).
- **Runtime phase** (`fh.view.runtime`, `ServerApp` / `sbt dashboardServe`): evaluates the **same
  Pkl entries in memory on startup** (so pkl-core *is* on the startup path — but never on the live
  hot path), pre-compiles the Mustache templates (jmustache, `Templates`), seeds all entity state from
  `/api/states` and keeps it live from the `state_changed` WS stream (`StateStore`, a `Ref` +
  fs2 `Topic`, full attributes; publishes only on real change). On each change it re-renders the
  affected components (`Renderer`, reverse index `entityId -> generated id`) plus the
  query-affected dynamic groups — **per-entity**: an in-place member tick morphs one
  `{gid}_{entity}` child, small membership deltas patch `remove`/`before`/`append`, and only
  heavy churn (≥50% of rendered members, `Server.MaxChurnFraction`) or a post-reload group
  repaints wholesale — and pushes only the fragments whose HTML actually changed (`Server`
  keeps a per-node last-rendered cache; http4s ember).
- **Phase discipline**: leaf templates escape `{{slot}}` values; container templates splice their
  children unescaped via `{{#children}}{{{html}}}{{/children}}`; other raw author values (action
  URLs, ids) use `{{{...}}}`. Pkl sources live in `src/main/resources/dashboards/` (top-level
  `*.pkl` entries + `lib/*.pkl`); the dump is a cache package (never on disk in the repo) and
  `dashboard.json` is generated + gitignored.
  The old `*.jsonnet`/`*.libsonnet` files also still sit here as **inert porting references**
  (the five real dashboards are being hand-ported to Pkl) — the backend never evaluates them and
  they must not be extended.
- Interactivity uses the WS `call_service` command (added to `ha-api`'s `CommandPhase` +
  `HomeAssistantApi.callService`). `POST /sse/action/:domain/:service/:entityId` triggers a no-data
  service; the value-carrying variant `.../:entityId/:key/:value` builds `service_data` (the value
  rides in the URL path, since Datastar template-literal URL interpolation isn't confirmed in v1 —
  use `'.../key/' + $signal` concatenation client-side). The resulting state change flows back over
  the persistent SSE stream.
- Cards (`lib/components.pkl`): `fhgrid`/`fhrow`/`fhcol` containers, `sectionTitle`, `entityCard`,
  `button`, `pill`, `slider` — each is a typed card class carrying its own `cardDef` (Mustache template +
  declared slots), and the emitted `cards` registry is derived by `pkl:reflect`; slots are checked
  by `Dashboard.validate`. Call-style factories / classes return layout nodes referencing a card
  by name; a new container/leaf kind is one class, no Scala change. Datastar attributes use
  **colon** syntax (`data-on:click`,
  `data-bind`, `data-signals`). `SlotSource.default` fills absent/null attributes (e.g. brightness
  when a light is off).
- **Every node is a cell (ADR 0008)**: the renderer wraps every component in an id'd `.fh-cell`
  (the real flex/grid item and Datastar morph target; `CardDef.wrapAsCell = false` is the rare
  opt-out — the tab anchors). `Grid` (`.fh-grid`, 12 columns, cells default to half — HA
  `grid_options` semantics) is the default container; per-node sizing rides in the wire-level
  `cell.classes` via the HA-flavored builders `columns(n)`/`fullWidth()`/`hug()`/`centered()`/`cellClass`
  on the Pkl `LayoutNode` base (chain them AFTER card-specific builders). Dynamic groups flow
  their members the same way (`.fh-group`).
- Dynamic groups: a `LayoutNode.Dynamic` runs a simple property-query AST (`Predicate`:
  And/Or/Not/Cmp over `domain`/`state`/`attr:<name>`) against live state and renders each matching
  entity via the first `case` whose `when` matches (per-entity/per-domain template dispatch).
- **Pkl authoring (ADR 0006)** — the authoring language: dashboards are [Pkl](https://pkl-lang.org),
  typed cards + editor completion. `fh.view.build.SourceEval` is the (Pkl-only) seam;
  everything downstream is source-agnostic. Pkl library modules live in `dashboards/lib/`
  (`hass.pkl` hand-written domain schema, `components.pkl`, the `theme.pkl` contract +
  the `theme-beer.pkl` implementation, `tokens.pkl`, the entry
  scaffold `entry.pkl`); top-level `*.pkl` files are entries that `amends "lib/entry.pkl"` and set
  only `card` (+ optional `title`/`surfaces`/`theme`). Slug = filename; `ServerApp.discoverEntries`
  scans top-level `*.pkl` only. The `@fh-home` dump is a TYPED dump generated by `PklDump` from the live
  fetch and seeded as a cache package (no file on disk). Feature surface: containers (grid/row/column) + the layout-cell builders
  (`columns`/`fullWidth`/`hug`/`centered`/`cellClass`, ADR 0008), sectionTitle/entityCard/button/pill/slider,
  expr/exprOf,
  serviceTap/navigate, tabs, popups/surfaces, dynamic groups (Mapping-branch + render-lambda over a
  typed Predicate AST), conditional sections (`` c.iff(cond).then(..).`else`(..) `` — state-activated
  surfaces on the tabs machinery, ADR 0007), three-tier slider config — see ADR 0006 for the deliberate API shape
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
semantics, and the suite still pins the ones it covers (full list with evidence:
`docs/plan-pkl-authoring-ergonomics.md`, "Spike results"):

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
- Required (no-default) class properties are **lazy**: a missing value errors only when
  forced, and the trace points at the class definition, not the author's dashboard line.
- `and` / `or` / `not` are legal method names (the operators are `&&`/`||`/`!`).
- Function-valued properties on a *rendered* module cannot be exported — mark them `hidden`.
- `Mapping` preserves insertion order; structurally-equal duplicate keys are a build error.
- `|>` binds looser than call/amend; `Mixin<T>` values and Mixin-returning methods chain
  as pipe stages.

#### Design docs and plans

The repo-wide rule (plans are deferred, ADRs are rewritten in place, discuss before rewriting)
is in the root `CLAUDE.md`. Module-specific:

- `plan-pkl-authoring-ergonomics.md` (call-style factories, Mapping-branch dynamic groups,
  fluent predicates) is fully designed and spike-verified but **not yet applied** to
  `components.pkl` — do not assume its API exists in the sources.

