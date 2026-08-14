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

   For changes to the Pkl authoring library itself there is also a pure-Pkl suite (`facts` +
   `examples`, no JVM and no dump), run with the `pkl` CLI at the **same version as the
   `pkl-core` pin** — a different CLI tests different semantics than we ship:

   ```bash
   pkl test modules/fh-datastar-view/src/test/pkl/*.pkl
   pkl test --overwrite modules/fh-datastar-view/src/test/pkl/*.pkl  # accept new example output
   ```

   The tests live outside `lib/` deliberately: `LibPackage` packages that directory into the
   content-versioned `@fh-dashboard` package, so a test module inside it would move the package
   hash and re-evaluate every dashboard. They import the library by relative path for the same
   reason. CI runs them in the `parallel:` block of `cicd.yml`.
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
5. Datastar questions (attribute syntax, SSE semantics): use the `datastar` skill — it points
   to context7 (`/websites/data-star_dev`) for general docs, plus pinned-bundle corrections and
   project conventions context7 won't have. Attributes use colon syntax (`data-on:click`, not
   `data-on-click`).
6. Formatting (Scala only; there is no formatter for the Pkl sources) is handled by the
   `PreToolUse` hook in `.claude/settings.json`, which runs the standalone `scalafmt` CLI
   before every `git add`. `project.git = true` in `.scalafmt.conf` covers every tracked
   source in one pass, so nothing reaches CI's `scalafmt --test` unformatted.

#### Key files

| File | Role |
|---|---|
| `fh/view/model/Dashboard.scala` | Wire model `{cards, card}`, `LayoutNode` (incl. `Dynamic`), `Predicate` AST, `validate` |
| `fh/view/build/SourceEval.scala` | The authoring-language seam: `.pkl` → `PklBuild` (Pkl is the only evaluated language) |
| `fh/view/build/PklBuild.scala` / `PklDump.scala` | Pkl evaluation (pkl-core 0.32.1) + typed dump generation (rendered to text, packaged — never written as a loose file). `PklDump` owns the TYPING design (ADR 0013): capabilities land on a class generated PER ENTITY, so reading one an entity lacks is an eval error, not a null; a MODELLED domain's co-occurring values move to a nullable GROUP on the domain class (`colourTemp: ColourTemp?` — the group is the predicate, one guard covers every value, an unguarded read errors); the generator emits DATA and `hass.pkl` derives the yes/no predicates, so the rules live in one place; `warnings` catches at CODEGEN what Pkl's lazy required-properties cannot (a half-filled group is omitted + reported, never fatal). Only `light` is modelled — every other domain falls through to the per-entity class untouched, which is what makes it incremental. ADR 0013 has the per-domain coverage table and the add-a-domain recipe |
| `fh/view/build/LibPackage.scala` / `AddonBootstrap.scala` | The server boot path (ADR 0010): `@fh-dashboard` packaged into a persistent cache + workspace seed (write-once user files, NEVER moved/overwritten — old `lib/`/consumer left alone, delete-to-reseed to adopt package-form; the only overwrite-with-backup is a dated `.fh/pins.json.backup.<stamp>`, capped at the newest 50, via [[Pins]]). Runs on EVERY start — add-on AND local `sbt dashboardServe` (repo resources as the bundled lib, appdirs cache, workspace `dashboard-local-dev`). **One resolution mode — package-form, everywhere** (server, `BuildApp`, tests): `@fh-dashboard` AND `@fh-home` are cache packages resolved offline via `moduleCacheDir`; there is NO path-form and NO `home/` folder. The workspace scaffold is BYTE-IDENTICAL everywhere — a STATIC, machine-agnostic `.fh/base.pkl` (reads `moduleCacheDir` + the `http.rewrites` target from `.fh/machine.json`, and both pins from `.fh/pins.json`, all via `pkl:json`) + a user `PklProject` + `.gitignore`; the instance SERVES these to a laptop's `fh init` over `/system/pkl/{base.pkl,PklProject,gitignore}` (no two copies). The two per-machine values (cache dir + instance URL) live in a gitignored `.fh/machine.json` — the ONLY file that differs between the instance and a git copy. A loaded `PklProject` with no `moduleCacheDir` is a HARD ERROR (`PklBuild.cacheDir`), never a silent fallback |
| `fh/view/build/DumpPackage.scala`, `scripts/fh` (repo root) | The dump as a content-versioned package (`fh-home@1.0.0-g<hash>`; the lib is content-versioned the same way — `fh-dashboard@<base>-g<hash>`, base from `lib/PklProject`, hash-suffix to be dropped for normal version bumps once the lib stabilizes), the ONLY form it takes anywhere: `seedFromText` builds+seeds it into the cache and rewrites `.fh/pins.json` on every dump render (server startup + `DumpRefresh`). Consumed by the instance's own eval AND laptops (via `/system/pkl/packages`). Plus the `fh` scala-cli script (`init` fetches the served scaffold verbatim + writes this laptop's `.fh/machine.json` + `.fh/pins.json`; `pull` re-pins `@fh-home`; `push` (several entries at once; `--slug` renames a single one; `--write` PUTs the SOURCE to the instance's `/edit/file/<slug>.pkl` instead of the evaluated JSON, so the instance re-evaluates it and it survives a restart; `--watch` re-sends on every `*.pkl` change in the workspace, but only the entries the change reaches — the import graph comes from the same `Analyzer.importGraph` call `PklBuild` uses, so editing one entry re-sends one dashboard and editing a shared module re-sends its importers); `init-lsp-fix` writes the rewrite to `~/.pkl/settings.pkl` (the pkl CLI ignores a project's `http.rewrites` in `project resolve <dir>` mode — how IntelliJ syncs); `update`; Typelevel toolkit + decline + in-process pkl-core, installed by curl from GitHub raw, `update` sha256-compares against the repo copy; needs only scala-cli). Its own suite `scripts/fh.test.scala` (weaver) runs via `cd scripts && SCALA_TEST_MODE=true scala-cli test .` — the script gates its dispatcher behind `SCALA_TEST_MODE`. Also a CI step |
| `fh/view/build/RegistryDump.scala` | **The** dump (there is no second path — the old Jinja `/api/template` one was deleted; it sat at 228k of that endpoint's hard 262144-char output cap and could not reach `entity_category` at all). Built from the WS registries joined onto the `subscribe_entities` snapshot, which is the SPINE (a LEFT join from states — the registry also lists disabled entities no dashboard can render). Also owns the shared `transform`/`entityKey`/`slug` keying. `build` is pure — `RegistryDumpSuite` tests it with no HA. Attributes are filtered to `CapabilityAttributes`, widenable via `FH_DUMP_ATTRIBUTES` (comma-separated, additive). **The filter protects the dump's CONTENT HASH, not just freshness** — the dump is a content-addressed package, so a volatile attribute re-seeds it and re-evaluates every dashboard on every change (this is why `entity_picture` is excluded: rotating `access_token`). Verify a candidate by watching `subscribe_entities` deltas before adding it. See ADR 0013 |
| `fh/view/build/DumpRefresh.scala` | Runtime dump refresh, validate-then-swap: unchanged ⟺ same content-version; else temp-copy the workspace, seed the new dump package there, re-eval all entries, swap the `.fh/pins.json` pin only if nothing that builds today breaks. No loose file, no dated backup — the previous immutable cache version IS the trail. Driven by HA registry events (`watch_registry` option) + `POST /system/dump/refresh` (the /edit button) |
| `fh/view/runtime/Renderer.scala` / `Server.scala` / `StateStore.scala` | Live re-render, SSE patch diffing, WS-fed state |
| `src/js/` + `package.json` + `vite.config.ts` | The frontend, bundled by **vite 8** into MANAGED resources (`project/NpmPlugin.scala`, `frontendInstall`/`frontendBundle` — a `resourceGenerators` entry, so a plain compile builds it and **node + npm are a build requirement**). ONE build, three entries: `shell.ts` (inlined into every page by `Server.page`), `editor/app.js` (CodeMirror + lsp-client bundled IN — no vendor file, no CDN, no import map), `editor/overlay.js`. Outputs are **content-hashed under `web/` with a `build.manifest`**; nothing spells a filename out — `FrontendAssets` reads the manifest and everything asks by ENTRY NAME (`Server.UrlSyncScript`, the `editAssets` overlay tag, the `__APP_JS__` placeholder in the editor `index.html`), and `Server` serves `/web/:file` `immutable` guarded by that same manifest. Deliberately **not `build.lib`**: lib mode refuses multi-entry for `iife`/`umd`, and `isEsLibBuild` hard-forces `minifyWhitespace: false` (to keep pure annotations for a downstream bundler we do not have), which shipped `app.js` at 654 kB where `rollupOptions.input` emits 421 kB. `shell.js` and `overlay.js` are classic scripts and work as `es` output ONLY because they import nothing; rollup never duplicates code, so one shared module splits a chunk and gives both a real `import`, breaking every page silently. The `fh-assert-self-contained` vite plugin FAILS THE BUILD on that, off rollup's own `chunk.imports`/`exports`, and the document's last line calls `fhScroll` only `if(window.fhScroll)` so anything the build cannot see still names itself in the console. Nothing built is committed; new code is TypeScript (`tsc --noEmit` runs as part of the build), the ported editor sources stay JS |
| `resources/dashboards/lib/` (the `@fh-dashboard` package) | THREE tiers by audience (ADR 0015): **`core/`** — `node` (LayoutNode/Node/CardDef + `cardsOf`), `slot` (Slot/Expr + the shared `labelSlot`/`valueSlot`/`secondarySlot`), `icon` (the MDI tables + `iconFor`), `tap` (Tap + the constructors), `surface` (SurfaceDef/activations/**Popup**), `predicate` (the query wire AST) — what a COMPONENT author imports; **`layout.pkl`** — Row/Column/Grid; **`components.pkl` + `components/`** (`text`, `entity`, `control`, `slider`, `surface`, `light`, `moreinfo`) — what a DASHBOARD author imports, where `components.pkl` is a FACADE declaring no cards; **`recipes.pkl`** — whole opinionated sections. `internal/dump-base.pkl` is generator-facing, `entry.pkl` stays at the root (it is what every entry amends). Two rules the facade cannot break: every re-export carries an explicit TYPE, and it must never use `extends` — untyped re-exports and `extends` each kill editor completion THROUGH the module (`docs/pkl-lsp-extends-completion-bug.md`) |
| `resources/dashboards/lib/{hass.pkl,hass/light.pkl,tokens.pkl}` | Pkl domain schema (`hass.pkl` stays at the package ROOT — every generated dump emits `import "@fh-dashboard/hass.pkl"`, and that URI identity is load-bearing; the vendored per-domain constants live under `hass/`) + shared HA-named design tokens. `hass.pkl` gives every SCOPE the same five names — `lights`/`sensors`/`switches`/`generic`/`all` — on `Area` (generator-filled), `Floor` (derived from its areas) and `Device` (type tests over its entities), matching the dump's house-wide lists, so `q.from(...)` takes any scope |
| `resources/dashboards/lib/internal/dump-base.pkl` | The house-wide lists as a CONTRACT (`open module`, `List()` defaults); the generated `@fh-home/dump.pkl` **extends** it (not `amends` — an amending module may not declare classes). This is what lets the starter dashboard query `dump.lights` on a home that has none: an empty list, not `Cannot find property` on a first boot |
| `resources/dashboards/lib/query.pkl` | The candidate-set query surface (`q.from(...).where(...).render(...)`), imported as `@fh-dashboard/query.pkl`. Folds registry conditions away at BUILD time (they select candidates) and emits only live ones as per-member guards; a live attribute NAME is checked against `hass.Entity.volatileAttrs` (capability-derived, so a typo is a build error — `q.attr(n)` is the unchecked escape for an integration-specific name), into the `SetNode`/`SetMember`/`SetClause` wire classes that live in `core/predicate.pkl` (query.pkl depends on the CORE, never on the shipped cards). **Plain Pkl stays the first answer** — a `for` over a typed dump list is still the right way to render a fixed set of lights; this earns its place only when membership must react to live state. Covers `where`/`orderBy`/`limit`/`caseOf`/`render`, aggregates (`count`/`any`/`none`/`all` — these are also what an `If` condition is built from), nested sets, and `q.entity(e)` for naming a DIFFERENT entity than the member. See `docs/adr/0003-dynamic-groups.md` |
| `resources/dashboards/lib/hass/light.pkl` | HA's `light` domain model VENDORED — the `ColorMode` union + `LightEntityFeature` bits (EFFECT 4, FLASH 8, TRANSITION 32). Safe to copy: HA's `*EntityFeature` IntFlags are APPEND-ONLY, never renumbered (vacant 1/2 are removed flags). `HaLight.scala` is the generator's copy and `HaLightSuite` asserts the two agree. Imported by `hass.pkl` **with an `as` alias** — Pkl binds an import to its FILE name, so the alias keeps `light` from reading as a light ENTITY; a `///` doc comment on an import is also a parse error. Other domains follow this pattern, not yet copied |
| `resources/dashboards/lib/theme.pkl` | The theme CONTRACT (`open class Theme` + the reusable `layoutCss` for the `fh-` layout classes) and the theme-author guide; implementations are the `theme-*.pkl` siblings |
| `resources/dashboards/lib/theme-beer.pkl` | BeerCSS MD3 theme, the DEFAULT (via entry.pkl) and only shipped implementation — read `docs/plan-beercss-theme.md` + the `beercss` skill first; its module doc explains the body-specificity color bridge + the amendable `md3Light`/`md3Dark` palettes. Also loads **MDI** (`@mdi/font`, pinned) because HA's own entity `icon` attribute is an MDI name — its doc carries the ~394 KB cost and the build-time SVG-inlining plan that should replace it |
| `resources/dashboards/lib/entry.pkl` | Entry base module — entries `amends` it, setting only `card` (+ optional `title`/`surfaces`/`theme`) |
| `resources/dashboards/lib/PklProject` | The `@fh-dashboard` package manifest — the shared lib, packaged into the cache by `LibPackage`. (The top-level consumer `PklProject` + `home/` are gone: workspaces are bootstrapped package-form; the repo `lib/` is bundled-lib SOURCE, not a path-form checkout.) |
| `resources/dashboards/pkl-demo.pkl`, `pkl-tabs.pkl` | Pkl entry dashboards (the demo/example entries) |
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
  a mount whose whole content arrived or left at once, or a post-reload group,
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
- Cards (`lib/components/`, re-exported by `lib/components.pkl` — ADR 0015): `fhgrid`/`fhrow`/`fhcol` containers, `sectionTitle`, `entityCard`,
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
  on the Pkl `LayoutNode` base (chain them AFTER card-specific builders). Candidate sets flow
  their members the same way (`.fh-group`).
- Candidate sets: a `LayoutNode.SetNode` carries a STATIC candidate list (decided at build time
  from the dump) plus per-candidate guarded renderings; the runtime decides only PRESENCE (the
  first clause whose `when` holds; none = not rendered) and ORDER. Authored through
  `@fh-dashboard/query.pkl`. The query-driven `LayoutNode.Dynamic` it replaced is deleted — see
  ADR 0003 for why, and `docs/adr/0003-dynamic-groups.md` for the derivation.
- **Pkl authoring (ADR 0006)** — the authoring language: dashboards are [Pkl](https://pkl-lang.org),
  typed cards + editor completion. `fh.view.build.SourceEval` is the (Pkl-only) seam;
  everything downstream is source-agnostic. Pkl library modules live in `dashboards/lib/`
  (`hass.pkl` hand-written domain schema, the `core/` kit + `components/` families behind the
  `components.pkl` facade — ADR 0015, the `theme.pkl` contract +
  the `theme-beer.pkl` implementation, `tokens.pkl`, the entry
  scaffold `entry.pkl`); top-level `*.pkl` files are entries that `amends "lib/entry.pkl"` and set
  only `card` (+ optional `title`/`surfaces`/`theme`). Slug = filename; `ServerApp.discoverEntries`
  scans top-level `*.pkl` only. The `@fh-home` dump is a TYPED dump generated by `PklDump` from the live
  fetch and seeded as a cache package (no file on disk). Feature surface: containers (grid/row/column) + the layout-cell builders
  (`columns`/`fullWidth`/`hug`/`centered`/`cellClass`, ADR 0008), sectionTitle/entityCard/button/pill/slider,
  a slider with `children` — the SAME card, holding member rows that are ORDINARY nodes: the head is
  its `self` and the members its mount, so a master state change never repaints a row; every slider
  carries its entity's own `iconFor` badge unless told otherwise (`icon = null` for none), and
  `icon`/`secondary`/`tap` are optional pieces a plain row simply doesn't carry
  (`c.sliderGroup(master, members)` is the shorthand; `.readout(…)` picks what a line reads out —
  `"percent"`/`"state"`/`"none"`, or any `expr`/`exprOf`, the names being shorthands for the two
  readings that need the card's resolved axis config, which `percentExpr`/`valueExpr`/`minExpr`/
  `maxExpr` expose for splicing — and defaults by shape: a head with rows under it reads out nothing),
  expr/exprOf,
  serviceTap/serviceValueTap/navigate, capability-conditional composition off the dump's groups
  (`c.slider(l.colourTemp)` / `c.effectPills(l.effects)` — a card takes the capability GROUP, which
  carries its `owner`, so ONE argument is both the values and the subject: the entity is named once,
  capabilities are discovered by completion on `l.`, and passing a group the entity lacks is a
  nullability mismatch pkl-lsp reports BEFORE eval. `lightControls` is the `when`-per-capability
  shortcut. Do not "simplify" this into a builder method or a selector enum — both hide the choice
  from static analysis; ADR 0013 "Shapes considered" has the four attempts),
  tabs, popups/surfaces, more-info (`c.entityCard(e) |> c.informative`, or the `c.moreInfo(e)` tap:
  an INLINE popup holding the entity's card, its domain controls, and `c.entityInfo(e)` — the id plus
  every attribute it reports, as one live text block, since a template cannot loop over attributes.
  Issue #106's precondition: it is what a tap on a non-actionable entity can do instead of nothing),
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
is in the root `CLAUDE.md`. Module-specific:

- `plan-pkl-authoring-ergonomics.md` (call-style factories, Mapping-branch dynamic groups,
  fluent predicates) is fully designed and spike-verified but **not yet applied** to
  `components.pkl` — do not assume its API exists in the sources.

