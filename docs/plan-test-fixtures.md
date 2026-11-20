# Plan: test fixtures from Pkl (F)

Stacked on #724 (`root-hosts`), before D0 of `plan-regions.md`. Agreed 2026-11-19.

## Why

Suites hand-build the wire model for shapes the LIBRARY builds — a tab bar, an If, a choice in a
group. Those copies drift from what the library writes: R4c found `{{hostId}}` hosts and `ifhost`
markup the library no longer produced, and most of R4c steps 2–3 went into rewriting them. The
rule that no fixture renders a dashboard the build refuses is only checked when the build
changes, by the suites going red.

## Scope

Migrated: the hand copies of library shapes — `Slots.inGroup/chosen/byVar/tabs/iff`,
`Slots.groupCard`, `FixtureDashboard`'s `ifhost`, `ServerHarness.tabsDash/ifDash/tabsAt`,
`RendererSuite.tabsDashboard/ifDashboard`, and the suites that use them.

Stays hand-built: `TestCard` leaves, `SurfaceGraphSuite` (a graph of fake pieces), `validate`
tests whose input is a refused shape, decoder and wire tests, and the benches (they live in
`benchmarks/`, which keeps whatever builders it needs).

## Steps

- **F0 measure** (scratch, not committed). Per snippet, after warmup, median of 20:
  (a) today's cold `PklFixture.buildDashboard`; (b) one workspace + lib package per JVM, a new
  evaluator per snippet; (c) one shared evaluator, if pkl-core allows it across parallel suites.
  Count the distinct snippets the migrated files need. Prior: `RenderGoldenSuite` does 12 cold
  evals + renders in 2.2 s on CI. **GO** if `testFull` on CI grows by at most 10 s.
  **NO**: a `docs/experiments.md` entry, and the fallback — keep the builders, one pin test per
  shape (the same snippet in Pkl, its leaves fed to the builder, trees equal).
- **F1** `PklFixture.snippet`: a dashboard from a small Pkl body, cached per JVM by source.
  Tests assert on authored `id`s; derived ids may move (set members keep `TestIds`).
- **F2** (lead) migrate `ServerHarness.tabsDash`/`ifDash` and their users as the pattern.
- **F3** (Sonnet, a batch of suites at a time, lead reviews each diff): the rest. Done = suite
  green, no assertion weakened.
- **F4** delete the hand builders nothing uses.

## Findings

(filled in as steps land)
