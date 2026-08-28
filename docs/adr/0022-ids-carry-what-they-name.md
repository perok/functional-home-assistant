# ADR 0022 — An id carries what kind of thing it names

- **Status:** Accepted
- **Date:** 2026-08-18
- **Scope:** `model/Ids.scala`, and every signature in `fh/view/runtime` that
  takes an id — `runtime/MemberGraph.scala`, `runtime/SurfaceGraph.scala`,
  `runtime/Renderer.scala`, `runtime/Patches.scala`, `runtime/FragmentLog.scala`
- **Refines:** ADR 0012, whose bake hosts created the first of these
  distinctions (a patch target is not a log key) but recorded it only in
  scaladoc. ADR 0003 owns candidate sets; this owns how their ids are typed.

## Context

Every id in the runtime is a string, and they all read alike. `c_2`,
`c_2_panel`, `c_2_light_a`, `s_det__c_0`, `_c_1__value` — six different spaces,
one shape. Nothing in a `String` signature says which one a function wants, and
the failures from mixing them share a signature of their own: **they are silent,
and they are permanent.**

- A DOM id stored as a **log key**: the changelog renders content FROM its keys,
  so the entry names a fragment that can never be rendered again. Nothing
  errors; that node simply stops updating, for the life of the session.
- A **container** id taken from the static index instead of the member graph: an
  inner set is not in the static index (it hangs off a member), so the ids are
  right, the markup is right, and **no patch is ever emitted** — the container
  the recorder maintains is not the element the browser has.
- A member's **root** read from the static index for the same reason: the member
  reads as main-page, and its patch goes to every connected client whether or
  not they have that surface open.

All three were real. Each was found by accident, and none of them could have
been found by reading the code, because the code looks correct.

## The decision

**An id's type says what kind of node it names, and the compiler enforces it at
every consumer.** Six opaque types over `String` in `model/Ids.scala`:

| type | names | derived by |
|---|---|---|
| `NodeId` | an addressable node — the log's key space | `LayoutNode.pathId`, `surfacePrefix`, `MemberGraph.memberIdOf` |
| `SetId <: NodeId` | a candidate-set container, at any nesting depth | `MemberGraph.setContainer` / `innerSetId` |
| `MemberId <: NodeId` | a materialised set member | `MemberGraph.memberIdOf` |
| `DomId` | an element a patch TARGETS (`c_2`, `c_2_panel`, `popups`) | `Renderer.elementId`, `Renderer.hostId` |
| `SignalId` | a slot's value in the client's signal store (ADR 0017) | `Renderer.signalName` |

Three rules make it work.

**Derivation is one-way and narrow.** `NodeId -> DomId` goes through
`elementId` and nothing travels back. Each type names its derivations in its
own scaladoc, and an id from anywhere else is a bug.

**`<: String`, deliberately.** An id IS a string for interpolation, prefix tests
and sanitising, and widening at those uses costs nothing. What the bound
forbids is the direction that matters: a bare `String`, or a `DomId`, where a
`NodeId` is expected.

**Narrowing is a PARSE, at one place.** The only way to turn an id that arrived
from somewhere else — a log key, a mutation's container — into a `SetId` is
`MemberGraph.setContainer`, whose `Some` is the answer and the proof together.
A map keyed by the WIDE type is how that parse is implemented, which is why
`MemberGraph.sources` and `MemberIndex.byId` are `Map[NodeId, _]` while
everything they hand back is narrow. That is not a lie about their contents; a
parse function's input is the wide type by definition.

## What this does and does not guarantee

Worth stating precisely, because the first version of this doc overclaimed.

**Every consumer is protected.** A signature taking a `SetId` cannot be
satisfied by an id straight out of the static index, so the zero-patches failure
above is not reachable by accident. This is where nearly all the value is, and
it is a genuine impossibility rather than a convention.

**The mint is a guardrail, not a proof.** `SetId.of` asks for the
`LayoutNode.SetNode` the id names as evidence, which narrows minting to callers
that have a real reason to be minting — all four production sites hold one
already, so it costs nothing. But `SetNode` is an ordinary case class with
all-default parameters, so anything in `fh.view` can fabricate one; `TestIds`
does, deliberately. Closing that would mean no public constructor here at all,
with minting folded into `MemberGraph`. Worth doing only if a wrong mint ever
actually happens.

## Consequences

- Signatures carry the story. `memberIdOf(setId: SetId, entityId: String):
  MemberId` and `innerSetId(member: MemberId, …): SetId` state the nested-set id
  scheme — `<member>_<clause>_<child path>` is only meaningful under a member —
  where a comment used to ask two ends to agree.
- `isSetContainer: Boolean` is gone. The parse replaced it, which is one
  mechanism instead of a predicate plus a separate lookup that could disagree.
- Suites mint through named helpers (`TestIds.setId`), never an implicit
  conversion. `NodeId`'s blanket conversion is safe because a test has no wrong
  id-space to reach for; a `SetId` asserts a runtime fact about WHICH INDEX was
  asked, which a suite can be wrong about, so it stays visible at the call site.
- The types are `<: String`, so this is zero-cost at runtime and invisible on
  the wire.

## Rejected along the way

- **A flat `enum NodeId` over the kinds.** Loses `<: String`, so every
  interpolation, prefix test and sort needs unwrapping, and the diff touches
  every id use in the runtime rather than the signatures that care. The
  refinement types buy the same errors for a fraction of the churn.
- **Typing `DomId`/`SignalId` further.** They are already separated and already
  carry a one-way derivation; there is no second kind of either to confuse.
- **Narrowing the parse maps' key types.** `MemberIndex.byId` cannot be
  `Map[MemberId, Member]`: all three readers arrive with an arbitrary id and are
  asking whether it is a member AT ALL, so narrowing needs a parse that could
  only be implemented as this very lookup. `byGroup` had no such excuse and IS
  `Map[SetId, _]`.
- **A validation helper (`require(isMember(id))`) instead of types.** This is
  the shape the bugs above already had: a check each caller must remember to
  perform, against the right index. Parse, don't validate.
