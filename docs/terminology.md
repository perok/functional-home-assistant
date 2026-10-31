# Terminology

Words this project uses in a specific way. Plain-English definitions, so an ADR or a code comment
that says "the flip raises the container's horizon" is readable without archaeology.

Home Assistant's own vocabulary (entity, domain, service, area, device) is not repeated here — only
words we coined or bent.

---

## Authoring — what a dashboard is written out of

**Card** — a kind of component, named by a string (`"slider"`, `"tile"`). In Pkl it is a
class; the backend knows it as a template plus a list of slot names it expects.

**Node** — one placed instance of a card in a dashboard's layout tree. Nodes nest.

**Slot** — one named value a card's markup needs, filled per node. Either a **literal** (a constant
string baked at build time) or a **transform** evaluated against live entity state — a CEL
expression (the engine tier), or an opted-in **simple** structure (`simpleMod.attr(...)`, the fast
tier: the closed set of atomic reads, each defined by its idiomatic CEL spelling and pinned
byte-equal to it). The tier is the slot's own field; nothing infers one from expression spelling.
"Slot" is the value; the template hole it fills has the same name. In Pkl a slot is a
`slotMod.Slot`: its name, its value (a literal or a **Reading**) and how the card binds it (`signal`,
`type`). A card names each one once, as a handle (`const local label = slotMod.slot("label")`), and
places it through the handle (`\(label)`, `label.bind`, `label.ifSet(…)`). That is a **template
slot**; a **cell slot** is placed by the renderer on the node's **cell** instead, keyed by the class
or the CSS custom property it sets (`cell.classes`, `cell.styles`; ADR 0008).

**Signal slot** — a slot whose value is pushed to the browser as a Datastar signal instead of being
baked into re-rendered HTML, so it can change without the card being re-rendered at all. ADR 0017.

**View** — a way a template reaches a slot besides its value, spelled `x.view`: `bind` (a signal
slot's binding), `has` (whether the node sets it), `jsValue` (the value as JavaScript), `signal`
(the signal's name). ADR 0017.

**Display signal** vs **interaction signal** — which of the two a signal is decides how it is NAMED,
so the distinction is load-bearing rather than descriptive. A *display* signal carries an entity's
value and is named by what it READS (`_e.<domain>.<object_id>.<transform>`), so every card showing
that value shares one signal and one frame entry. An *interaction* signal is client state with no
server truth to share — a drag position, an optimistic selection, a busy indicator — and stays
scoped to the node that owns the control (`_<nodeId>__<slotName>`). Sharing an interaction signal
would let one card's gesture drive another card's readout. ADR 0017, ADR 0025.

**Subject entity** — the entity a card is "about", carried as the magic `entity_id` slot. Other
slots on the same node read it unless they name an entity of their own. A base component has none
unless the HA layer gives it one (`subject`).

**Its button** — a button that names no target: the card holding it fills it in from its
**subject entity**, and shows nothing where that entity cannot do it. Written through `c.its`
(`c.its.toggle`), against a **building block** that names its target (`c.tap.toggle(l)`).
ADR 0015.

**Axis** — one value of an entity a slider can drive (`hass.Axis`): where it is read and written,
its range, and when the entity is on it (`inUseWhen`). A light's are its `brightness`, `colourTemp`
and `colour` groups (`l.axes`); each is also a main control, named by it. ADR 0013.

**Main control** — what a light's slider shows, one at a time: brightness, colour temperature or the
hue. HA's own name (`more-info-light.ts`), switched by `c.its.mainControl` through the node variable
`control`. Not HA's colour MODES, the formats a light accepts (`hs`, `color_temp`), which the main
controls are derived from.

**Reading** — a slot's value used as an INPUT (`slotMod.Reading`): a live read (`entityId` + transform) with no binding yet,
which the card it is handed to binds where it belongs. It reads its entity's state (a fast read may
name another), or the node's own expression values (`c.expr("on > 2")`), or both. `c.isOn(l)` and `c.stateIn(l, states)` are
readings. The card says WHERE a value lands, and the reading says WHAT it reads. Hand a base
component a reading that names its entity, since a base card has no subject to fall back on.

**Condition** — the yes/no thing `c.iff` takes (`pred.Predicate`: `q.from(xs).where(…).any()`,
`q.entity(e).stateIs("on")`). `c.iff` also takes a node variable at a value (`v.valueIs("30d")`),
which is not a condition: it is a viewer's selection, so it is not shared and never flips. Not a reading: it may name many entities and carries no transform.
It is the fast tier's bool half (ADR 0028), so it can be a slot's transform, decided with no CEL,
which is how every boolean input (`active`, `disabled`, `classWhen`) takes one directly. It can
also be an expression value, a `bool` an author's CEL reads by name. An aggregate the build can settle is a plain `Boolean` instead.
ADR 0034.

**Base component** vs **HA component** — `components/base/` cards know nothing of Home Assistant:
they take strings, taps and readings, and import no `hass` module. An HA component is **thin**: a
subclass that holds an `entity` and assigns the base's inputs from it, declaring no card, template
or slot of its own, so it equals the base card built by hand with the same inputs. ADR 0015.

**Slot type** / **boolean slot** — what a card reads a slot's value AS (`Slot.type`): text by
default, `bool` for a slot its template reads as on/off (a `disabled` ORed into
`data-attr:disabled`, where the string `"false"` would be truthy), or `cssClass` for one placed in a
`class` attribute, whose value the theme's class rules reach (ADR 0020). A boolean slot is one whose
type is `bool`. The CARD sets the type where it binds the slot: on the handle for `cssClass`
(`slotMod.cssClass(name)`), beside `signal` for `bool`, because a reading has no binding of its own;
a `class:` binding (a live cell class) is a bool by kind. The build refuses a reading on a boolean
slot that does not produce a bool.

**Value while** — a slot's `valueWhile`: a boolean input deciding whether the slot has a value at
all. While it does not hold the value is `""`, and the slot's transform and fetches are not run.
Not a **guard**, which is a tap's (ADR 0019), and not `c.iff`, which shows or hides a region's
content, a card's own part included.
ADR 0034.

**Live cell class** — a class on a node's `.fh-cell` wrapper while a reading holds
(`LayoutNode.classWhen`). The renderer binds it on the wrapper itself, so it works on any card and
no template places it. A button's `active` is one (`fh-active`).

**Live cell property** — a CSS custom property on a node's `.fh-cell` wrapper, set from a reading
and kept live (`LayoutNode.cellStyle`) — a live cell class's value-carrying twin, read by the
card's CSS through inheritance. Only `--…` names. ADR 0034.

**Expression value** — a named input a node's or a slot's OWN expressions read as a typed CEL
variable (`expressionValues` in Pkl, `values` on the wire): a literal, a **tally** — a static
candidate list and which of it is present, counted live, the same thing a state condition's `Count`
compares — a **condition**, decided live, or a **fetch**, its provider's answer read on demand.
Not a node variable: nobody chooses it, and nothing below the node sees it. ADR 0034.

**Build passes** — what turns a dashboard's JSON into `Dashboard.Validated`, once per evaluation,
named as a compiler names them:

- **decode** (a compiler's parse): JSON → `Dashboard`. A rule about one value is part of its type
  and fails here (`ChartStyle.Size`).
- **rewrite**: the slug is applied (`DashboardBuild.decode`).
- **lower**: sugar becomes the core form. Pkl does it before emitting: a live cell class or
  property is written as a slot.
- **bind**: each name a slot's CEL reads is connected to its definition, an expression value, and
  so to its type (`Component.bound`, a `BoundSlot`). Not a card's **binding** of a slot, which is
  where a value lands (`x.bind` in Pkl, `{{{x.bind}}}` in the template).
- **check**: the rules across fields and nodes (`validate`).
- **compile**: CEL against the names and types binding gave it; each card's template, once, theme
  classes applied (`Templates.card`), which check reads its names off and the renderer runs.

Bind and compile fix CEL's **environment**, the names and their types. The values are its
**activation**, computed on every render (`Conditions.resolve`).

**Node variable** — a named choice a node DECLARES (`Component.vars`) and its descendants READ;
ADR 0033 lists its scoping rules in one place. The word is always two words: `Renderer` already calls a card's mustache context "vars", and a theme
calls CSS custom properties the same, so a bare "vars" in prose is ambiguous three ways.

Three words go with it, and they are not interchangeable:

- **Declarer** — the node whose `vars` a reference resolved to. Resolution is by NAME up the
  ancestor chain, so a node declaring nothing is transparent and the NEAREST declarer wins.
- **Reference** (`varMod.Ref`, a `Choice.Chosen`'s `by`) — a read of one, as opposed to a value
  written down (`Choice.Fixed`). The distinction is the access story rather than a convenience:
  only a declared variable is writable, so a value written down has nowhere for a write to land.
- **Shadow** — a nested declaration of a name an ancestor also declares, winning for its own
  subtree and nothing else. What "one control over three charts, except this one" is made of.
- **Variable** vs **declaration** — a variable (`varMod.Variable`) is only the name, which a
  reader holds without knowing who declares it. A declaration (`varMod.Declaration`, an entry in
  `Component.vars`) is one node owning a value for that name, and holds the variable.

What a variable holds, highest first:

- **Choice** — what the viewer picked: a write, a `v.` URL param, a link.
- **Initial value** — what the declaration's **initial transform** gave this session: CEL over one
  entity's state (`Declaration.initialTransform`, `withInitialTransform` in Pkl), run ONCE as the
  page opens. Not a choice (no URL param) and not a flip (it never follows the entity). A slider's
  `opensOn` is one.
- **Default** — the value it holds before anybody chooses (`defaultingTo`).

A declaration is a NAME, its default, whether a choice is mirrored into the URL, and an optional
initial transform — that is the whole of it. **There is no "allowed values" on the wire**, deliberately: what a variable may hold
is decided by whoever READS it (a chart's provider refuses a window it cannot parse), and a list
beside the declaration would be a second, weaker copy of that, free to disagree with the control
the author actually rendered. Where a closed set of values is the point, it lives in the Pkl that
emits the declaration and its control from one list.

**Cell** — a node's cell is the wrapper element the renderer puts around it (`.fh-cell`), and the
item the layout sizes: the grid or flex item that `columns(n)`, `hug()` and `grow()` act on. It
carries the layout classes (`fh-cols-3`, `fh-hug`, `fh-grow`, `fh-stick-top`) and the node's cell
slots (`cellClass`, `classWhen`, `cellStyle`). Layout is the backend's job, not each card's.
ADR 0008.

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

**Tap / tap action** — what a click does, as a value rather than a string: a service call on a
**target** (an entity, an area, a floor or a query set), a navigation, opening or closing a popup. A tap names its
own target and never borrows the card's subject entity, so one card may show an entity and act on
another. ADR 0016, 0023, 0024.

**Guard** — the attribute on a tappable element that refuses a second click while the first is still
in flight. The point of the busy machinery; the spinner is decoration. ADR 0019.

**Busy** — per CONTROL: a request is in flight *from this element*. What the guard, the spinner and
the disabled state read. About whether you may click, not about what is shown.

**Disabled while** — the states of a call's TARGET in which a press is meaningless
(`Call.disabledWhile`, plus `unavailable` for every service call), so every card that sends the call
refuses it alike. A card's OWN reason, such as a popup opener that carries no call, is its
`disabled` reading, ORed with the call's. About the entity's state, not about a request: that is
busy. The tap carries it as the `tapDisabled` slot.

**`fh-disabled`** — the ONE "you cannot use this" LOOK, whatever the reason: a tap in flight, or a
press its entity's state makes meaningless. Classes name what the view looks like, not why.
`fh-loading` and the spinner are the in-flight look on top of it; `fh-error` is the server having
refused a press (a REFUSAL is always that after-the-fact answer, never the before-the-press state).

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
Members are addressable nodes in their own right, and so is every node below a member, under an id
below the member's.

**Presence / order** — the two things about a set's membership that can move: whether an entity is in
it, and where it sits. Tracked separately because reordering re-sends nothing.

**Dump** — the generated Pkl file describing the live Home Assistant instance (every area, entity and
its attributes) as typed values, so `dump.entities.light_kitchen` dot-completes in an editor. Built
from a live fetch, never committed. ADR 0013.

**Defaults (`c.defaults`)** — what every card starts from: one `Defaults` object, grouped by card
family (`defaults.light.opensOn`), set in the workspace's own `components.pkl` and overridden on one
card by its builder. A card that reads one carries the whole object, handed over by `c`. Not a node
variable's **default** (below), which is per declaration and per page. ADR 0015.

**Role** — the part a sensor plays on a `c.progress` card beside the countdown or percentage the
card was given: `total`, `percent`, `phase`, `status`, or `ignore` for none. Found at build on the
given sensor's device by the names in `defaults.progress`, one sensor per role by rank; a tie at
the top chooses none. A name only narrows the shape a role requires. ADR 0013.

**Entry** — the top-level Pkl module for one dashboard. `site.pkl` names every entry the instance
serves; an entry's key in it is that dashboard's **slug** (its URL segment). ADR 0021.

**Hoist** — the build step that lifts a popup defined inline inside a tap up into the top-level
surface registry, splicing the owning node's real id into it. Why authors can write a popup where it
is used rather than registering it elsewhere.

**Token (`@@NODE_ID@@`)** — a placeholder Pkl writes because the value is not
knowable while authoring, filled in later by a pass that is. `NODE_ID` stands for *the node that
owns this subtree*: a card constructing its own children writes it so they can name signals the card
owns, and the build replaces it with that node's real id — bottom-up, so the innermost owner wins.
Note this is authorship, not tree position: it means "the card that wrote this", which is why the
renderer cannot derive it from the parent link.

`@@VAR:<name>@@` (`varMod.Variable.declarer`) is the other token, and it IS tree position: the nearest
node above declaring the node variable `name`, spliced node by node so a shadow holds (ADR 0033).

---

## Card structure — what a card's markup holds

**Template** — the whole card's markup, holes included. There is one per card.

**Chrome** — two senses, and they are unrelated, so always say which:

- **The dashboard's chrome** (`Theme.chrome`) — the frame template a dashboard's body is rendered
  into, owning the `#dashboard` swap target and the popup host. Ours, and made of HTML.
- **The browser's chrome** (`ChromeColors`) — the UI the *device* paints around our page: the URL
  bar, an installed app's status bar and splash. Not ours, and made of one colour per scheme,
  which we can only ask for (`<meta name="theme-color">`, the manifest's themeable members).
  ADR 0014.

The vendor is a third thing again, and the reason the sentence "Chrome ignores the chrome colour
in an installed app" needs writing out rather than shortening.

**Region** — a named hole in a template that something else fills, spelled `{{{name}}}` and placed
exactly once, outside every section. A card declares them
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
exposed so a card can show the selection without JavaScript.

**Bake group** — the set of surfaces competing for one host. Exactly one is baked at a time. Who
chooses is the group's activation: a node variable for a tab bar, a `c.select` or an If over a
variable (`Activation.Var`, ADR 0033), each member selected at one of its values or, for an If's
`else`, at every other value; a condition for an If over state. Inside a candidate set's clause,
a group is that clause's: every clause of a member shares its nodes' ids, so the clause is part of
the group's id.

**Flip** — a state-activated bake group changing which branch is selected because *entity state*
moved, not because a user clicked. Server truth, so every viewer gets it. Only a group shown before
and after flips: one inside a set member whose arrival or clause switch places it, or below
another group that flips, is in that placement instead. ADR 0007.

---

## Runtime — how a change reaches a browser

**Node id** — a node's address, derived from its position in the tree (`c_2_0`). Not authored. ADR
0022.

**Index** — what a renderer knows from the tree alone, built once when it is created: ids,
parents, each node's fetching slots, which nodes an entity wakes, every member a candidate can be.
The second of three layers (build, index, decisions; the pipeline doc's §1a). Not the build's
**bind** pass, which runs before any renderer exists.

**Decisions** — what one state snapshot decides: which members a set holds and in which clause,
which branch a state group shows, whether each `valueWhile` holds. Kept for that snapshot, so the
pre-read, the walk and the paint ask rather than decide again, and every viewer of that version
shares them. A viewer's own selection is not a decision, it is looked up.

**Fragment** — one node's own HTML, as opposed to the composed HTML a parent embeds. What gets sent.

**Patch** — one instruction to the browser: morph these bytes over that element, remove it, insert
before it.

**Morph** — Datastar replacing an element's content in place, keeping focus, scroll and any DOM
state rather than swapping the node out.

**Recorder / publisher** — the one fiber per dashboard that watches entity state and writes down
what moved. It renders nothing and sends nothing.

Home Assistant has a component of its own called the recorder — the database behind charts — and
the two have nothing to do with each other. Unqualified, "the recorder" is ours; HA's is always
**HA's recorder**.

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

## History — what a chart is drawn from

Separate from the log/cursor vocabulary above, which is also about "history" in the sense of what a
session has already been sent. Nothing here interacts with **horizon**, **floor** or **position**.

**Series** — a numeric line: every point a provider answered, in time order (the chart stage thins it to draw), carrying no
unit and no name (those belong to the entity's live state, and a copy here could disagree with the
card beside it).

**Window** — what a history fetch reads over a span: `Raw`, the recorder's rows, or `Statistics` at
one period (`p.history.raw`/`p.history.statistics`, ADR 0031). The author picks; `1h`/`24h`/`7d`/`30d` are only more-info's
**presets**. Each window derives the **cache bucket** its answers key on, and only an author adds a
window, so viewers share.

**Bucket** — two unrelated uses, so say which. A **cache bucket** is "now" floored to a window's
step (its statistics period, or a 720th of a raw span), and is what makes a series expire by time
moving rather than by a timer. A **statistics bucket** is one pre-aggregated interval in HA's own
table.

**Fetch** — a read of a **provider** with its parameters: `history` of one entity over one window.
A fetch slot shows one; an expression value reads one in CEL (`p.history.of(e, w)`).
Not a **query**, which is `query.pkl`'s `q.`: it picks CANDIDATES at build time, with presence and
order decided live, and reaches no network. Two words because the two get discussed side by side.

**Fetch slot** — a slot whose value comes from a fetch rather than from live state. It is the
other half of `SlotShape`, opposite a state slot, and it has no `reads` and no signal: where a value
comes from and when it is read are not separate questions for one. It DOES have a `transform` — the
one field both shapes carry, reading a different arm on each. Only a component author writes a fetch
slot.

**Provider** — the named thing that answers a fetch, and the read counterpart of `ServiceCalls`. It
exists as a seam because HA scopes recorder data per user, so who is reading is a property of the
request. `history` is the only one. A provider FETCHES and answers with an **answer**; it has no
opinion about presentation and no way to express one. It owns the fetch's caching: expiring by a
**bucket** rolling works for recorder data because the past is immutable, and would be wrong for a
forecast or a camera.

**Answer** — what a provider answers with: DATA plus a **version**, travelling together so a version
with no content cannot be written. The version says AS OF WHEN this content became current, must be
non-decreasing, and doubles as the caching policy — a stable one is shared by every viewer, one that
moves every call is uncached by construction.

**Stage** — what an answer BECOMES, and the third arm of a slot's `transform`. `chart` draws SVG;
`passthrough` is the absence of a transform — the provider's JSON, which is the contract a third
party writing their own chart library reads against. A fetch slot's default is derived from its
shape, the same rule `reads` follows, so the wire states which tier is in play rather than leaving a
reader to infer it.

**Ask** vs **read** — the same pair (a fetch and the stage applied to it) at two different times,
and the distinction is what lets a fetch parameter be per-viewer at all.

An **ask** (`SlotAsk`) is what a node statically declares: its parameters may still be
**references** to node variables. It is a property of the TREE, so a node holds its own, and the
static input set stays enumerable before the walk.

A **read** (`SlotRead`) is the same pair with every reference resolved — what this render actually
asked for, and what the render key and both caches carry. The pair rather than the fetch alone
because the two deduplicate at different levels: two cards charting one sensor over one window at
different sizes are ONE fetch and TWO drawings, so they share a version and must not share a cache
entry.

What a stage produces carries the provider's version unchanged, because a stage is a deterministic
function of an answer and has no version of its own. The runtime type holding that pair is
`Staged`, and the collection of them for one render is a `FetchSnapshot` — the fetch counterpart
of the `Map[String, EntityState]` the state half travels as. Both were called `Fragment`/
`Fragments`, which collided with the **fragment** defined above (a node's own HTML); the names
were fixed before any of this merged.
