# ADR 0029 — A node's fingerprint digests its render INPUTS, not its output bytes

- **Status:** Rejected after measurement
- **Date:** 2026-08-30
- **Scope:** `modules/fh-datastar-view`
- **Refines:** ADR 0012 (each session renders what it is owed — the render
  cache and `RenderInputs`) and ADR 0017 (signal slots — the patch form and
  `holds`).
- **Implements:** issue #230 (the issue stays OPEN; see "What survives").

## Context

A page load *sends* the document form but *records the patch form's digest* in
`holds` (ADR 0017: "`holds` means one thing for a session's whole life"). So
the document walk renders every own-rendering node a SECOND time — the patch
form — and at the four record sites the resulting bytes are used once and
thrown away:

```scala
id -> Held(Some(Digest.of(p.html)), p.signals)   // Server.scala (×3), Patches.scala (×1)
```

Issue #230's proposal: the render is a pure function of its inputs, so hash
the inputs (`Digest.of(cardName, shownVars, cellClasses, id, childDigests)`)
and the second render disappears. This ADR is the measurement that proposal
asked for before being written — and the number came back the wrong way.

## Why it was tried

- The patch form withholds signal slot VALUES (ADR 0017), so for a
  pure-signal leaf the patch bytes are constant across value ticks —
  re-rendering them to discover "unchanged" looks like pure waste.
- The render cache is already input-keyed (ADR 0012); extending input-keying
  to the digest itself appeared to remove the second render entirely.

The implementation was built and is preserved in the branch history: the walk
computed an input digest per leaf and per member (a recursive one for
members, whose bytes carry their children's), `Painted` carried a digest
instead of bytes, and the whole thing was gated by a hedgehog property suite
holding equal digest ⟺ equal bytes over generated shapes.

## What the bench said

`RenderBench`, `-f 1 -wi 4 -i 3 -prof gc`, exact allocation, interleaved
against `main` at identical parameters:

| cell | main | input digest | Δ |
|---|---|---|---|
| `page` (200 plain leaves) | 2,223,937 B/op | 2,622,634 B/op | **+17.9%** |
| `pageFlat` (200 signalled leaves, one container) | 4,666,644 | 5,261,698 | **+12.7%** |
| `pageNarrow` (200 signalled leaves, binary tree) | 5,302,695 | 6,033,703 | **+13.8%** |
| `pageSet` (signalled members) | 4,889,556 | 5,671,944 | **+16.0%** |

A hybrid variant — input digest ONLY where the patch form differs (signalled
nodes), byte digest of the document slice elsewhere — still measured
`pageFlat` +12.8%, `pageNarrow` +14.1%, `pageSet` +16.0%: the regressions are
exactly on the cells the proposal was meant to improve.

## Why it lost

The async-profiler allocation profile of `pageFlat` names the reason
directly: the top allocation section is `PatchInputs.canonical` — the
canonicalization itself.

- **The canonical input string is as large as the bytes it stands for.** A
  leaf's patch rendering is a tiny template over a dozen vars; the
  canonical form (length-prefixed template generation, card, id, classes and
  every var) is comparable in size, and building it costs a growing
  StringBuilder plus the escaping-adjacent copies the byte digest gets for
  free from a slice that already exists.
- **The var-map surgery is not free.** Blanking signal slots (`vars --
  signalSlots`), sorting, and the per-child recursion for members each
  allocate a fresh map/list per node per paint.
- **The second render it replaces is cheap for small templates.** Issue
  #230's own estimate (~5% of `pageSignals`) was the correct order of
  magnitude; hashing a tiny template's bytes costs less than stringifying
  its inputs.

The idea's economics only work where the patch bytes are LARGE relative to
their inputs. A card's patch fragment is the smallest rendering in the
system — the opposite regime.

## What survives

- **The hedgehog property suite** (`DigestPropertySuite`): the walk's
  recorded digest and the live path's rendered bytes must agree, node for
  node, over generated shapes. The walk and the live path stopped sharing
  one string when the walk started threading one buffer (#253, #254); this
  pins the agreement that "what `holds` recorded" and "what a morph sends"
  still match, and it caught two real divergences during the attempt.
- **The finding that a digest-input scheme must include a template
  generation token**: the render cache evicts on renderer identity, but a
  session's `holds` survives a live re-evaluation — without it, a client
  keeps stale bytes across a dashboard change, silently. If the input-digest
  idea is ever revisited (for LARGE fragments), that token is a hard
  requirement, and the shrink-failure mode demands a property test first.
- **The wire contract is untouched**: `holds` still records `Digest.of(patch
  bytes)`; issue #230 stays open for the day fragments are large enough to
  change the economics.
