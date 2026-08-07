- global system that imports all dashboards?
- should we also do jinja2 as ha templates? https://github.com/HubSpot/jinjava. jinja2 over mustache as that is more familiar to HA users.
- TODO pick up changes in the static sets. Like a new area or a new entity. That must recreate the dump and recreate a dashboard.json
- [x] TODO some popup if not connected
- a json spec step for validating everything?
- switch to handlebars? from mustache, For more custom transformation on static tweaks per instance. Need a cache setup that caches based on the hash of the generated template string or something since we need to compile the template for multiple instances. But share where we can. or jinja2, or the thing from shopify
- a worker for connection to backend api? so we can seamlessly switch between in home connection and a remote connection?
- move from datastar to htmx? Add hyprscript for client side scripting?
- components api amke it clear what is static injected variables, what goes to templating backend rendering and what are values that the client side can script on. SHould JSONata be used to determine values that are injected?
- lots of design stuff, like tabs, are added to themes.libsonnet, should some of that be injectable from the components themselves?
- Parse AST of Pkl directly and use that to validate jsonata. Provide positional errors.
- [x] condital component to filter them out, if component.
- (cd modules/fh-datastar-view/editor-src; npm install), do this from sbt
- [x] reload page on not component changes but layout 
- BuildPhaseSuite: test based on request response caching json structure to make intention clearer
- [x] VisualSnapSHot: for ci dir for images, create tempdir used in GHA and use that for directory to save files
- FixtureDashboard: base the structure on PKL instead of internal structure
- assets-cache fallback to a temp directory or xdg config directory
- tests should not have one named functional. the suites should be appropropritatley places and be functional tests
- TestServer must be rewritten to use ServerApp instead of duplicating stuff. And ServerApp must support this
- [x] HALowLEvel Stream result and remove topic, less things
- add elif
- button, how to click action?
- test suite to test if pkl compiles or not. for.ex c.button("TODO unrepresentable", c.toggleTap) should be a compile error
- ServerApp to class, with http4s routes. TestServer need not duplicate too much
- server does not pickup new pkl dashboard files
- failed dashboard parse during startup; will not be accessible after it is fixed in the code
- house data into sqllite? read from that?
- an action push should hold until complete and if failed revert the change
- https://github.com/google/closure-compiler for javascript inlined?
- are we pruning to only latest on resume? a long forgotten tab had quite a walkthrough of changes
- setmodulecachedir, needed at all in pklbuild?
- use valuevisitor to parse pkl jsonata to get correct positions for syntax error?
- pkl syntax
    - c.button(d.light_test) // with tap and verything
    - d.dynamic(d.entity.has(d.domain, h.domains.light).and(d.state.is(h.domain.light.states.on))))
- split compinents in structure (layout setup, if, dynamic) and components
  - move dynamic grup into own class/continer


---
TODO document the API surface
jsonata https://docs.jsonata.org/programming

---

TODO conditions here? for.ex when over X time, dont turn on sofies room light to max? or wrong place?
