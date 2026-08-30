# ADR 0029 — A node's fingerprint digests its render INPUTS, not its output bytes

- **Status:** Proposed
- **Date:** 2026-08-30
- **Scope:** `modules/fh-datastar-view`
- **Refines:** ADR 0012 (each session renders what it is owed — the render
  cache and `RenderInputs`) and ADR 0017 (signal slots — the patch form and
  `holds`).
- **Implements:** issue #230.

## Context

A page load *sends* the document form but *records the patch form's digest* in
`holds` (ADR 0017: "`holds` means one thing for a session's whole life"). So
the document walk renders every own-rendering node a SECOND time — the patch
form — and at the four record sites the resulting bytes are used once and
thrown away:

```scala
id -> Held(Some(Digest.of(p.html)), p.signals)   // Server.scala (×3), Patches.scala (×1)
```

The #253 visitor walk and the #254 member walk removed the *copying* around
those renders; the renders themselves remain. On the 200-leaf `pageSignals`
fixture the walk still executes every signalled leaf's template twice per
paint — once to send, once to hash.

Two facts make the second render pure waste:

1. **The patch form contains no signal values** (ADR 0017 withholds them —
   they ride the signal frame). So a patch rendering is a pure function of
   things the walk already holds by the time the digest is wanted: the card,
   the resolved vars (with signal-slot values blanked exactly as the patch
   form blanks them), the cell classes and the id. For a pure-signal leaf the
   patch bytes are *constant across value ticks* — re-rendering them to
   discover "unchanged" is the definition of the waste.
2. **The render cache is already input-keyed** (ADR 0012): `RenderInputs` —
   the content versions of the entities the node reads — decide WHEN to
   re-render. The digest is a second, different question — WHAT the bytes
   are — and answering it today costs a render.

## The decision

**`Digest` of a node's own rendering is computed from its render inputs, not
from its output bytes.**

```scala
Digest.ofPatch(PatchInputs(templateGen, cardName, id, cellClasses, shownVars))
```

- `Painted(html: String, signals)` becomes `Painted(digest: Digest, signals)`.
  The document walk (and the member walk's per-member `Painted`) computes the
  digest from the inputs in hand and never renders the patch form. The
  `twoForms` machinery in the walk — the second execute, and the #254
  no-signals slice — both die: the digest covers every member and leaf
  uniformly, signalled or not.
- The four record sites read `p.digest` directly.
- `RenderCache`'s entries keep their html (the morph's bytes) but their
  digest is the SAME input digest, not `Digest.of(html)`: what the cache
  stores and what `holds` recorded must be comparable, and they are
  comparable only if both sides name their bytes the same way.

### What the inputs must cover

Equal inputs ⟺ equal bytes is the whole contract, so every input of the
patch rendering is enumerated, exhaustively:

- **`templateGen` — the renderer's template generation** (the dashboard
  content version the renderer was built from). This input is NOT in issue
  #230's list, and it is the one that bites: the render cache evicts on a
  renderer swap (`_.renderer eq renderer`), but a session's `holds` survives
  a swap — re-evaluation replaces the renderer under live connections. With
  byte digests a swapped template's new bytes simply digest differently; with
  input digests, same card, same vars, same id ⇒ same digest ⇒ **a client
  keeps stale bytes forever across a dashboard change**, silently. The
  generation token is one string in the hash and the failure is closed.
- **`cardName`** — template identity within one renderer.
- **`id`** — the node's id, spelled in the wrapper.
- **`cellClasses`** — the wrapper's class attribute.
- **`shownVars`** — the resolved vars as the PATCH FORM sees them: signal
  slot values blanked (`resolved.vars -- resolved.signalSlots`), which is
  exactly the rule `NodeContext.fhGet` applies. Deliberately NOT the full
  vars: including a signal value would digest differently on every value
  tick — the spurious-morph storm ADR 0017 exists to prevent, reintroduced
  through the fingerprint.

No children input: `own` exists only for nodes with an own rendering
([[hasOwnRendering]] — leaves), and `renderInputs` already refuses to cache
nodes whose bytes carry children. A leaf's own markup names no child bytes.

### What changes in the consumers

- **`render(..., SlotForm.Patch)`** (the `renderNodeById` engine) can no
  longer read its bytes out of the trace — `own.html` is gone. It renders on
  demand, as it did before the trace existed; the cache keeps it at once per
  generation. The property the old shape bought — "what `renderNodeById`
  returns and what `holds` recorded are the same string BY CONSTRUCTION" —
  is deliberately traded for "equal digest ⟺ equal bytes, PROPERTY-TESTED".
  The doc comments on `render` and `Traced` move with it.
- **The morph path** is unchanged in shape (`Patches.morph` → cache →
  digest-vs-`holds`) and switches digest source in the same commit.
- **Members** (`#254`'s `Painted`): the per-member patch render — both the
  slice case and the signalled case — collapses into the input digest. A
  member's real patch bytes still come from `renderMember` on demand, as
  they already do for every patch that sends them.

### What makes it safe

- **Both paths switch together, in one commit.** A walk recording input
  digests while the cache digests bytes would make every node look changed
  on the first tick — the spurious-morph storm, byte for byte.
- **A property test asserts the biconditional** — equal digest ⟺ equal
  bytes — over GENERATED node shapes (varied cards, slots, signal-slot
  declarations, cell classes, ids; values that include the escape set and
  mustache-looking text), never over a fixed example. This is the only test
  that catches a missed input, and a missed input fails SILENTLY and
  PERMANENTLY: two different renderings hash the same, so a client keeps
  stale bytes forever with nothing to report it.
- The `RenderBench` cells to watch: `pageSignals` (200 signalled leaves —
  the second execute disappears from the walk) and `pageSet` (per-member
  renders collapse).

## Consequences

- The walk stops rendering the patch form; a first paint renders each leaf
  ONCE. The digest costs a hash of a small structured string.
- A template's bytes are never materialized just to be hashed.
- `Digest`'s meaning changes — ADR 0012's cache correctness argument and
  ADR 0017's `holds` discipline now lean on the enumerated input list and
  the property test instead of on "it is the bytes' own hash". The property
  test is the contract's enforcement, not a nicety.
- The one-way door: a deployed mix of old-digest clients and new-digest
  servers cannot exist (digests are server-internal state held per session
  in memory), so no wire migration is needed — the switch is atomic per
  process.
