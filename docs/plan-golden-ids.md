# Plan: compare goldens with ids normalised

Part of the regions work (`docs/plan-regions.md` on branch `regions`, steps R3 and R4), done
ahead of it. R3 and R4 move node ids (a panel gains a `group` node; the pick mints ids per key and
case), so every golden changes. Before regenerating them, the check is: the new goldens equal
the old ones once ids are numbered by first appearance, so nothing but ids moved.

`RenderGoldenSuite` already does this for transform hashes: `normaliseHashes` renumbers each
`t<hash>` by first appearance (`t#1`, `t#2`, …) and sorts seed entries to match. This plan adds
the same for ids.

**State:** not built.

## Change

- `normaliseIds(text)` beside `normaliseHashes`, in the same style: every node id becomes `n#<k>`,
  `k` counting distinct ids in the order the text first names them, everywhere it occurs, including:
  - `id="…"`, and patch selectors (`#…`);
  - ids inside other names: member ids (`<set>_<entity>`), host ids (`<node>_<slot>`), node-scoped
    signals (`_<node>__<name>`), variable signals and URL params (`_var_<node>__<name>`,
    `v.<node>.<name>`), `data-fh-node`, and the `s_…` namespace of surface ids.

  Read `LayoutNode.pathId`, `LayoutNode.childId`, `Surface.hostId`, `VarKey` and the goldens
  themselves for the exact shapes before writing the patterns; an id the patterns miss shows up as
  a diff later, so test them on every golden.
- A way to run the comparison on demand: a test, skipped unless a system property names a git
  revision, that renders every golden, reads the committed one at that revision (`git show
  <rev>:<path>`), and asserts `normaliseIds(normaliseHashes(new)) ==
  normaliseIds(normaliseHashes(old))` per file, printing the first differing line. Wire the
  property through an sbt command in `build.sbt` that sets and clears it in a `try/finally`, as
  `dashboardSnapshotsUpdate` does (a `-D` on the sbt command line never reaches the tests).

## Tests

- `normaliseIds` on a hand-written fragment holding each id shape above, with an expected result.
- Two renders of one fixture under different id prefixes (if the renderer takes one) or with one
  node added before the others normalise equal for everything after the insertion.
- The on-demand comparison against `HEAD` passes on an unchanged tree.

## Docs

`modules/fh-datastar-view/CLAUDE.md`, the workflow paragraph on goldens: one line naming the
command and when to use it (a change meant to move only ids).

## Done when

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` is green, and the on-demand command run
against `HEAD` reports every golden equal.
