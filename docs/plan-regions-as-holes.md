# Plan: a region is a raw hole, `{{{name}}}`

Agreed in chat on 2026-10-28. Stacked on #654 (`card-slots-from-template`).

**State:** built; review, then delete this file.

## Why

A card places a region as `{{#children}}{{{html}}}{{/children}}`: a section looping over the
children, each pushing a scope that answers one name, `html`. Since #654 a region's section must be
exactly that, so the loop carries no information the name does not, and it costs a mechanism of its
own:

- `html` is a magic name, so validation has to tell a region's `{{{html}}}` from a stray one, and
  check each region section's body.
- `FhVisitor.iterable` prints every section's body to find the exact ones and wraps them in
  `FhRegionCode`; `Templates.inlineRegions` and `NodePlan.inline` carry that answer to the walk.
- The walk keeps a second path for a region that is not inline: `childrenHtml` and `bakedHtml`
  render each child to a string, and `NodeContext.fhGet` hands them over as a list of one-entry
  maps. Since #654 nothing reaches it, because every region is inline, yet every structural node
  still builds both maps per render.

The page chrome already places its body as a raw hole, `{{{body}}}`, which `FhValueCode` streams
into the writer through `FhScope.writerHoles`. A region can be the same thing.

## The design

**A region is `{{{name}}}`, placed exactly once, outside every section.** Eager and baked alike.

- **Render:** `NodeContext.writerHoles` is the walk map that `regionWalk` is today, so `FhValueCode`
  streams the children in at the hole. Removed: `FhRegionCode`, `FhVisitor.iterable`,
  `FhScope.regionWalk`, `Templates.inlineRegions`, `NodePlan.inline`, the `childrenHtml` and
  `bakedHtml` paths, and the region branch of `NodeContext.fhGet`.
- **Read:** `Templates.Reads` records each region's placements: raw, escaped or as a section, and
  whether inside a section. `regionBodies` and `strayHtml` go, and `html` is an ordinary name.
- **Validate, per card:**
  - each declared region appears exactly once, as `{{{name}}}`, outside every section. Twice
    would render its children twice under the same ids; inside a section, the server would patch
    ids the page may not have.
  - `{{name}}`, `{{#name}}` or `{{^name}}` for a region is an error naming the fix
    (`write {{{children}}}`). An old template fails with that message, not silently.
- **Validate, per node:** a slot may not share a region's name, since the region would shadow it.
- `CardDef.holeOf(name)` becomes `{{{name}}}`.

Not built: a wrapper shown only when a region has children (`{{#r}}<div>{{{r}}}</div>{{/r}}`). Add
it when a card needs it, by answering a region's name as a boolean in `fhGet`.

## Migration

- Library: the 20 region sections in `layout.pkl`, `core/surface.pkl`, `base/slider.pkl`,
  `base/surface.pkl`, `base/tile.pkl`, `select.pkl`; `core/node.pkl`'s `regions` doc.
- Test fixtures and benchmarks: the same rewrite, mechanically (`{{#r}}{{{html}}}{{/r}}` to
  `{{{r}}}`), about 30 files. Wire snapshots regenerate; only template strings may change.
- A workspace's own cards: the build names each old hole and its replacement. The HA box's
  instance workspace needs the rewrite before release.

## Tests replaced

These assert the old spelling's rules and are replaced, not weakened:

- `TemplateReadsSuite`: "a region's section is not descended" and "html outside a declared region
  is stray" become placement tests (raw, escaped, section, inside a section, twice).
- `BuildPhaseSuite`: the region-body-shape and stray-`{{{html}}}` cases become an old-spelling
  case, a region inside a section, a region placed twice, and a slot named like a region.
- `TemplatesBehaviourSuite`: the three region-loop cases go with the code they cover; one case pins
  that a region hole streams its children into the writer, one that it renders nothing unfilled.
- `RendererSuite` "a declared region whose template places no hole": the expected hole is
  `{{{kids}}}`, and its wrong-hole case is now the section spelling.
- `components.test.pkl`: the tile fact looks for `{{{features}}}`.

## Perf

The walk's streaming is unchanged, and each structural node stops building two maps per render.
`RenderBench`, `-f 2 -wi 4 -i 5 -prof gc`, #654's head against this branch, same machine;
allocation is the steady number, times move ±10% run to run:

| bench | B/op before | B/op after |
|---|---|---|
| page | 1 246 477 | 1 236 460 |
| pageSignals | 1 442 755 | 1 421 204 |
| pageWalkStream | 999 000 | 990 226 |
| pageServe | 1 553 296 | 1 541 081 |
| resumeMembers | 191 501 | 189 298 |
| tickRender | 68 592 | 68 512 |
| mustache | 392 001 | 392 001 |
| wireCommon | 56 612 | 57 332 ± 1 147 |

The first cut joined the baked and eager hole maps with `++`, which rebuilt the map on every
structural node and put `wireCommon` 1.3 kB above its error bar; the baked hole is now added only
when there is one. `page`, `resumeMembers` and `wireCommon` are measured after that fix; the other
rows are from the first cut, so they are upper bounds.

## Docs

- `docs/terminology.md` (**Region** already says "a named hole"; add the spelling),
  `writing-components.md`, module `CLAUDE.md` (phase discipline), `core/node.pkl`.
- `docs/architecture-rendering-pipeline.md`: "a region is a `Writer => Unit` in `regionWalk`"
  becomes `writerHoles`.
- No ADR owns the spelling; none is added.

## Checks

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, CI-mode compile of tests and
benchmarks, scalafix, scalafmt, snapshot diff read, RenderBench as above.
