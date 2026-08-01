# ADR 0012 — One render pass, addressed per client

- **Status:** Accepted
- **Date:** 2026-08-01
- **Scope:** `modules/fh-datastar-view` (the SSE runtime)

## Context

ADR 0002 split live rendering in two. Nodes whose HTML was a pure function of
entity state were rendered once per slug; anything that could differ per client —
an open surface's contents, a bake-group owner — was rendered again per
connection, against that connection's own diff cache.

The split was a cost model, and it bought a duplicated pipeline: two selection
passes, two caches, and a class of bug where a fragment reached one and not the
other. It also mis-framed the problem. What varies is not *clients* but
**variants**, of which the server owns a closed set.

## The decision

**One pass renders; a tag decides who sees it.** Every live patch is produced
once per slug and carries the innermost user-selected surface it belongs to
(`Patches.Addressed`); each connection keeps only what its own open set admits.
State-activated surfaces are transparent to that tag — their selection is server
truth, so tagging with one would hide the patch from everybody (ADR 0007).

**A fragment is a node's OWN html, never the composed html.** The composed form
welds a host to its children, which is what the self/mount split exists to
prevent (ADR 0008). Nodes with no markup of their own — a bare container, a
dynamic group root, anything whose children carry a mount — are neither log keys
nor patch targets. Their children are addressable in their own right.

**Visibility is a chain, not a membership test.** A client can see a node when
every user surface above it is selected AND every state surface above it is
active. `open` alone is not that: a selection is reported for every bake group
whether or not it is on screen. The chain gates what is rendered at all, and
prunes what a resume owes.

**Variance is local, and computed lazily.** The one thing that cannot be
rendered once is a node whose own markup reads its own selection — a `self`
using `{{bakeIndex}}`, or a branch placing a subtree that mounts one. The
variant set is static (one per member of that node's group) and cannot multiply
out, because a node's own rendering carries no mount. So each variant is a log
entry of its own, and the verdict for a variant is computed on first ask and
shared with everyone holding it — not per connection, or the first viewer would
consume the patch and the second would be told nothing changed.

## Consequences

- `Session` holds no diff cache. There is one `FragmentLog` per slug, and
  everything that changes a client's DOM goes through it (ADR 0011).
- N viewers of one dashboard cost one render, including viewers of an open popup
  or a selected tab. Two viewers on *different* variants of one node cost two,
  which is simply what is true.
- The popup stopped being a special channel: it is `ui_<hostId>`, set by its own
  taps like any tab (ADR 0005).
- Rendering per viewer is bounded to the variant case, so the work is per
  *variant*, never per connection.

## Why the no-JS path is worth its cost

`{{bakeIndex}}` in a `self` is the only way a selection is visible without
scripts — a tab click is then an `<a href="?ui.<host>=N">` and the answer is a
fresh document. Supporting it costs four concepts (`nodeVariesByViewer`,
`variantOf`, a per-variant `Fragment`, `Memo`), a branch in the diff path, and
roughly 150 lines. In a JS-only world all of it is dead weight: the shipped tab
bar highlights client-side and needs no round trip. **If the no-JS goal is ever
dropped, this is the first thing to remove** — and removing it means restoring a
`validate` rule that rejects `{{bakeIndex}}` in a `self`.

## Rejected along the way (still guarding the design)

- **A hollow mount plus a per-connection fill.** The first attempt at serving
  viewers on different tabs from one render: insert the branch with its mount
  EMPTY, then let each connection fill it. Two DOM updates for one change, and a
  rendering "for nobody" that leaked a blank tab index into live markup — a
  mount carries client-dependent ATTRIBUTES, not merely children.
- **Baking whatever the connected clients agree on.** Would have removed the
  hollow mount for the common case by reading the union of open sets. It makes
  rendered bytes depend on the audience — one dashboard, one state, different
  HTML depending on who is watching.
- **Per-variant versions, and per-variant log ids.** Variants differ in content,
  not in change timing: the same entity drives all of them, and what
  distinguishes them changes by client action, never by a log event. One version
  per node is right, and `logId` identifies the log, which a renderer swap
  invalidates whole.
- **Deciding suppression per connection.** Two viewers on one variant would have
  the first consume the change and the second receive nothing. What is memoised
  must be the verdict, not the render.
- **Keying the log by `(NodeId, variant)`.** Puts a non-node-id into the ledger
  and into `since`'s results. The variance belongs inside the entry.

## Open

**Should a `self` splice children at all?** Today it may: a tab bar's buttons are
the card's own chrome, generated by the card rather than nested by an author, so
no mount can appear among them. The alternative is that everything a card holds
goes through a mount, which would make the rule unnecessary rather than merely
satisfied. What would decide it: whether any card ever wants AUTHOR-supplied
children inside a `self`. Until then the rule is checked, not trusted.
