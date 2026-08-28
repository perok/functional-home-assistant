# Plan: one render form for everything the stream sends

Work in flight. Follows the entity signal store
([#134](https://github.com/perok/functional-home-assistant/issues/134)); stacked on that branch.

## Why

A fill and an insert do the same job by two different mechanisms:

| | bytes sent | recorded in `holds` | how the value arrives |
|---|---|---|---|
| member **insert** (`Patches.scala`, the `Mutation.Placed` path) | **patch** form, no seed | `Held.bytes(digest)` — digest only | a **frame**, because `holds` claims no signals |
| surface **fill** (`arrivingFill`), branch fill, refill | **document** form, seeded | `Held(digest, p.signals)` | the **seed** |

ADR 0017 says so outright about the first: *"a member insert [is] correct, since its bytes are
patch-form and carry no seed."* And `signalFrame`'s own comment already states the rule the second
does not use: *"It goes FIRST… a signal set before the element binding it is simply the value that
element paints with when it arrives."*

So this is "one mechanism, not two", applied to the one place the codebase kept two. The document
form stops being a thing the STREAM sends and becomes what it is named after: the HTTP document.

**The prize is not the tidiness.** A patch-form fill carries no values, so its bytes stop depending
on entity state — which is what would let a fill be served from the patch-form `RenderCache` that
already exists, and is the honest version of the "document caching" idea
[#130](https://github.com/perok/functional-home-assistant/issues/130) wants. The earlier claim that
the signal store would deliver that was wrong (see `plan-entity-signal-store.md`); this is the
change that would.

## Measured first

`DatastarMorphContractSuite` now carries the spike as two contract tests. Two instruments, because
they disagree and only one answers the question:

| order | MutationObserver | **painted (rAF)** |
|---|---|---|
| signals-first | `["", "42"]` | **`["42"]`** — never blank |
| elements-first | `["", "42"]` | **`["", "42"]`** — a blank paint |

The DOM passes through blank either way, because the fragment's own text is empty — so the observer
cannot separate the orders and is the wrong instrument. What the browser PAINTS separates them.

Two things follow, and both are load-bearing: a signal patched **before anything reads it** survives
and is picked up by a later-mounted binding (the premise the whole approach rests on), and putting
the frame first is what makes the swap invisible. The harness meters patches 50ms apart, wider than
the single flush production uses, so treat the flash as the direction of the risk rather than its
size — signals-first is safe at any spacing, which is why it is a rule and not a tuning.

## What was tried, and the wall it hit

Converting the three fill sites to patch form is easy — `renderHost`/`renderMembers` take a
`SlotForm`, `arrivingFill` sends `t.patch`, and `holds` records `Held.bytes(digest)` instead of
claiming signals. All 643 non-browser tests stayed green, **which was the warning sign**: a change
this size passing untouched meant nothing covered it.

Adding coverage found the bug. A flip's panel arrived with no frame, so it would have mounted blank
and stayed blank:

```
a flip re-reveals the client's OWN tab  ==>  no frame carried the panel's value
```

The attempted fix — collect the filled hosts' node ids into `touchedIds` so `signalFrame` treats
them as candidates — **does not work, and the approach is wrong, not the details.** `hostNodeIds`
answers for one surface; the value in that test lives in a tab panel **nested inside** the branch
being filled, which is a different surface with its own index. Enumerating ids means recursing
through nested surfaces and keeping that recursion in step with what the fill actually composed —
two things that would drift, silently, into blank panels.

## The way through

**Do not enumerate ids. Take the signals from the fill's own trace.**

The fill already renders the content, and `Traced.own` is `Map[NodeId, Painted]` where
`Painted(html, signals)` — recursive by construction, because it is built by the same walk that
composed the bytes. `arrivingFill` already holds exactly this as `t.own` and currently spends it on
`Held(..., p.signals)`.

So the shape is: a fill emits **two** `Addressed` — a `Patch.Signals` built from its own trace,
then the `Patch.Insert` — and records the signals against the frame rather than the bytes. Nothing
needs to know which nodes a fill "would have" supplied, because the fill is holding the answer.

That needs `renderHost` to have a traced sibling (`renderSurfaceTraced` already exists; the set
branch would return the members' `Painted`s), which is the piece not yet written.

## Steps

1. Give `renderHost` a traced form returning `List[(NodeId, Painted)]`, reusing
   `renderSurfaceTraced` and `renderMember`'s trace.
2. Make each fill site emit its frame from its own trace, ordered **before** its insert, and record
   the signals on the frame rather than on the bytes.
3. Revert the `touchedIds`/`filledIds` experiment — it is the wrong axis and would only mask a gap.
4. Only then flip the three sites to `SlotForm.Patch`.

## Verification

- `sbt fh-datastar-view/testFull` — the browser suites are not optional here. The unit suites were
  green through a change that would have shipped blank panels.
- The assertion that caught it, kept: in `PklDashboardBehaviourSuite`'s flip test, a `data: signals`
  line naming the panel's entity must appear, and must come **before** the `data: elements` line
  carrying the panel.
- A cold-run caveat: `PklDashboardBehaviourSuite` and `UiSmokeSuite` have each failed once on the
  run immediately following a recompile and passed on re-run. Timing against a cold JIT, not this
  work — but it means a single red browser run is worth repeating before believing it.
