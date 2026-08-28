# Plan: one entity signal store

Work in flight for [issue #134](https://github.com/perok/functional-home-assistant/issues/134).
Supersedes the "deliberate for now" paragraph in [ADR 0017](adr/0017-signal-slots.md), which this
work rewrites on completion.

## Why

`Renderer.signalName` mints `_<nodeId>__<slotName>`, so a signal is scoped to the node that *shows*
a value rather than to the value itself. One light in three places mints three signals that are
equal by construction and stay equal forever — the frame in #134 carries seven entries for three
distinct values.

#134's body proposes keying by `(entity, transform)` and hashing the pair into a name
(`_c_16__fill` → `_s_a1b2c3`), and then argues itself down: unreadable names, and the node-prefix
invalidation rule in `Patches.applied` stops reaching signals. Both objections are real *for that
variant*.

They mostly dissolve under the variant in #134's second comment — reference the live value in a
**global store**:

| #134's stated cost | Under a store |
|---|---|
| Readability goes | **Dissolved.** No name is minted. The binding names the entity literally; `$_e.light.taklys.brightness` reads better than `_c_16__fill`, not worse. |
| Node-prefix invalidation breaks | **Mostly dissolved** — see below. Validated in step 2, and the plan stops if it does not hold. |
| The node-level seed story changes | **Real, and accepted.** The `data-signals` seed leaves `.fh-cell` for one document-level store. |

On invalidation: `Session.holds` is `Map[NodeId, Held]` with `Held(digest, signals)`, and a host
fill drops entries by node-id prefix. With a document-level store, **a remounted node needs no
re-seed** — its binding reads the store, which already holds the current value. So signals stop
needing node-scoped invalidation at all; they need a per-session last-sent map keyed by store path,
pruned only when an entity leaves the dashboard's static index. That is less machinery than today,
not the second parallel mechanism ADR 0017 was right to avoid.

**This is the plan's load-bearing claim.** Step 2 proves it before anything else is built.

## Not the framework question

#134 is filed next to [#149](https://github.com/perok/functional-home-assistant/issues/149)
("switch to htmx"), and the two are easy to conflate. They are separate:

- Signal names are **our** choice, in `Renderer.signalName`. Nothing about Datastar forces
  node-scoped names, and Datastar already deep-merges nested signals — the `_cursor.*` resume
  fields rely on it. A store is expressible here today with no new client machinery.
- The Datastar Pro license genuinely is unusable for this project ("Making the software available
  in a public repo is a form of redistribution, and is strictly prohibited"), but Datastar **core is
  MIT** and the Pro surface is garnish — `data-persist`, `data-query-string`, `data-view-transition`,
  `@clipboard()`, the Inspector. Nothing in ADR 0017's mechanism depends on it, and `shell.ts:30`
  already hand-rolls the one Pro attribute that was wanted. We are never forced up the tier.

So this work is not a step toward or away from htmx. It happens to be the shape any htmx path would
need — see [`datastar-vs-htmx.md`](datastar-vs-htmx.md) — which makes it cheap insurance, not a bet.

## Scope

**In:** entity-derived display values — the signal slots ADR 0017 created.

**Out:** client-only interaction state. `_<id>__slide` (drag), `_<id>__pending` (optimistic
selection), `_<sig>_slow` (spinner debounce) and `_sse` (transport health) are *interaction*
signals, not entity values: they have no server-side truth to share and no duplication to remove.
They keep their node-scoped names. #133's comment already draws this display/interaction line;
this plan applies it rather than formalising it.

## Steps

1. **Settle the entity-id nesting question.** Entity ids contain dots, so `light.taklys` under a
   nested path parses as two levels (`_e` → `light` → `taklys`). That may be elegant — domain,
   entity, attribute — or may break access with a literal dotted key. Decide against the pinned
   `v1.0.2` bundle with a throwaway page before writing any type; the answer fixes the path format
   everything downstream depends on.

2. **Prove the remount claim.** Before the refactor: confirm that a node re-rendered by a host fill
   picks its value up from a document-level store with no re-seed. A tab switch with a live value
   visible is the case. If this fails, stop and redesign — the whole simplification rests on it.

3. **Name the path as a type.** Add `SignalPath` (or extend `SignalId`) owning the store path for
   an `(entity, transform)` pair and its JSON nesting, following the `PackageRef` precedent in
   `fh.view.build` — one value type owning a string format that would otherwise be re-interpolated
   in several places. Reuse the key `Renderer.identityCache` already uses for non-reactive slots.

4. **Repoint `Renderer.signalName`** to produce a store path. `SignalBind` stays renderer-side —
   ADR 0017's reason holds, a card that wrote its own `data-text` could not have it un-written.

5. **Move the seed** to one `data-signals` on the document, alongside the existing `data-init` on
   `<body>`, instead of per-`.fh-cell` seeds.

6. **Split `Session.holds`.** Digests stay keyed by `NodeId` with the existing prefix-drop rule in
   `Patches.applied`; signals become a per-session `Map[SignalPath, String]` of last-sent values.

7. **Keep the `_` prefix.** The store root stays underscore-prefixed so Datastar's default request
   filter keeps a dashboard's worth of live values out of every action POST and SSE reconnect.
   ADR 0017's reasoning is unchanged and still load-bearing — without it this moves cost from the
   server's frames to the client's requests rather than removing it.

8. **Measure, and answer #134's own question.** The issue says "worth measuring first" and #130
   established that as the house style. Record frame bytes and signal-entry count on a dashboard
   built to have duplication *and* one built to have none. #134's observed frame — 7 entries → 3,
   ~55% — is the baseline to reproduce. A no-duplication dashboard saving ~0% is a finding that
   belongs in the ADR, not a result to bury.

## Files

- `modules/fh-datastar-view/src/main/scala/fh/view/runtime/Renderer.scala` — `signalName`
  (`:1449`), `signalBind`, `identityCache` key reuse
- `.../model/Dashboard.scala` — `SignalBind` (`:190`), if the binding shape changes; it should not
- `.../runtime/Datastar.scala` — `signalsJson` (`:116`), `binding` (`:160`); 183 lines, and the only
  file that speaks the wire protocol
- `.../runtime/Patches.scala` — the `Patch.Signals` diff and `applied`
- `.../runtime/Sessions.scala` — the `Session.holds` split
- `.../runtime/Server.scala` — document-level seed, near the `data-init` on `<body>`

## Docs owed, in the same commits

- **ADR 0017** — rewrite in place. The "one entity shown in N places mints N signals … Deliberate
  for now" paragraph is exactly what this supersedes.
- **`architecture-rendering-pipeline.md`** — the signal-slot box and the "digest stands still while
  the value moves" paragraph.
- **`terminology.md`** — if *store* or *signal path* becomes a project word.
- Grep for comments this falsifies: `_c_`, `signalName`, `holds`, `__ifmissing`, plus the `used to`
  / `no longer` / `now that` tells.
- **Delete this file** when the work lands, in the same PR.

## Verification

- `sbt fh-datastar-view/testFull` — covers Scala **and** the Pkl `facts`, and this touches both.
  Where no browser driver is present, run
  `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` and say so; six red `smoke` suites are
  the environment, not the diff.
- Do not filter `[warn]` out of the output. `-Wunused` is on and `warnError` is excluded, so a
  helper made unreachable by the `holds` split shows up only there.
- `sbt dashboardBuild && sbt dashboardServe`, then load a dashboard showing one light in three
  places and confirm from the frame log that the value rides **once**.
- Exercise a tab switch (surface open → host fill → remount) with a live value visible — the step 2
  claim, re-checked after the refactor rather than only before it.
- The wire-format suites are the ones that will move: `SignalSlotSuite`, `ResumePatchesSuite`,
  `AckedResumeSuite` and `SurfaceTapSuite` under `test/.../runtime/`, plus
  `DatastarMorphContractSuite` under `test/.../smoke/` — that last one is Playwright, so
  `--exclude-tags=Slow` skips exactly the suite most likely to catch a seed regression. It has to be
  run somewhere with a browser driver before this lands.

## Open

- **Store eviction.** "Entity leaves the dashboard's static index" is the proposed prune rule.
  Confirm nothing else needs to drop a path.
- **A home-grown signal inspector.** The Datastar Inspector is Pro, and being locked out of it is a
  standing annoyance. With one store a debug panel becomes cheap — dump one object plus recent
  frames. Follow-up issue, not this PR; worth noting it gets *easier* after this work, not harder.
