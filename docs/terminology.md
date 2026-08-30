# Terminology

Words this project uses in a specific way. Plain-English definitions, so an ADR or a code comment
that says "the flip raises the container's horizon" is readable without archaeology.

Home Assistant's own vocabulary (entity, domain, service, area, device) is not repeated here — only
words we coined or bent.

---

## Authoring — what a dashboard is written out of

**Card** — a kind of component, named by a string (`"slider"`, `"entityCard"`). In Pkl it is a
class; the backend knows it as a template plus a list of slot names it expects.

**Node** — one placed instance of a card in a dashboard's layout tree. Nodes nest.

**Slot** — one named value a card's markup needs, filled per node. Either a **literal** (a constant
string baked at build time) or a **transform** (a CEL expression evaluated against live entity
state). "Slot" is the value; the template hole it fills has the same name.

**Signal slot** — a slot whose value is pushed to the browser as a Datastar signal instead of being
baked into re-rendered HTML, so it can change without the card being re-rendered at all. ADR 0017.

**Display signal** vs **interaction signal** — which of the two a signal is decides how it is NAMED,
so the distinction is load-bearing rather than descriptive. A *display* signal carries an entity's
value and is named by what it READS (`_e.<domain>.<object_id>.<transform>`), so every card showing
that value shares one signal and one frame entry. An *interaction* signal is client state with no
server truth to share — a drag position, an optimistic selection, a busy indicator — and stays
scoped to the node that owns the control (`_<nodeId>__<slotName>`). Sharing an interaction signal
would let one card's gesture drive another card's readout. ADR 0017, ADR 0025.

**Subject entity** — the entity a card is "about", carried as the magic `entity_id` slot. Other
slots on the same node read it unless they name an entity of their own.

**Cell** — the wrapper element the renderer puts around every node, carrying the layout classes
(`fh-cols-3`, `fh-hug`). Layout is the backend's job, not each card's. ADR 0008.

**Surface** — a chunk of layout that is shown only sometimes and rendered only while shown: a popup,
a tab panel, an `if`/`else` branch. Registered separately from the main tree and addressed by id.

Two kinds, and they are not interchangeable:

- **Owned** — a tab panel, an `If` branch. It is part of one node's definition, only that node
  refers to it, and it has no meaning apart from it. Authored inline at that node.
- **Triggered** — a popup. It is a dashboard-level thing that any number of taps may open, and its
  lifetime should not depend on which of them exists.

**The registry** (`surfaces { ["detail"] { … } }`) is for the TRIGGERED kind, and that is the whole
of what it is for. Registering something owned — a tab's panel by hand — throws away the ownership
that makes it addressable, orderable and lazily baked as part of its node. What earns a registry
entry is being referred to by NAME from more than one place, or from somewhere that is not its
parent; nothing else does.

An **inline** surface (`openPopupInline`) is a locality convenience, not a third kind: the body is
written where the tap is and the build lifts it into the registry. It cannot be shared — its id is
derived from the owning node, and the `@@NODE_ID@@` token resolves only to a node's OWN id, so no
second tap can name it. Sharing means registering it and using `openPopup("detail")`.

**Tap / tap action** — what a click does, as a value rather than a string: a service call, a
navigation, opening a popup. ADR 0016, 0024.

**Guard** — the attribute on a tappable element that refuses a second click while the first is still
in flight. The point of the busy machinery; the spinner is decoration. ADR 0019.

**Busy** — per CONTROL: a request is in flight *from this element*. What the guard, the spinner and
the disabled state read. About whether you may click, not about what is shown.

**Pending / committed** — per SELECTION GROUP, and a different fact from busy: **pending** is the
value this client has ASKED for, **committed** is the value the server says is in effect. A control
shows the pending one while there is one and the committed one otherwise, so an optimistic update
never has to be rolled back — nothing wrong was ever committed. Pending is client-written only, and
clears itself three ways: the committed value agrees, the stream is down (so no answer is coming),
or the server refused. ADR 0025.

**Candidate set** — a card position that stands for "whichever entities currently match", with the
possible entities known at build time from the dump. ADR 0003.

**Clause** — inside a candidate set, one `when` branch carrying a complete node. A matched entity
gets the first clause whose condition holds.

**Member** — one materialised entity inside a candidate set, or one row inside a grouped slider.
Members are addressable nodes in their own right.

**Presence / order** — the two things about a set's membership that can move: whether an entity is in
it, and where it sits. Tracked separately because reordering re-sends nothing.

**Dump** — the generated Pkl file describing the live Home Assistant instance (every area, entity and
its attributes) as typed values, so `dump.entities.light_kitchen` dot-completes in an editor. Built
from a live fetch, never committed. ADR 0013.

**Entry** — the top-level Pkl module for one dashboard. `site.pkl` names every entry the instance
serves; an entry's key in it is that dashboard's **slug** (its URL segment). ADR 0021.

**Hoist** — the build step that lifts a popup defined inline inside a tap up into the top-level
surface registry, splicing the owning node's real id into it. Why authors can write a popup where it
is used rather than registering it elsewhere.

**Token (`@@NODE_ID@@`, `@@CLASSBIND:…@@`)** — a placeholder Pkl writes because the value is not
knowable while authoring, filled in later by a pass that is. `NODE_ID` stands for *the node that
owns this subtree*: a card constructing its own children writes it so they can name signals the card
owns, and the build replaces it with that node's real id — bottom-up, so the innermost owner wins.
Note this is authorship, not tree position: it means "the card that wrote this", which is why the
renderer cannot derive it from the parent link.

---

## Card structure — what a card's markup holds

**Template** — the whole card's markup, holes included. There is one per card.

**Region** — a named hole in a template that something else fills. A card declares them
(`CardDef.regions`), a node fills them (`Component.regions`, keyed by region name). `fill = "eager"`
splices the children it was given; `fill = "baked"` is filled by a surface instead (see **bake**).

Every region is named on the wire, the default one included. `children` is the AUTHORING word —
`Grid { children { … } }` is still what a one-hole card is written as, and Pkl resolves the name
before it emits. Two words for two sides of one thing, and the sugar stops at the boundary.

**Leaf card** — a card with no regions. Its template *is* what a live patch renders, so a leaf is
the only kind of node a patch ever targets.

**Structural card** — a card with regions. Its element contains what it holds, so a patch aimed at
it would carry that content back with it — which is why **structure is never a patch target**. A
structural card therefore has only what it holds to show: a live slot on one is a build error
*unless the value travels as a signal*, which never becomes bytes in the element. ADR 0012, ADR
0017.

A card wanting its OWN markup to move puts that markup in a region, as a node — a slider's head is
a leaf card beside the rows for exactly this reason. So the guarantee needs no rule to police it:
every hole in a template is filled by a node with an id of its own, and a patch at one node can
never reach another's content.

**Host** — the element a baked region is filled through, addressed as `hostId` (`c_2_panel`). The
word the runtime uses; not to be confused with a **region**, which is the declaration, or with the
**node** that owns it.

**Bake** — to render chosen content into a host. A surface declares which node it bakes **into**;
the host renders it as **bakeAs**; **bakeIndex** is which member of the group is currently chosen,
exposed so a tab bar can show the selection without JavaScript.

**Bake group** — the set of surfaces competing for one host. Exactly one is baked at a time.

**Flip** — a state-activated bake group changing which branch is selected because *entity state*
moved, not because a user clicked. Server truth, so every viewer gets it. ADR 0007.

---

## Runtime — how a change reaches a browser

**Node id** — a node's address, derived from its position in the tree (`c_2_0`). Not authored. ADR
0022.

**Fragment** — one node's own HTML, as opposed to the composed HTML a parent embeds. What gets sent.

**Patch** — one instruction to the browser: morph these bytes over that element, remove it, insert
before it.

**Morph** — Datastar replacing an element's content in place, keeping focus, scroll and any DOM
state rather than swapping the node out.

**Recorder / publisher** — the one fiber per dashboard that watches entity state and writes down
what moved. It renders nothing and sends nothing.

**Session** — one browser tab's server-side record: what it holds, how far it has read, which
surfaces it has open.

**The log / changelog** — per dashboard, `node id -> the version it last moved at`, plus structural
mutations. It holds **no HTML** — what a client actually has is that session's business, not a
shared one.

**Touched** — a node the recorder marked as moved at this version.

**Mutation (Gone / Placed)** — a structural change in the log: a node left the DOM, or arrived.
Replayed to a reconnecting client as a remove and an insert.

**Holds** — one session's map of `node id -> digest of the bytes that tab currently has`. The answer
to "does this client already have this?", asked per session and nowhere else.

**Digest** — a hash of a node's own HTML, so "unchanged" is cheap to decide without keeping the
bytes.

**Claims / invalidates** — what a patch reports about itself: the nodes whose bytes it just wrote
(so the session can record them) and the nodes it displaced (so their records stop describing
anything). A patch that writes a whole subtree, like a tab switch, uses both.

**Position / cursor** — how far a reader has got through the log. A session's `position` is what the
server last sent it; a **cursor** is what a reconnecting client claims to have.

**Doorbell** — the single wake-up signal per dashboard. The recorder rings it once; every connected
session wakes and pulls for itself. Coalescing is free — two rings before anyone wakes are one wake.

**Pull** — a session computing its own updates from its own position. A live tick and a reconnect
are the same call with a different cursor.

**Resume** — the reconnect path: given a cursor, work out what this client missed.

**Repaint** — giving up on deltas and re-sending the whole page. What happens when a cursor is too
old or the dashboard itself changed.

**Floor** — the lowest position any live session still holds. The log can be pruned below it,
because nothing below it can appear in any resume anyone will ever run.

**Horizon** — per container, the version at which its history stopped being complete (everything
churned at once, so recording each change was pointless). A cursor below a container's horizon gets
the whole container back — a **refill** — instead of a list of changes.

**Linger** — the grace period a session stays alive after its stream drops, so a reconnecting tab
resumes instead of starting over.

**Render cache** — per dashboard, so N viewers woken by one doorbell render each node once between
them. Keyed by what the render *read*, not by node id alone.

**Render inputs** — that key: the content version of every entity the node reads, plus which member
of its bake group is selected. A node whose bytes carry its children has no sound key and is simply
not cached.

**Generation** — one version of a node's bytes in the cache. Kept per selection, so viewers on
different tabs stop evicting each other.

**Straggler** — a session that renders from an older snapshot than its peers. It is served its own
render, but it may not overwrite newer bytes in the cache.

**Traced** — a render that also hands back every node's own bytes from inside it, so a caller that
rendered a whole subtree can record what it placed without walking it again.

---

## Build and packaging

**Package** — the authoring library and the dump, each published into Pkl's package cache under a
content hash, so a dashboard names an exact version. ADR 0010.

**Pin** — the recorded version of each package a workspace resolves against.

**Workspace** — a laptop checkout the `fh` CLI sets up, holding the dashboards and pinned packages
but no server.

**Snapshot (wire)** — a byte-for-byte recorded copy of a dashboard's evaluated JSON, so a refactor
that should change nothing can prove it. Distinct from the **visual snapshots**, which are PNG
baselines behind their own gate.
