- [x] ~~global system that imports all dashboards?~~
      `site.pkl` is the ONE entrypoint naming every dashboard it serves (ADR 0021). TODO2 had
      parked this as "unclear need"; it shipped anyway, for the build story rather than the
      navigation one.
- [ ] should we also do jinja2 as ha templates? https://github.com/HubSpot/jinjava. jinja2 over mustache as that is more familiar to HA users.
      Still open, and for the reason stated: an HA user already writes jinja2, so it is the one
      option here that removes something to learn rather than adding it.
- [x] ~~TODO pick up changes in the static sets. Like a new area or a new entity. That must recreate the dump and recreate a dashboard.json~~
      `RegistryDump` + `DumpRefresh`, on by default via `FH_WATCH_REGISTRY`.
- [x] ~~TODO some popup if not connected~~
      Two distinct failures, presented separately — see the disconnected-indicator entry in TODO2.
- [ ] a json spec step for validating everything?
      Still relevant. Pkl's type system covers what we author today (ADR 0006), but a spec is the
      thing to reach for if the surface grows beyond what Pkl alone can state.
- [ ] switch to handlebars? from mustache, For more custom transformation on static tweaks per instance. Need a cache setup that caches based on the hash of the generated template string or something since we need to compile the template for multiple instances. But share where we can. or jinja2, or the thing from shopify
      The "custom transformation" half is answered — CEL is the transform layer (ADR 0027). The
      engine question is the jinja2 one above and stays with it.
- [ ] a worker for connection to backend api? so we can seamlessly switch between in home connection and a remote connection?
      Still relevant. Parked in TODO2 on the deployment story (home-addon) maturing, not on the
      idea.
- [ ] move from datastar to htmx? Add hyprscript for client side scripting?
      Parked in TODO2 rather than answered: no identified Datastar limitation, so nothing to act
      on yet.
- [x] ~~components api amke it clear what is static injected variables, what goes to templating backend rendering and what are values that the client side can script on. SHould JSONata be used to determine values that are injected?~~
      The transform language is CEL (`fh/view/runtime/Cel.scala`, ADR 0027), and
      `docs-pkl-components.md` documents the static / backend-rendered / client-signal split.
- [x] ~~lots of design stuff, like tabs, are added to themes.libsonnet, should some of that be injectable from the components themselves?~~
      The jsonnet track is deleted (#23). Themes are Pkl, ADR 0015 splits core from components,
      and ADR 0020 settles who owns the wrapper class.
- [ ] Parse AST of Pkl directly and use that to validate jsonata. Provide positional errors.
      JSONata is gone, but the wish underneath is not: the transform layer still reports no
      POSITION for a bad expression. That is the live part of this item.
- [x] ~~condital component to filter them out, if component.~~
      ``c.iff(p).then(…).`else`(…)``, which renders only the taken branch.
- [x] ~~(cd modules/fh-datastar-view/editor-src; npm install), do this from sbt~~
      `project/NpmPlugin.scala`: `frontendInstall` (`npm ci`) and `frontendBundle` (vite), wired
      as a resource generator so a plain `compile` builds it.
- [x] ~~reload page on not component changes but layout~~
- [ ] BuildPhaseSuite: test based on request response caching json structure to make intention clearer
- [x] ~~VisualSnapSHot: for ci dir for images, create tempdir used in GHA and use that for directory to save files~~
- [ ] FixtureDashboard: base the structure on PKL instead of internal structure
- [x] ~~assets-cache fallback to a temp directory or xdg config directory~~
      Decided against, deliberately. `AddonBootstrap.scala:227` records why: appdirs reads
      `XDG_DATA_HOME`, which leaked. The relocation knob is `FH_ASSETS_DIR`.
- [ ] tests should not have one named functional. the suites should be appropropritatley places and be functional tests
      `modules/fh-datastar-view/src/test/scala/fh/view/functional/` is still there.
- [ ] TestServer must be rewritten to use ServerApp instead of duplicating stuff. And ServerApp must support this
      Half done: `ServerAppSuite` drives the real `prepareRenderers` -> `liveServer` path, but
      `TestServer` survives and is still used by `FunctionalSuite`, `UseCaseSuite`,
      `ActionSignalNamesSuite` and `SmokeSuite`. Related: the browser-shaped test seam in TODO2.
- [x] ~~HALowLEvel Stream result and remove topic, less things~~
- [ ] add elif
      Only ``c.iff(p).then(…).`else`(…)`` exists; there is no else-if chain.
- [x] ~~button, how to click action?~~
      `c.tap.toggle` / `c.tap.navigate(…)`, with the in-flight semantics of ADR 0019.
- [ ] test suite to test if pkl compiles or not. for.ex c.button("TODO unrepresentable", c.toggleTap) should be a compile error
      The motivating example is still live in the dev workspace:
      `dashboard-local-dev-server/pkl-if.dashboard.pkl:37` carries
      `c.button("TODO unrepresentable", c.tap.toggle)` — a tap with no entity.
- [ ] ServerApp to class, with http4s routes. TestServer need not duplicate too much
      Same work as the TestServer item above.
- [x] ~~server does not pickup new pkl dashboard files~~
      The workspace DIRECTORY is watched, not just known imports (`ServerApp.isSourceEvent`), and
      `reloadSite` reports `Change.Added`.
- [x] ~~failed dashboard parse during startup; will not be accessible after it is fixed in the code~~
      ADR 0018; `reloadSite` emits `Recovered`, and `ServerAppSuite` covers the boot half.
- [ ] house data into sqllite? read from that?
      Never explored, no design attached. Note the history-view plan (PR #365) makes a related but
      different call: series data stays in HA's recorder and is fetched, not mirrored.
- [x] ~~an action push should hold until complete and if failed revert the change~~
      ADR 0019 (an action in flight) and ADR 0025 (a value in flight).
- [ ] https://github.com/google/closure-compiler for javascript inlined?
      Still relevant, and it is about the JS written INSIDE Pkl modules — a card's client-side
      expressions, e.g. `progress.pkl`'s fill-width — not the vite bundle. Nothing checks or
      minifies those strings today.
- [x] ~~are we pruning to only latest on resume? a long forgotten tab had quite a walkthrough of changes~~
      `FragmentLog.pruned` truncates below `Sessions.floor`.
- [x] ~~setmodulecachedir, needed at all in pklbuild?~~
      Yes: it is the ONE resolution mode (ADR 0010); `PklBuild.scala:79` says why.
- [x] ~~use valuevisitor to parse pkl jsonata to get correct positions for syntax error?~~
      JSONata is gone; the surviving wish is the positional-errors line above.
- [x] ~~pkl syntax~~
      `q.from(…)`, `q.entity(…).stateIs(…)`, `hass.lights(dump.areas.stue.all)`. The tap-carrying
      button form exists too; what is still unrepresentable is covered by the compile-error test
      item above.
    - c.button(d.light_test) // with tap and verything
    - d.dynamic(d.entity.has(d.domain, h.domains.light).and(d.state.is(h.domain.light.states.on))))
- [x] ~~split compinents in structure (layout setup, if, dynamic) and components~~
      ADR 0015: `lib/core/` is what a component author extends, `lib/components*` is what a
      dashboard author writes against.
  - ~~move dynamic grup into own class/continer~~


---
- [x] ~~TODO document the API surface~~
      `docs-pkl-components.md`.
- [x] ~~jsonata https://docs.jsonata.org/programming~~
      The transform language is CEL (ADR 0027).

---

- [ ] TODO conditions here? for.ex when over X time, dont turn on sofies room light to max? or wrong place?
      "or wrong place" is the answer: this belongs in the `home` automations track, not the view
      layer. Parked in TODO2 on that basis.
