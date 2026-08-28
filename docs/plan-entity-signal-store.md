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

## Two mechanisms that are easy to conflate

**The digest governs sending; the cache governs re-rendering.** They are different questions and
this plan touches both, so keep them apart:

- `Session.holds` digests are per-viewer and answer *"is this worth putting on the wire?"* A signal
  slot's value is not in patch-form bytes, so the digest does not move and `Patches.morph`
  suppresses the element patch. **That win is already banked** — it is ADR 0017 working as designed.
- `RenderCache` is per-slug and answers *"do I have to run mustache again?"* Nothing about the
  digest stops a wasted render upstream of it. That is what step 8 addresses, and it is a CPU win,
  not a bytes win.

**Hard-inserting values for a JS-less browser is also already done.** ADR 0017's DOCUMENT form has
the value inline, plus the binding, plus the seed; the PATCH form has the binding alone. The ADR's
own reason is the one to preserve: "A JS-less browser gets the value. It receives the document and
nothing else — no Datastar, no SSE, no patches ever." Nothing in this plan may take that away.

**A JS-less browser and a morph-only client are not the same thing**, and ADR 0017 records
conflating them as its first mistake. No-JS takes *no* patches, so there is no morph to fall back
to for it — its inline document bytes are the whole story. A morph-only client (#133) does take
`datastar-patch-elements` but has no expression evaluator, and it is the one a morph fallback would
serve. #133 also warns the fallback is not always available: a display signal on a *structural*
card has no valid patch target, so the fallback would have to climb to some patchable ancestor —
"worse than a coarse morph; it is an unbounded one." `sliderHead` is already that case.

## Scope

**In:** entity-derived display values — the signal slots ADR 0017 created.

**Out:** client-only interaction state. `_<id>__slide` (drag), `_<id>__pending` (optimistic
selection), `_<sig>_slow` (spinner debounce) and `_sse` (transport health) are *interaction*
signals, not entity values: they have no server-side truth to share and no duplication to remove.
They keep their node-scoped names. #133's comment already draws this display/interaction line;
this plan applies it rather than formalising it.

## The stack

Each lands as its own PR, stacked, in this order. Only the second is #134 proper.

| # | branch | what | depends on |
|---|---|---|---|
| 1 | `cache-key-signal-slots` | Narrow the render-cache key (step 8 below) | nothing — lands first |
| 2 | `entity-signal-store-impl` | The store: steps 1–7 and 9 below | 1, only to keep measurement clean |
| 3 | — | Document-form caching (#130 + a profile-dependent key) | **2**, see Open |
| 4 | — | Spike htmx + Alpine, throwaway | 2 |

Step 8 is written last below because it is the least entangled, not because it lands last. It
touches `renderInputs` and so does 3, which is the one real conflict in the stack — 3 rebases onto
whatever 1 leaves behind.

## Steps

1. ~~**Settle the entity-id nesting question.**~~ **Answered** — read off the pinned `v1.0.2`
   bundle rather than spiked in a browser, since the parsing is all in the source.

   **Nesting works, and dots are always path separators.** A `$name` reference is matched by
   `\$([a-zA-Z_\d]\w*(?:[.-]\w+)*)` and rewritten by
   `f.split(".").reduce((m,h) => \`${m}['${h}']\`, "$")`. So `$_e.light.taklys.<key>` becomes
   `$['_e']['light']['taklys']['<key>']`. Segments are bracket-indexed, so they need not be JS
   identifiers — but the *regex* requires `\w+`, and HA slugifies both domain and object id to
   `[a-z0-9_]`, so every entity id fits with no escaping. There is **no bracket syntax in an
   expression**: `$_e['light.taklys']` cannot be written, because the match stops at `$_e`. A
   literal dotted key is therefore unreadable by construction, which settles the question — nest.

   **The payload must be genuinely nested JSON, not flat dotted keys.** The handler is
   `apply(…){ k(ce(t), {ifMissing:r}) }` — `k` is `mergePatch`, *not* `mergePaths`. `mergePatch`
   recurses only where the VALUE is a plain object, so a flat `"_e.light.taklys.x"` key would be
   set as one literal key containing dots and never match the nested read. (`mergePaths`, which
   does split dotted keys, is not what the SSE event uses.) `Datastar.signalsJson` and
   `signalsAttr` therefore have to build nested structures — today both emit a flat object.

   **Deep merge is confirmed**, which is what makes a store viable: `Nt` walks into nested objects
   and assigns only at leaves, so patching one entity leaves its siblings untouched. `ifMissing`
   (the `__ifmissing` modifier) applies at every depth. Note `null` deletes a key at any depth —
   the hazard `signalsJson` already guards at the top level now applies throughout.

   **New fork this surfaced — the last segment.** The sharing key is `(entity, transform)`, and a
   JSONata transform is not `\w+`, so it cannot be a path segment. Using the SLOT NAME instead
   would defeat the deduplication outright (two cards naming one transform differently would stop
   sharing, and one name over two transforms would collide), so it is not a real option. The
   segment must be a function of the transform alone. Proposed: a readable slug for the two common
   shapes — `$state` → `state`, `$attr.<word>` → `attr_<word>` — and a `t<hash>` fallback for
   computed expressions like the slider's `percentExpr`. Disjoint prefixes keep it injective. This
   keeps the ENTITY readable in a frame log, which is the half of ADR 0017's readability objection
   that actually mattered.

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

8. **Narrow the render-cache key** — separable from the store, and worth landing on its own.

   `RenderCache` holds **patch-form** bytes (`Patches.renderNodeById` → `NodeBytes.of`), and patch
   form carries no signal value. But its key is built from `liveEntities`
   (`Renderer.renderInputs`, `:808` and `:813`), which *includes* entities read only through signal
   slots. So a brightness tick invalidates a generation whose re-render is byte-identical: the
   mustache render runs, the digest then says nothing moved, and nothing is sent. `RenderInputs`
   names this case itself (`Renderer.scala:60-62`) — "a key that is TOO DISCRIMINATING costs a
   wasted render … CPU, no bug."

   The narrower list already exists: **`Dashboard.liveEntitiesAsBytes`** (`:554`) is `liveEntities`
   minus signal-only reads, documented as "the entities whose movement can reach this node's DOM
   only by re-rendering it." That is the cache key's semantics exactly. Key on
   `subjectEntity ++ liveEntitiesAsBytes`, preserving the subject rule the current comment gives.

   **The reverse index must keep `liveEntities`.** The same doc comment says why — "a signal still
   has to make its node a candidate, or no frame is ever computed for it." Swapping the two lists
   the wrong way round silently stops signal frames rather than failing. Today
   `liveEntitiesAsBytes` has exactly one caller, the structure rule at `Dashboard.scala:1120`.

   Safe **because** the document form bypasses the cache today (`renderPageTraced` takes no cache;
   issue #130). If #130 lands and documents enter the cache, this step's assumption changes — see
   below.

9. **Measure, and answer #134's own question.** The issue says "worth measuring first" and #130
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

- **A profile-dependent cache key, if and when #130 lands.** Step 8 is safe only while document
  renders bypass the cache. Put documents *in* the cache and the key must distinguish viewers,
  because document-form bytes carry the value and a stale entry would be served to a client that
  cannot fix it. #133 already names the mechanism: "the mode has to reach the renderer, and
  therefore `RenderInputs.vars`, so the two viewer kinds do not share a `RenderCache` generation for
  a signal-carrying node." The cost is a cached generation per profile for signal-carrying nodes.

  Note the ordering dependency, which runs the *opposite* way to intuition: it is tempting to say a
  signals client tolerates stale document bytes because it fixes the value from the store, but
  **today it does not** — the document form's `data-signals` seed carries the value too, and ADR
  0017 makes a first paint correct with no following frame, so a stale seed stays stale until that
  entity next changes. Step 5 of this plan is what changes that: moving the seed to one
  document-level attribute generated outside the cache leaves the cached per-node bytes carrying
  only the inline text, which matters only to no-JS and morph-only viewers. **The store is the
  prerequisite that makes document caching safe**, not a consequence of it.
- **Store eviction.** "Entity leaves the dashboard's static index" is the proposed prune rule.
  Confirm nothing else needs to drop a path.
- **A home-grown signal inspector.** The Datastar Inspector is Pro, and being locked out of it is a
  standing annoyance. With one store a debug panel becomes cheap — dump one object plus recent
  frames. Follow-up issue, not this PR; worth noting it gets *easier* after this work, not harder.
