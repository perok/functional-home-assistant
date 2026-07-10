# fh-datastar-view — TODO

Ordered by value; within each section, smallest first. Parked items at the bottom say why.
(Previous entries that already shipped — Pkl track, chrome-in-theme, import watching,
query-scoped dynamic re-renders, column layout — were removed; git history has them.)

## Quick wins

- [x] Wire-format snapshot test: snapshot the evaluated `{cards, card}` JSON of the Pkl demo
      entries in PklBuildSuite so authoring-layer refactors are byte-identity-checked by
      `sbt test`, not manual diffing. Land this BEFORE implementing
      docs/plan-pkl-authoring-ergonomics.md — it is that plan's safety net.
- [ ] `pkl-spike` skill (`.claude/skills/pkl-spike/`): package the scala-cli + pkl-core
      spike harness (lib.pkl + entry.pkl + runner, see the template in CLAUDE.md) so
      "verify Pkl semantics empirically" is a one-command habit.
- [ ] (project-wide) Move the HA bearer token out of `build.sbt` (`secretToken`) into an
      env var / untracked `.env` — security hygiene, and stops the credential being copied
      into new files during refactors.
- [x] Page title: replace the hardcoded `<title>Home Assistant</title>` (Server.scala) with a
      per-dashboard title — new optional top-level `title` in the dashboard model, falling
      back to the slug. (Backend half of plan-jsonnet-removal Phase 2; `lib/entry.pkl`'s
      `title` field is the authoring half.)
- [ ] Area/floor membership in queries: today authors write
      `eo.area_id == dump.floors.overetasje.areas.kjokken.area_id` by hand. Add `inArea(...)` /
      `onFloor(...)` helpers to `lib/components.pkl` (build-time — the dump knows membership; no
      Predicate AST change needed for the static case).

## Worth doing

- [ ] Discover NEW dashboard files at runtime: the watcher re-evaluates known entries but a new
      top-level `.pkl` needs a server restart. Watch the dashboards dir for creates, add a
      renderer for each new slug.
- [x] Disconnected indicator: a visual cue when the live connection stalls. Shipped as a
      server `srvBeat` heartbeat (gated on upstream HA-feed health) + a client
      `data-on-interval` watchdog — so it catches BOTH a browser-side SSE drop and an upstream
      HA WebSocket freeze, not just the SSE (rather than Datastar's connection-lifecycle events,
      whose shape isn't confirmed in the pinned bundle). Structure/behavior are in the server
      shell (`Server.page`, theme-agnostic so it always renders); the look is theme-owned via
      the `.fh-offline` class in each theme's `styles`. Pairs with the self-healing HA feed
      (`HaFeed` + `HAWSApiLowLevel` idle ping/pong + reconnect) that the banner reflects.
- [ ] Registry-change refresh: a renamed entity / new area / new entity never reaches the dump
      (fetched once at startup). Subscribe to the HA registry-updated WS events, re-fetch the
      dump, re-evaluate entries — same machinery as source-file live reload, different trigger.
- [ ] Dynamic case containers: a dynamic group case renders a single card (`childrenHtml = Nil`
      in Renderer.renderCase) — allow a case to render a row/col with children (e.g. a slider
      *and* a label per matched light).
- [ ] Author-facing docs: one authoring guide for the API surface (cards, slots
      literal-vs-transform, JSONata context, dynamic groups, surfaces/tabs, theming) — the ADRs
      record decisions but nothing teaches usage. Include what is static, backend-rendered, or
      client-signal scriptable.
- [ ] CSS/class pass-through on components — ties into the Tailwind `.ha-*` theme plan
      (docs/plan-tw-theme.md): standard class API on templates, `class` slot on containers in
      both languages, and make the backend-emitted `.fh-cell` wrapper class theme-owned.

## Bigger bets (design first)

- [ ] "show if" / conditional visibility: a predicate-gated node (hide a card or subtree when a
      condition is false). Reuses the Predicate AST + the dynamic-group re-render scoping;
      needs an ADR (interaction with pathId stability and the diff cache).
- [ ] Event coalescing under state_changed bursts: debounce/batch, collapsing repeated touches
      of the same node into one render+push (already flagged as FUTURE in Server.scala). Do
      after the shared-fanout refactor — it changes where batching goes.

## Parked (deliberately not doing, with reasons)

- Scala macros for predicate/DSL perf — predicate eval is trivially cheap; identity-slot
  memoization already removed the real cost. Premature.
- Handlebars/jinja2/jinjava instead of Mustache — logic-in-templates is what the JSONata
  transform layer is *for*; swapping template engines buys churn, not capability.
- htmx + hyperscript instead of Datastar — wholesale transport rewrite with no identified
  Datastar limitation. Revisit only if Datastar blocks something concrete.
- xtrasonnet / jsonnet-bundler / validate-libsonnet — the typed/packaged/validated story is
  Pkl's job now (ADR 0006); jsonnet-side tooling would duplicate it.
- Versioning all libsonnet files — Pkl `package://` is the eventual sharing mechanism; version
  there when publishing, not per-file.
- Full-page cache/ETag scheme — LAN dashboard, render is cheap, SSE patches arrive right after
  load anyway. No measured need.
- Worker/api abstraction for in-home vs remote connection — real feature, but blocked on the
  deployment story (home-addon) maturing first.
- Automation-style conditions ("don't set the light to max after 22:00") — belongs in the
  automations track (`home` module), not the view layer.
- "Global system that imports all dashboards" — unclear need; runtime discovery + navigation
  already covers the named use cases.
