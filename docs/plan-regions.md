# Plan: regions, as slots are

A region today is a string that three places must spell alike: the card declares it
(`regions = new Mapping { ["features"] = new nodes.Region {} }`), the template places it
(`{{{features}}}`), and the node fills it (`children = new Mapping { ["features"] = … }`). A baked
region adds a fourth (`bakeAs = "panel"`) and a fifth, the host id the template must remember to
write (`id="{{hostId}}"`). That is the shape slots had before #657.

What fills a region is also written three different ways, and only two of them in Pkl:

| What fills it | Authored as | Host markup written by |
|---|---|---|
| Fixed nodes | `children { … }`, or `children = new Mapping { [name] = … }` | the card's template |
| One of several branches, picked by a condition or a node variable (`c.iff`, `c.tabs`, `c.select`, the slider's controls) | `inlineSurfaces` with `bakeInto`/`bakeAs`/`bakeIndex`, and `fill = "baked"` on the region | the card's template, by convention (`id="{{hostId}}"`) |
| One node per present candidate (`q.from(…).render(…).build()`) | `pred.SetNode`, a layout node that is not a card | **Scala**: `Renderer.groupOpen`, `<div class="fh-cell fh-group" id="…">` |

The popup host is a fourth: `theme.chrome` writes it, and Scala writes it again as a literal
`<div id="popups"></div>` when the last popup closes (`Server.scala:1056`, `Server.orphanedPopup`).

**State:** plan only. Nothing is agreed.

## Is a region a slot whose value is nodes?

For the handle, yes: one named object declared once, placed through itself and filled through
itself. A typo is then a Pkl error at the line, and the settings live on the handle. The slot spikes
carry over unchanged: interpolation through `toString`, a `Listing` of handles lowered to the wire
map, and a duplicate name reported on its node.

For what fills it, no, and the difference is the reason regions exist:

| | Slot | Region |
|---|---|---|
| Filled by | a value: a literal, a reading or a fetch, then steps | nodes: fixed, a choice among branches, or one per present candidate |
| What reaches the page | the value, as bytes or a signal | the nodes, each with an id and a patch of its own |
| When it moves | the node is re-rendered, or a signal frame is sent | the HOST is patched: `Inner` for a choice, `Before`/`Append`/`Remove` per member |
| What a live value does | becomes the output | only picks WHICH nodes; it never becomes bytes |

So the leaf/structure rule (ADR 0012) stays exactly as it is. A region's content never comes back
through its card's patch, because the card never renders it as a value.

## Design

### 1. A region handle

```pkl
const local features = nodes.region("features")      // fixed nodes
const local panel = nodes.region("panel").hosted()     // its content moves: it needs a host

cardDef = new nodes.CardDef {
  regions { features; panel }
  template = """
    <article>…\(features)\(panel.host("div", "fh-tab-panel"))</article>
    """
}
```

- `\(features)` prints `{{{features}}}`.
- `panel.host("div", "fh-tab-panel")` prints
  `<div id="{{hostId}}" class="fh-tab-panel">{{{panel}}}</div>`. The handle writes the host
  element, so the id cannot be forgotten and the hole is the element's only content.
- The `regions` Listing lowers to today's wire map, so step R1 changes no wire field.
- `children { … }` stays the sugar for the default region, as it is today.

### 2. Three fills, given through the handle on the node

```pkl
fills {
  features.of(xs)                                   // fixed nodes
  panel.choose(tabVar, branches)                    // a node variable's value picks a branch
  branch.choose(cond, thenNodes, elseNodes)         // a condition picks: first branch that holds
  members.each(q.from(lights).where(…).orderBy(…))  // one node per present candidate, in order
}
```

- The card says only whether a region is `hosted`. **What** fills it is the node's choice, through
  the handle. Today the card says `fill = "baked"` and the node builds the surfaces itself, which
  is the same fact written twice.
- `choose` and `each` need a hosted region, because their content changes after the first paint.
  The build refuses either on a region that is not hosted.
- `choose` writes `inlineSurfaces` with `bakeInto`, `bakeAs = region.name` and `bakeIndex`. No card
  spells any of those again.
- `fills` is hidden and lowers to the wire's `regions`, as `slots` lowers to `templateSlots`.

### 3. A candidate set is a card

`build()` returns a `group` node: a card in `core/` with one hosted region `members`, filled by
`each`. Its host is its own cell, as it is today: the card adds `fh-group` to its cell classes, and
the cell's id is the node id, which is the `SetId` (`Ids.scala`). So the bytes do not change, and
the markup moves from `Renderer.groupOpen` into Pkl.

On the wire, a set becomes a `Component` whose region holds an `Each` fill (candidates, orderBy,
limit, members). `Component.regions` becomes `Map[String, RegionFill]`, with
`RegionFill = Nodes | Each`, and `LayoutNode.SetNode` goes. Scala keeps what only it can do:
- `MemberGraph` decides presence, clause and order from the `Each`;
- `Patches` addresses the host and the members.

The `SetNode` case in `Renderer.tracedInto` becomes a third branch of the region loop that every
card already runs.

`q.from(…).render(…).build()` is unchanged for a dashboard author. So is a set's place among fixed
siblings: it is a node in its parent's region, as an `If` is.

### 4. The popup host is the chrome's

A close sends the chrome's own host back empty, not a Scala literal. Either it is rendered from
`theme.chrome`, or it is an `Inner` patch with no content if the pinned bundle accepts one (to be
spiked). After this, Scala writes no region or host markup at all.

### What stays in Scala, and why

- **The `.fh-cell` wrapper** (ADR 0008). It is every node's layout item, its patch target, and the
  carrier of its signal seed and URL variables. Moving it into each card would make every card
  responsible for those attributes. If it moves at all, it moves as one theme-level template, in its
  own round.
- **The page shell, the error page and the default `<main id="dashboard">` chrome.** These are
  page-level, not regions.
- **The failed-fetch label** (`Fetches.scala:65`). It is a slot value, not a region.

## Later, design only: one runtime machinery for "a value picks what fills a region"

After steps R1–R4, the author has one concept and Scala holds no markup. The runtime still has two
paths: `SurfaceGraph` bakes a choice into a host, and `MemberGraph` places members. They may be one
path:
- **An `If` is a set of one member whose clauses are its branches.** The first clause that holds
  renders, and none means empty.
- **Tabs and an `If` over a variable are the same choice, made per viewer.**

What would have to be answered:
- **Per-viewer choices.** A tab's selection lives in `Viewing`, and a set's decisions live in
  `Shared` (§1a). One mechanism must keep that split.
- **Ids.** A surface's nodes are `s_<id>__…`. A member's nodes are `<gid>_<entity>`, and its
  clauses share ids.
- **Patches.** A flip is an `Inner` on the host. A member arrives or leaves by its own patch.
- **ADR 0007's argument**, that "a member container is a per-entity comprehension, not a gate". With
  fills, both are "a value picks nodes for a hosted region", and the only difference is one key
  against many.

Nothing here is built until R1–R4 are in and this round is agreed.

## Who moves

| Step | What changes |
|---|---|
| R1 (handles) | Every card that declares a region: `layout.pkl:34` (Grid, Row, Column), `core/surface.pkl:87` (Popup), `components/base/surface.pkl` (Tabs, If), `components/select.pkl` (the shared option and panel regions, two hosts, the chooser), `components/base/slider.pkl` (head, controls, rows), `components/base/tile.pkl` (features). In the goldens, only the If's host moves: it is the one template that writes `class` before `id`. |
| R2 (sets) | Every wire snapshot with a set. Goldens must not move. |
| R3 (fills) | Tabs, If, select and the slider's controls, which build `inlineSurfaces` by hand today. |
| Docs | ADR 0003 (a set is a card), ADR 0007 (`choose`), ADR 0012 and ADR 0002 (hosts). Pipeline doc §4a and §4b. Terminology: region, host, bake and member, plus a new term, **fill**. `writing-components.md`, and the module `CLAUDE.md` line on `.fh-group`. |

## Steps

1. **R1, handles.**
   - Pkl: `nodes.region`, `hosted()`, `host(tag, classes)`, `CardDef.regions` as a Listing, and
     `fills` with `of`.
   - Lowered to today's wire: wire snapshots byte-identical, and the goldens move only at the If's
     host (attribute order).
   - A duplicate region name is reported on the node.
   - Pkl facts: a typo is an error, a hosted region placed without `host` is an error, and a
     region left unplaced is an error.
2. **R2, a set is a card.**
   - `RegionFill = Nodes | Each`, and the `group` card.
   - `SetNode` goes from Pkl, the wire, `Ready`, `Validate` and `Renderer`, and `groupOpen` goes.
   - Goldens byte-identical.
   - Bench, allocation first, `-XX:-DoEscapeAnalysis` on both sides: `publishSetOrdered`,
     `leaderRow` and `tickRender`, plus `validated` timing.
3. **R3, `choose`.**
   - Tabs, If, select and slider controls on `choose`; `Region.fill` goes.
   - The build refuses a `choose` or an `each` in a region that is not hosted.
   - Wire unchanged, since `choose` writes today's surfaces.
4. **R4, the popup host.** Spike `Inner` with no content against the pinned bundle; otherwise render
   the chrome's host empty. A test that no Scala source writes a host or region element.
5. **Docs** go in each step's commit. When R1–R4 are in, the "one runtime machinery" round is
   discussed. This plan is deleted when the maintainer says so.

## Open questions

1. **The host element.** Written by the handle (`panel.host("div", "fh-tab-panel")`), or written by
   the author with `\(panel.hostId)` inside it and checked by `validate`? The recommendation is the
   handle: a rule the build cannot check is the drift this plan removes. The cost is a fixed
   attribute order.
2. **A set on the wire.** A `Component` with an `Each` fill, with `SetNode` deleted (recommended),
   or `SetNode` kept as a kind that gains a `card`?
3. **Where the fill kind lives.** On the node, through the handle, with the card saying only
   `hosted` (recommended), or kept on the card as `fill = "baked"`?
4. **Names.** `of` / `choose` / `each`, `hosted` / `host`, `fills`, and `group` for the set's card.
5. **The runtime machinery.** After R1–R4 as above, or designed into R2/R3 now?
