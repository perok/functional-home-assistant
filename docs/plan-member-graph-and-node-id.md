# Plan: extract the member graph, then type `NodeId`

Issue [#109](https://github.com/perok/functional-home-assistant/issues/109), items **1** and **2**.
This is a deferred design plan — nothing here is implemented yet.

## Is it still worth doing? Yes, more than when it was filed

`Renderer.scala` was 2389 lines when #109 was written. It is **2708** today. The file grew 13%
while the issue sat, which is the issue's own argument ("it answers *where does this belong* for
the next change") measured a second time. `NodeId` is untouched: still one
`opaque type NodeId <: String` in `model/Ids.scala` covering static ids, member ids, container ids
and surface-prefixed ids, all interchangeable to the compiler.

Items 3 and 4 have moved (smoke suites are out of `testQuick`, the `TestControl` migration landed),
so the issue is live and being worked — 1 and 2 are simply the two that were left.

## The vocabulary question, settled

The concept has three names in the tree. Checked all three; only one is stale.

| name | where | verdict |
|---|---|---|
| `query` | `src/main/resources/dashboards/lib/query.pkl` (700 lines) | **correct, keep** |
| `SetNode` / `SetMember` / `SetClause` | `model/` | **the target vocabulary** |
| `dynamic*` | `runtime/` (87 mentions) | **stale, rename** |

`query.pkl` is not drift. Its header says so itself: *"Its own namespace, not part of
`components.pkl`: this is a query language, and a card knows nothing about it."* It is the
authoring-time **selection** language — `q.from(...).where(...).render(...)` — and it is doing a
different job from the runtime's member graph. Candidates are decided at build time from the typed
dump; registry facts (`area_id == "stue"`) fold away and never reach the wire; only conditions on
live state survive as per-member guards.

So **the rename is Scala-runtime-only.** No authored dashboard changes, no Pkl seam moves, nothing
in `dashboard-local-dev-server/` breaks. An earlier reading of this plan assumed the Pkl seam gated
the rename; it does not.

What makes `dynamic*` worse than ordinary staleness is that it asserts the opposite of what the
code does. "Dynamic" meant *members invented at runtime* — the single property the candidate-set
work (ADR 0003) removed. A reader now carries two names for one thing to follow `Patches` into
`Renderer`.

### Rename map

| now | becomes |
|---|---|
| `dynamicChildId` | `memberIdOf` |
| `dynamicMembers` | `membersOf` |
| `isDynamicContainer` | `isSetContainer` |
| `renderDynamicMembers` | `renderMembers` |
| `renderDynamicChild` | `renderMemberById` |
| `renderDynamic` | `renderSet` |
| `affectedDynamics` | `affectedSets` |
| `affectedSurfaceDynamics` | `affectedSurfaceSets` |
| `recordDynamic` | `recordSet` |
| `DynamicGroupSuite.scala` | folded into the set-vocabulary suites |

Counts to expect the compiler to walk: `Renderer` 41, `Patches` 33, `Dashboard` 7, singles in
`FragmentLog`, `Datastar`, `StateStore`, `Ids`, `Transform`.

`docs/adr/0003-dynamic-groups.md` is still filed under the retired name and is what `query.pkl`
points at. It gets rewritten in place in the same commit — ADRs are current-state documents here.

## Item 1: extract the member graph

### What moves

Currently in `Renderer.scala`, top-level and inside the class:

- `GroupMembers` (`:148`), `MemberGraph` (`:173`) — already top-level and already private, the
  cleanest part of the cut
- `MemberSource` (`:341`), `member` (`:436`), `innerSetId` (`:476`), `memberSources` (`:483`),
  `memberOwner` (`:559`), `graph` (`:577`), `groupOf` (`:603`), `materialise` (`:613`),
  `syncMembers` (`:647`), `applyOne` (`:704`), `insertOrdered` (`:733`), `sortKey` (`:744`),
  `memberAt` (`:753`), `affectedDynamics` (`:770`), `affectedSurfaceDynamics` (`:774`),
  `containersIn` (`:784`)
- from the companion: `precedes` (`:2604`), `compareOn` (`:2615`), `compare` (`:2693`),
  `propertyOf` (`:2647`), `matches` (`:2594`), `matchesIn` (`:2663`)

Roughly 600 lines into `runtime/MemberGraph.scala`, leaving `Renderer` around 2100.

### What deliberately stays

The `render*` half — `renderMember`, `memberChild`, `memberSignals`, `renderSet` — needs
`templates`, `identityCache` and the document walk. Moving it would drag the renderer's guts along
and buy nothing. The seam is: **the graph decides presence and order; the renderer paints.**

`MemberSource` stays a wrapper and does not collapse to `Map[NodeId, SetNode]` — #109 checked this
explicitly. It memoizes two derived indexes per set (`position`, and the `movedBy` reverse index);
collapsing rebuilds the reverse index every frame, which is the exact cost model the candidate-set
work exists to fix.

### Why it pays

Presence and ordering are pure functions of (members, states) but are reachable today only through
a booted `Server` via the harness. That is the functional-core principle in `CLAUDE.md`, and
`Patches` already had the treatment — which is precisely why `Patches.reordered` has direct unit
tests and the ordering logic does not. New `MemberGraphSuite` gets direct tests for `precedes` /
`compareOn` / `insertOrdered` / `materialise` with no server boot.

## Item 2: make `NodeId` carry what KIND of id it is

After the extraction, not before — doing it first means retyping across a 2708-line file.

`NodeId` becomes a sum over the kinds that already exist implicitly, so "a painted group id is a
registered container" is a type error rather than a test somebody has to think to write. The
targets are the real cases #109 lists, all of which have bitten or nearly bitten:

- `Member.entitiesOf` must stop AT a nested set — descending wakes the tile on every bulb inside it
- container selection must read `memberSources`, not the static index — this WAS a bug, and the
  failure mode is the one that makes this worth typing: correct ids, correct markup, **zero
  patches**
- the nested-set id scheme must agree between the end that registers a container and the end that
  paints an element

The prize is the 79 `silent` / `must agree` / `cannot disagree` / `by construction` comments across
`src/main/scala/`. A good handful evaporate; what remains is genuinely about ordering and timing
rather than identity.

`DomId` and `SignalId` stay as they are — they are already separated and already carry their
one-way derivation.

## Order of work

1. Extract `MemberGraph.scala`, folding the `dynamic*` rename into the same diff. The member-graph
   move already touches every one of those symbols and the compiler drives it, so it lands as one
   honest diff instead of a churn commit that makes `git blame` worse for no behavioural reason.
2. Rewrite ADR 0003 in place under the candidate-set vocabulary; update
   `docs/architecture-rendering-pipeline.md` in the same commit.
3. Add `MemberGraphSuite` — direct unit tests for the now-reachable pure logic.
4. Type `NodeId` as a sum; let the compiler walk the call sites.
5. `fh-datastar-view/testFull` green before the PR.

## Not doing

- Renaming anything in `query.pkl` or the authoring seam.
- Collapsing `MemberSource`.
- A wire-size / frame-cost regression guard — explicitly declined on #109, recorded here so it is
  not re-proposed as an oversight.
