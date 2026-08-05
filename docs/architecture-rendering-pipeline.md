# fh-datastar-view — the rendering pipeline

How a Home Assistant state change becomes bytes in a browser: what runs **once per slug** (shared,
whatever the viewer count) and what runs **once per connection** (because only that connection knows
what its viewer has selected).

> **This is a current-state document, and it is the map we plan changes against.**
>
> It describes the pipeline as the code is today — not a proposal, not a history. Keep it true:
>
> - Change the pipeline, change this file **in the same commit**. A diagram that lags the code is
>   worse than no diagram, because it is trusted.
> - An **ADR that alters this pipeline must update this file too** — the ADR owns the *decision and
>   its rationale*, this file owns *the shape of the thing*. They are not alternatives to each other.
>   ADRs [0011](adr/0011-the-live-connection.md) and
>   [0012](adr/0012-each-session-renders-what-it-is-owed.md) are the two that most often will;
>   [0003](adr/0003-dynamic-groups.md) (dynamic groups) and
>   [0007](adr/0007-state-activated-surfaces.md) (state-activated surfaces) own two of the three node
>   kinds below.
> - When proposing work here, say which box moves. "Render outside the critical section" is a
>   statement about §1; "prune at pull time" is a statement about §6.
> - Everything here is current state. Work in flight lives in a `plan-*.md` and moves into this file
>   as it lands; a plan is deleted once its decisions are in the ADRs and its shape is here.

---

## 1. End to end

```mermaid
flowchart TB
  HA["Home Assistant · WebSocket<br/>subscribe_entities"]

  subgraph GLOBAL["GLOBAL — exactly one per process, for ALL dashboards"]
    direction TB
    PUMP["HaFeed.pump<br/>ONE HA WebSocket, one subscribe_entities<br/>one HA frame = one fs2 chunk"]
    STORE["StateStore.update<br/>ONE store for every dashboard<br/>ONE Ref.modify per frame<br/>version++ only if content really moved<br/>each moved entity stamped with it"]
    CH["changes topic · list of StateChange<br/>unbounded — the feed must never backpressure"]
  end

  subgraph SHARED["PER SLUG — one recorder fiber each, however many viewers"]
    direction TB
    SYNC["Renderer.syncMembers<br/>apply the frame to every dynamic group's MEMBER GRAPH<br/>before the gate, and for every group:<br/>the graph tracks the stream, not who is watching"]
    PLAN["Patches.plan<br/>WHAT this frame touches:<br/>staticIds (members included) · dynamics · flips"]
    REC["Patches.record<br/>writes the CHANGELOG and nothing else<br/>NO RENDERING, no digests, no patches<br/>membership from the graph, flips from state"]
    BELL["doorbell · SignallingRef of the version<br/>discrete coalesces: versions landing while a<br/>session renders collapse into one pull"]
  end

  subgraph CLIENT["PER CLIENT — one SSE stream per browser tab"]
    direction TB
    OPEN["openingPatches<br/>resume ▸ repaint ▸ reload<br/>narrowest that is still correct"]
    BEAT["keepAlive · every 25s<br/>a comment, or the CURSOR when position moved<br/>since this stream last said so"]
    PULL["Server.pull<br/>Patches.resume from position + 1<br/>ALL RENDERING HAPPENS HERE<br/>against THIS session's holds + open set"]
    APPL["Patches.applied<br/>forget the mounts it re-supplied,<br/>claim what its bytes placed"]
    MERGE["merge: pulls ▸ control ▸ reloads<br/>▸ haDown ▸ keepAlive"]
    SSE["SSE bytes to the browser<br/>Datastar morphs the DOM"]
  end

  ACT["action POST<br/>surface/open · popup/close<br/>carries conn + ui-state"]
  SESS["Sessions registry<br/>conn maps to slug, open set, control queue,<br/>holds (what this DOM has) + position"]
  LOG[("FragmentLog per slug — the CHANGELOG<br/>node -&gt; version · Gone/Placed · horizon<br/>absence means: unknown, send it")]

  HA --> PUMP --> STORE --> CH --> SYNC --> PLAN --> REC --> BELL
  BELL --> PULL --> APPL --> MERGE --> SSE
  OPEN --> MERGE
  BEAT --> MERGE
  REC -.->|writes| LOG
  PULL <-.->|since position| LOG
  OPEN <-.->|since cursor| LOG
  APPL <-.->|holds| SESS
  ACT --> SESS
  SESS -->|per-connection control queue| MERGE
  ACT -.->|hostFill claims into holds| SESS
  SESS -.->|openSets: which surfaces are worth recording| PLAN

  classDef global fill:#e0f2fe,stroke:#0369a1,color:#0f172a
  classDef shared fill:#dbeafe,stroke:#1d4ed8,color:#0f172a
  classDef client fill:#dcfce7,stroke:#15803d,color:#0f172a
  classDef store fill:#fef3c7,stroke:#b45309,color:#0f172a
  classDef ext fill:#ede9fe,stroke:#6d28d9,color:#0f172a
  class PUMP,STORE,CH global
  class SYNC,PLAN,REC,BELL shared
  class OPEN,PULL,APPL,MERGE,SSE,BEAT client
  class LOG,SESS store
  class HA,ACT ext
```

**Nothing is pushed.** A frame is recorded once per slug; every byte is produced by the session that
will receive it, from the same `Patches.resume` a reconnect runs. A live tick is a resume from
`position + 1` — one mechanism, not two — which is why there is no audience tag on a patch and no
per-client filter at the wire edge: a patch exists only because the session that will send it asked
for it, against its own open set and its own record of its own DOM.

The recorder is **one fiber per slug** (`Server.publisherFor`), re-armed by `switchMap` on every
renderer swap — and a swap past the first mints a fresh log identity, so cursors issued against the
old renderer cannot be resumed.

**Three scopes, not two.** Getting these confused is the easiest mistake to make here:

| Scope | One per | What lives there |
|---|---|---|
| Global | process | the HA WebSocket, `HaFeed`, **the `StateStore`**, the `changes` topic, the `Sessions` registry |
| Per slug | dashboard | the recorder fiber, the `Renderer` (in a `SignallingRef`, hot-swapped) **and the member graph inside it**, the `FragmentLog`, the doorbell, the `RenderCache` |
| Per connection | browser tab | the `Session` — created by the DOCUMENT, adopted by the stream (slug, open surfaces, control queue, plus `holds`/`position` — what THIS client's DOM has and how far it has been served), the SSE stream, that viewer's selections |

There is exactly ONE store and ONE upstream subscription for every dashboard — `HaFeed.resource`
creates the store, `Server.fromFeed` takes `feed.store`. Dashboards are views over one shared state,
never separate feeds.

---

## 2. The setup, in pseudo-code

The same thing as §1, in words, because the diagram cannot show ordering and lifetime.

### Boot — once per process

```
open ONE WebSocket to Home Assistant, subscribe_entities
  the opening frame IS the full entity set, so there is no separate seeding step
create ONE StateStore              // for every dashboard, not one each
evaluate every *.pkl entry         // slug = filename
  per slug: one Renderer in a SignallingRef   // hot-swapped on file edit
  per slug: one FragmentLog with a fresh id, in a Ref, and one doorbell
create ONE Sessions registry
for each slug: start a recorder fiber
  // STARTS IMMEDIATELY, and runs whether or not a browser ever connects
```

### A browser opens a dashboard

```
GET /d/:slug
  render the WHOLE page from the current snapshot
  mint conn; create Session{slug, open surfaces, control queue, holds, position}
  holds = the digest of every node this render painted   // what THIS client's DOM has
  register it, and schedule a reap if no stream adopts it within AdoptionWindow
  embed in the page as Datastar signals: logId, storeVersion, headHash,
    styleHash, conn, and haDown READ FROM `healthy` — not a hardcoded false,
    or a page loaded while HA is unreachable renders as healthy until the
    stream corrects it
  ...and conn again on the data-init URL, which is what the connect reads

GET /sse/dashboard/:slug/patch
  announce `conn` ONLY if this URL named none (a bookmarked SSE endpoint):
    the document seeds it, so echoing it back says nothing
  retire the session `?prev=` names, if any, unless a stream is HOLDING it
    // this tab's previous document, from sessionStorage. A reload mints a
    // fresh conn, so without this its predecessor sits in the registry for a
    // whole linger window holding an old position — and the floor is the
    // LOWEST position, so a few reloads keep the changelog un-prunable.
    // Retiring is all that is wanted: the old holds describes a DOM that no
    // longer exists. The not-Held guard is for a DUPLICATED tab, which
    // inherits sessionStorage and would otherwise evict a live viewer
  adopt the session that URL's conn names (epoch++); mint one under the same id
    if it is gone — a reap, a bookmark, a restart: costs suppression, not correctness
  opening patches, narrowest that is still correct:
      resume   if the cursor's logId matches and nothing structural moved
      repaint  if it does not      // claims what it painted, same as the document
      reload   if the document itself is stale
    ...then position = WHAT THIS CONNECTION CAN PROVE IT SENT: the doorbell's
      value (read BEFORE the log) for a resume, since a resume can only answer
      for versions the changelog describes; the snapshot's version for a
      repaint, which painted all of it
  then stream: pulls ▸ control ▸ reloads ▸ haDown ▸ keepAlive
    // no subscription to acquire, so no window to nest around: the doorbell
    // hands a new watcher its current value, so a frame recorded before this
    // stream existed still wakes it

on disconnect
  release the session into a LINGER (Tenure.Lingering), but only if this stream
    still OWNS it — a displaced stream must not put the live one out to pasture
  it stays registered and recorded for, so a client back inside LingerWindow
    resumes against its own holds instead of paying for a repaint
  after the window, the reaper drops it — but only from exactly the tenure it
    expected, so a reconnect that lands while the reaper sleeps simply wins
```

A session's whole life is one value (`Tenure`), walked in order:

```
Fresh ──(a stream adopts)──▸ Held(1) ──(that stream ends)──▸ Lingering(1)
  │                             ▲                                │
  │                             └────(a reconnect adopts)─────────┤
  └──(AdoptionWindow passes)──▸ Reaped ◂──(LingerWindow passes)───┘
```

Every transition names the tenure it expects to replace, which is what makes
the reaper unable to race a stream: both decide on the same ref, so the loser
sees the winner's answer rather than acting on a stale read. `Held` is also the
only state that counts as a live stream (`Sessions.liveStreams`, the readiness
seam tests wait on) — a lingering session is registered and has nobody to send
to.

### A state change arrives — once, globally

```
StateStore.update(frame)                    // one Ref.modify for the whole frame
  version++ ONLY if some entity's content really moved
  stamp each MOVED entity with that version  // EntityState.contentVersion;
                                             // a deduped re-seed keeps its old one
  publish List[StateChange] on `changes`

every slug's recorder wakes
  read snapshot+version together, and sessions.openSets(slug) + floor(slug)
  syncMembers -> apply the frame to EVERY dynamic group's member graph, and
      report what it did to each: the member lists before and after, plus the
      members whose CASE was replaced in place. BEFORE the gate and for
      every group, not the visible ones: the graph tracks the state stream, so
      a frame nobody records still moves members and the next page render must
      see them. Only a CHANGED entity can have crossed a query or case
      boundary, so a frame costs the number of CHANGES, not the size of the
      house — and a frame that only ticks members walks no member list at all
      (10 µs on a 2 000-entity house, flat in group size)
    NO SESSIONS -> record nothing at all, just mark the gap (log.skipped) and
      stop. A dashboard with no browser on it is the normal state of a home
      instance. Safe only because a document registers its session BEFORE
      reading the snapshot it renders from, which makes any version skipped
      one that document already contains
    visible = surfaces some session can actually SEE  // widens what is considered
  plan    -> staticIds (members included — they are in the reverse index),
             dynamics (which groups to ask about MEMBERSHIP), flips
  record  -> the changelog, and nothing else:
      flips first    -> evict the departed branch, record Gone/Placed
      static         -> node -> version    // members are in here: they are in
                                           // the reverse index like any node
      dynamics       -> the members whose CASE was replaced (no entity edge can
                        name a card binding nothing live), then Gone/Placed per
                        membership move, or a filled mount
                        (the churn heuristic survives as `filled`, which raises
                         the container's horizon — "any cursor below this gets
                         this mount")
  ring the doorbell with the version          // AFTER the log is written, or a
                                              // session could set its position
                                              // past changes it never saw

each connection wakes and PULLS
  resume(log, holds, snapshot, position + 1, open, its own ui-state)
      -> render the candidates; send the ones whose digest is not what it holds
  applied  -> forget the mounts those patches re-supplied, claim what they placed
  position = the version it woke for                // ALWAYS, even when it was
                                                    // owed nothing
  write bytes — but ONLY if there were any: a frame this client was owed
    nothing for is silence on the wire, cursor included. The keepalive carries
    the cursor forward instead, so `position` may run ahead of what the client
    holds by up to one interval. Safe, and bounded to a container refill —
    the argument is on Session.position
```

### The log

```
keyed by node id: the version it last moved at   // no digests: what a CLIENT
                                                 // holds is the session's answer
  a later write overwrites — latest wins, and a version never goes backwards
mutations: Gone / Placed per node, latest wins
pruned by the FLOOR: the lowest `position` any live session holds. A mutation
  below it cannot appear in any resume any session will ever run — exact, where
  the rule it replaced was a one-hour wall clock
  per-container `horizon` records the version at which its history became incomplete
  -> a cursor below a container's horizon gets a refill instead of a delta
     (a CLIENT cursor is not bounded by the floor, which is why pruning raises it)
completeFrom: the whole log going incomplete — see the gap above
absence means "unknown, send it" — so dropping an entry is ALWAYS safe
```

### Reconnect

Same call as a live pull; only the cursor differs.

```
client sends back its stored signals: logId, version, headHash, styleHash
  different logId, version ahead of the store, head moved, or a cursor from
    before a stretch this slug did not record -> full repaint
  otherwise since(version):
      nodes   whose logged version >= cursor   -> rendered NOW, sent if the digest
                                                  is not what this client holds
      ...AND every node in a surface this client has OPEN — nothing may have
              rendered it while nobody was viewing it, so the cursor cannot
              name it and only re-rendering can tell
      moved   Gone/Placed                      -> replayed as remove + insert
      refill  containers whose history aged out -> whole mount
```

A client's cursor gets `>=` where a session's `position` gets `+ 1`, and the difference is who is
claiming: a client can hold version V having seen only part of it, where a position is what this
server itself last sent.

---

## 3. Inside `Patches.record` — the three kinds, and what each writes

Nothing here renders. Everything it needs is state: a flip's selection is `resolveActiveByState`,
and membership arrives already applied — `Renderer.syncMembers` moves the member graph for every
dynamic group before the gate, and hands `record` each group's list before and after plus the
members whose case it replaced.

```mermaid
flowchart TB
  REQ["DiffRequest from Patches.plan"]

  REQ --> FLIP["FLIPS<br/>a state group's selected branch moved"]
  REQ --> STAT["STATIC IDS<br/>ordinary bound components"]
  REQ --> DYN["DYNAMICS<br/>a query-driven group whose MEMBERSHIP may have moved"]

  FLIP --> FLIPW["evict the departed branch's entries,<br/>record Gone / Placed<br/>runs FIRST: its prune must precede<br/>anything suppressed against a pre-flip entry"]

  STAT --> STATW["touched: node -&gt; version<br/>a materialised MEMBER arrives here too"]

  DYN --> REPL["touched, per member whose CASE was REPLACED<br/>— always, whatever membership did.<br/>A member that merely ticked came in as a STATIC ID"]
  REPL --> SAME{"membership<br/>moved?"}
  SAME -->|no| OUT
  SAME -->|yes| SET{"did the member SET move,<br/>or only its order?"}
  SET -->|only the order| OUT
  SET -->|the set| CHURN{"churn a MINORITY?<br/>perEntityChurn"}
  CHURN -->|no, heavy churn| FILL["filled: drop what is under the mount,<br/>raise its horizon past this version<br/>= any cursor below gets the whole mount"]
  CHURN -->|yes| EST{"log.hasChildOf gid<br/>is there a base to patch against?"}
  EST -->|yes, established| DELTA["Gone per departure,<br/>Placed per arrival"]
  EST -->|no, fresh log after swap or fill| FILL

  FLIPW --> OUT["the CHANGELOG.<br/>Each session turns it into patches for itself,<br/>in Patches.resume"]
  STATW --> OUT
  REPL --> OUT
  FILL --> OUT
  DELTA --> OUT

  classDef write fill:#dbeafe,stroke:#1d4ed8,color:#0f172a
  classDef q fill:#fef3c7,stroke:#b45309,color:#0f172a
  class FLIPW,STATW,REPL,FILL,DELTA write
  class SAME,SET,CHURN,EST q
```

**There is no fourth kind.** A node whose markup reads its own viewer's selection used to be one. Its version
moves like any other node's, and the render that reads a viewer's selection happens where the viewer
is. That is the whole of what `Varying`/`Pending`/`Memo` used to buy, for free, and
`nodeVariesByViewer` went with them once `plan` stopped partitioning what `record` merged back.

**The churn heuristic had to survive the loss of the render**, or the wire would move: heavy churn
still fills the mount rather than patching members. It is recorded as `FragmentLog.filled`, which
raises the container's `horizon` — already the mechanism for "no delta describes this, send the
mount" — so `resume` reaches the same patch from the other side. A fill also `touched`es the members
it leaves, because those entries are what keep the group *established* for the next membership
change.

---

## 4. Why the flip records nothing but structure

A flip is server truth — the branch every viewer must move to — but *which* branch a given viewer
has mounted, and what belongs inside it, depends on selections below it. So the recorder does the
part that is identical for everyone (evict the departed branch, record where it went) and each
session fills the mount for its own selection when it pulls. A branch no connected viewer reaches is
never rendered at all.

That is the same mechanism as every other node, not a second path — which is the point: it used to
need a deferred render (`Pending`) and a memo to share one verdict between viewers who agreed,
because the *pass* was shared. Once the render moved to the viewer, both disappeared.

**A branch fill forgets by MOUNT, not by prefix.** A branch's content ids are `s_<surface>__…`,
which no prefix of the container's id reaches, so the patch names the surfaces at that mount
(`Patches.hostEvicts`) as what it made unknown. A dynamic mount's children *are* `gid_…`, so there
the container's id is the right root.

---

## 4b. The member graph — a dynamic group's members ARE nodes

The dashboard's graph has two halves. The **static** half (`Renderer.allIndexed`) is computed once
from the `Dashboard`: every authored node, keyed by its location-derived id. The **dynamic** half
(`MemberGraph`) is a group's members, and it is maintained by the state stream rather than computed.

A member is a real `LayoutNode.Component` — the matched case's card, its slots plus the matched
entity as a literal `entity_id` slot, its cell — stored under the id its `MemberKey` derives. That
literal slot is the whole trick: `renderCase` already set it on every render, so setting it ONCE, at
membership time, is all it takes for a member to stop being special. `renderNodeById` renders it,
`renderInputs` keys it, `elementId` patches it. There is no reverse `childId -> entityId` lookup,
because nothing needs to recover an entity from an id — the node carries its own binding, as every
other node does.

Three properties hold it up, and each fails silently if broken:

- **A member is selected like any other node.** `componentsFor` includes members binding the
  entity, so a member re-renders because something it binds moved — the group's query is asked
  about MEMBERSHIP alone. Two consequences: a case slot naming a second entity ticks (it never
  did, silently), and `rootOf` must resolve a member through its group or a member inside a
  surface reaches clients who do not have it open. The one case the index cannot cover is a
  switch to a card binding nothing live: no edge names it, so `syncMembers` reports the members
  it REPLACED and the recorder touches those by id.
- **Ids are key-derived, never positional.** A positional id renames every node below an arrival,
  which is exactly what a per-member delta exists to avoid. Position is the ORDER (the group's
  member list, which is also what an insert anchor reads); the key is the IDENTITY. `MemberKey` is
  already a sum (`Entity` today, `Surface` for a state group's branch), so "one member is one
  entity" stays a property of the predicate engine rather than of the id scheme.
- **The recorder is the only writer.** A reader derives a group the stream has not reached yet but
  never installs what it derived. Installing would let a page rendering at version 5 — while the
  recorder is still applying the frame that produced 5 — become that frame's "before"; the frame
  would see no membership move, and a client still at 4 would never hear about the arrival.
- **A case switch re-materialises.** The node is state-derived, so a frame moving the matched entity
  across a case boundary REPLACES it. Marking it changed is not enough: the card would render
  happily, from the wrong branch, for as long as the entity stayed a member.

Mutation is in place, in an `AtomicReference` on the renderer, and that is not incidental. Three
things key on renderer IDENTITY — `publisherFor` rotates the changelog on a renderer emission,
`reloadRepaints` repaints every connection on one, and `RenderCache` compares renderers with `eq`.
A membership change that produced a NEW renderer would rotate the log, repaint every browser and
flush the cache on exactly the case the graph exists to make cheap. Mutating in place keeps all
three keyed on the dashboard, for free.

---

## 5. What each scope renders

| | Per slug (the recorder) | Per client (the pull) |
|---|---|---|
| Runs on | one recorder fiber per slug | that connection's own SSE fiber |
| Sees | the full entity snapshot + the union of visible surfaces | one session's open set and ui-state |
| Renders | **nothing** | everything: opening paint, live pulls, resume |
| Writes | the changelog (`node -> version`, mutations, horizon) | that session's `holds` and `position` |
| Cost of N viewers | ×1 | ×1, via the per-slug render cache |

The last row is not structural any more, and that is worth being precise about. Each session renders
for itself; what makes it ×1 is that both pulls go through one `RenderCache` keyed by what the render
READS (`Renderer.renderInputs`), so whoever arrives first renders and the rest wait on the same slot.
Two viewers of one dashboard have the same key for a node unless their selections differ — the hit is
the normal case, not a lucky one. `ServerSuite`'s "rendered once between them" holds the number as a
cost contract: a 2 there means a key is varying per viewer where it should not, or a pull is
rendering outside the cache.

---

## 6. The pull path

The same call serves a live tick and a reconnect. What differs is only where the cursor came from.

```mermaid
flowchart LR
  RC["a pull: the doorbell rang,<br/>or a client reconnected with a cursor"] --> Q{"a CLIENT cursor?<br/>same logId · not ahead of<br/>the store · same head hash"}
  Q -->|no| REPAINT["full body repaint<br/>from the current snapshot<br/>— and it CLAIMS what it painted"]
  Q -->|yes, or a session's own position| SINCE["FragmentLog.since v"]
  SINCE --> N["nodes whose version is at least v<br/>RENDERED NOW from the current<br/>snapshot, never from the log"]
  SINCE --> M["moved: Gone / Placed mutations<br/>replayed as remove + insert"]
  SINCE --> R["refill: containers whose history<br/>no longer reaches the cursor<br/>last resort"]
  Q -->|yes, or a session's own position| OPENN["…AND every node in a surface this<br/>client has OPEN — the cursor cannot name<br/>what nothing rendered while nobody looked"]
  N --> HOLD{"is this what<br/>the client holds?"}
  OPENN --> HOLD
  HOLD -->|yes| DROP["send nothing"]
  HOLD -->|no| SEND["Morph, establishing the digest"]

  classDef ok fill:#dcfce7,stroke:#15803d,color:#0f172a
  classDef bad fill:#fee2e2,stroke:#b91c1c,color:#0f172a
  class N,M,OPENN,SEND,DROP ok
  class REPAINT,R bad
```

The log stores **versions and structure, never HTML** — a pull renders from the current snapshot,
which is by construction at least as fresh as anything the log could have held. That is why a
missing entry is always safe: it reads as "unknown, send it", costing bytes and never staleness.

"Rendered now" goes through the slug's `RenderCache` (`Patches.bytes`), which is what keeps N
sessions woken by one ring of the doorbell from rendering the same node N times. It changes no
answer — the key is what the render reads, so a hit is the render — only who pays for it.

The second question — *does this client already have these bytes?* — is answered by the **session's
own `holds`**, and only ever by that. It is seeded by the document that created the session (and by
a repaint, which paints the same thing), and updated wherever bytes are sent to this client:
`Patches.applied` for a pull, `Patches.hostFill` for a tab or popup swap. Never from what another
client was told.

**Mutations are filtered by visibility too.** A `Gone`/`Placed` inside a surface this client does not
have open would patch an id its DOM lacks — a silent no-op, so it only ever cost bytes, but it is one
client's worth of another client's tab on every frame. That test (`Renderer.visibleNode` on the
container) is where the old audience tag's work now happens.

Mutations are pruned below the floor (`Sessions.floor`, the lowest position among a slug's live
sessions); a container whose history has been pruned past a client's cursor yields a `refill` rather
than a refusal. **Nothing in the log reads a clock** — a version orders everything, and the only
thing wall time still decides is how long a session lingers.

---

## 7. Where each box lives

Paths are under `modules/fh-datastar-view/src/main/scala/fh/view/`.

| Box | Code |
|---|---|
| feed → store | `runtime/HaFeed.scala` · `pump`, `runConnection` |
| store + changes topic | `runtime/StateStore.scala` · `update`, `changes` |
| per-slug recorder | `runtime/Server.scala` · `publisherFor`, `recordFrame`, `sharedPatchPublishers` |
| what a frame touches | `runtime/Patches.scala` · `plan` |
| what a frame writes | `runtime/Patches.scala` · `record`, `recordFlip`, `recordDynamic` |
| the doorbell | `runtime/Server.scala` · `LiveSlug.doorbell` |
| the log (the changelog) | `runtime/FragmentLog.scala` · `touched`, `filled`, `removed`, `placed`, `since` |
| a stretch nobody watched | `runtime/FragmentLog.scala` · `skipped`, `reaches`; `runtime/Server.scala` · `recordFrame`'s gate, `openingPatches`' cursor filter |
| a session's pull | `runtime/Server.scala` · `pull`; `runtime/Patches.scala` · `resume`, `applied`, `encode` |
| what a client holds | `runtime/Sessions.scala` · `Session.holds` / `position` |
| SSE stream | `runtime/Server.scala` · `sseStream` |
| opening paint | `runtime/Server.scala` · `openingPatches` |
| sessions + surface actions | `runtime/Sessions.scala`; `runtime/Server.scala` · `withSession`, `openSurface`, `swapHost`; `runtime/Patches.scala` · `hostFill`, `hostEvicts` |
| a document establishes a session | `runtime/Server.scala` · `pageResponse`, `adoptOrMint`; `runtime/Sessions.scala` · `Session.adopt` |
| a session's lifetime | `runtime/Sessions.scala` · `Tenure`, `Session.release`/`relinquish`/`supersede`; `runtime/Server.scala` · `reapAfter`, `retire`, `AdoptionWindow`, `LingerWindow` |
| a tab handing over its session | `runtime/Server.scala` · `ConnHandoffScript` (`sessionStorage`), `PrevConnParam`, `prevConnOf`, `retire` |
| the actual rendering | `runtime/Renderer.scala` · `renderNodeById`, `renderMount` |
| what keys a render | `runtime/Renderer.scala` · `renderInputs`, `activeBakeIndex` |
| the member graph | `runtime/Renderer.scala` · `Member`, `MemberGraph`, `syncMembers`, `membersOf` |
| the render cache | `runtime/RenderCache.scala`; entered from `Patches.bytes` (morphs, placements). A composed surface mount is NOT cached — its bytes carry its children, so it has no sound key |
| what a cache entry is keyed by | node id -> (renderer identity, `RenderInputs`) — one generation. The renderer is in the key because a dashboard edit changes the MARKUP while the entity versions it reads stay put |

## 8. Known open questions

Live list — delete an entry when it is answered, and say where the answer landed.

- **A laggard evicts the current.** One generation per node means two sessions pulling with different
  keys for the same node replace each other's entry rather than sharing the map. Costs renders, never
  wrong bytes (the key is compared, not assumed). Unmeasured; the fix if it bites is a small FIXED
  number of generations per node, not a return to unbounded keys.
- **An entity that VANISHES leaves a ghost member.** A removal produces no `StateChange`, so a
  delta-maintained graph never hears about it and keeps a member whose element is in no DOM — and
  offers its id to `insertInto` as an anchor. The answer is to drop the `LiveSlug` outright rather
  than to teach the delta path about it (a removal already forces a registry watch → renderer swap
  → fresh log); the gap is that an `r` frame does not always have a registry event behind it. See
  ADR 0003's open section.
- **Ordering across sessions is assumed, not stated.** Sessions render on their own fibers and can
  sit at different positions. Nothing in the design depends on them agreeing — each pull is computed
  against the current snapshot from that session's own cursor — but that is an invariant worth
  writing down and testing rather than relying on.
- **Carrying the converted attribute map across a tick.** See TODO2.md — `EntityState.javaAttributes`
  is rebuilt per state change even when attributes did not move.

---

## 9. Two findings worth keeping at hand

The reshaping this file describes — the recorder, the doorbell, the per-session pull, the session
linger, the maintained member graph — **has landed**; its decisions live in ADRs 0011, 0012 and
0003, and the route it took is in the git history. Nothing in §1–§8 describes code that does not
exist.

Two findings from the cache work, which sit between the ADRs and so are easy to lose:

- An `if`/`else` host's branch is a quantified predicate over the WHOLE entity map, so it is keyed
  on the RESOLVED selection rather than on what the selection reads.
- The cache holds ONE generation per node, which is what bounds it: `plan` selects the nodes whose
  entity just moved, so a `(nodeId, inputs)` key would grow forever at a near-zero hit rate.
