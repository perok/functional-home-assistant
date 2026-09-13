# TODO — the old brain-dump, audited

Every line of the original list, checked against the tree on 2026-09-13 and sorted by what is
actually true of it now. Nothing was dropped; git history has the original order.

## Done

- [x] **A global entry that imports all dashboards.** `site.pkl` is the ONE entrypoint naming
      every dashboard it serves (ADR 0021). TODO2 had parked this as "unclear need" — it shipped
      anyway, for the build story rather than the navigation one.
- [x] **Pick up changes in the static sets (new area, new entity) and rebuild the dump.**
      `RegistryDump` + `DumpRefresh`, on by default via `FH_WATCH_REGISTRY`.
- [x] **Some popup if not connected.** Two distinct failures, presented separately — see the
      disconnected-indicator entry in TODO2.
- [x] **`npm install` from sbt.** `project/NpmPlugin.scala`: `frontendInstall` (`npm ci`) and
      `frontendBundle` (vite), wired as a resource generator so a plain `compile` builds it.
- [x] **Conditional component to filter them out.**
- [x] **Reload the page on layout (not component) changes.**
- [x] **VisualSnapshot: a CI directory for images.**
- [x] **`HALowLevel` returns a stream; the topic is gone.**
- [x] **Button click actions.** `c.tap.toggle` / `c.tap.navigate(…)`, with the in-flight
      semantics of ADR 0019.
- [x] **An action push holds until complete and reverts on failure.** ADR 0019 (an action in
      flight) and ADR 0025 (a value in flight).
- [x] **The server picks up new Pkl dashboard files.** The workspace DIRECTORY is watched, not
      just known imports (`ServerApp.isSourceEvent`), and `reloadSite` reports `Change.Added`.
- [x] **A dashboard that failed to parse at startup becomes reachable once fixed.** ADR 0018;
      `reloadSite` emits `Recovered`, and `ServerAppSuite` covers the boot half.
- [x] **Prune to the latest on resume.** `FragmentLog.pruned` truncates below `Sessions.floor`.
- [x] **`setModuleCacheDir` — needed at all?** Yes: it is the ONE resolution mode (ADR 0010);
      `PklBuild.scala:79` says why.
- [x] **Split components into structure vs components; move the dynamic group out.** ADR 0015 —
      `lib/core/` is what a component author extends, `lib/components*` is what a dashboard author
      writes against.
- [x] **Document the API surface.** `docs-pkl-components.md`.
- [x] **Pkl syntax for `c.button(entity)` and a dynamic query form.** `q.from(…)`,
      `q.entity(…).stateIs(…)`, `hass.lights(dump.areas.stue.all)`. See the open item below for
      the part that is still unrepresentable.

## Superseded — the question stopped applying

- **Handlebars / jinja2 / jinjava instead of Mustache**, and the template-compilation cache that
  went with it. Parked in TODO2: logic-in-templates is what the transform layer is for.
- **JSONata everywhere** — the transform language is CEL (`fh/view/runtime/Cel.scala`). That
  retires "should JSONata decide injected values", "parse the Pkl AST to validate JSONata", and
  "use a ValueVisitor for JSONata positions". The wish underneath the last two — *positional*
  errors from the transform layer — is unaddressed and worth re-raising as its own item if it
  still bites.
- **A JSON spec step for validating everything** — Pkl's type system is that step (ADR 0006).
- **Design details living in `themes.libsonnet`** — the jsonnet track is deleted (#23). Themes are
  Pkl, ADR 0015 splits core from components, and ADR 0020 settles who owns the wrapper class.
- **Closure Compiler for inlined JavaScript** — vite bundles the frontend, and
  `vite.config.ts`'s `fh-assert-self-contained` plugin fails the build on an unbundled import.
- **`assets-cache` falling back to a temp/XDG directory** — decided against, deliberately.
  `AddonBootstrap.scala:227` records the reason: appdirs reads `XDG_DATA_HOME`, which leaked. The
  relocation knob is `FH_ASSETS_DIR`.
- **A worker abstraction for in-home vs remote connections** — parked in TODO2, blocked on the
  deployment story.
- **htmx + hyperscript instead of Datastar** — parked in TODO2; no identified Datastar limitation.
- **Automation-style conditions** ("don't set the light to max after 22:00") — belongs in the
  `home` automations track, not the view layer.

## Still open

- **`elif`.** Only `c.iff(p).then(…).\`else\`(…)` exists; there is no else-if chain.
- **A test that asserts some Pkl does NOT compile.** The motivating example is still sitting in
  the tree: `dashboard-local-dev-server/pkl-if.dashboard.pkl` carries
  `c.button("TODO unrepresentable", c.tap.toggle)` — a tap with no entity, which should be a
  compile error and is not.
- **Test packages named after their layer, not `functional`.**
  `modules/fh-datastar-view/src/test/scala/fh/view/functional/` is still there.
- **`TestServer` folded into `ServerApp`.** Half done: `ServerAppSuite` now drives the real
  `prepareRenderers` -> `liveServer` path, but `TestServer` survives and is still used by
  `FunctionalSuite` and `ActionSignalNamesSuite`. Related: the browser-shaped test seam in TODO2.
- **`BuildPhaseSuite`: make the request/response caching structure the thing asserted**, so the
  intent is legible from the test.
- **`FixtureDashboard`: build the fixture from Pkl** rather than from the internal structure.
- **House data in SQLite and read from there.** Never explored; no design attached. Note the
  history-view plan (PR #365) makes a related but different call — series data stays in HA's
  recorder and is fetched, not mirrored.
