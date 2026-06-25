# Architecture Decision Records

Short notes capturing a significant decision: its context, the options weighed,
and the outcome. Numbered sequentially. While the module's design is still in
flux (pre-v1), an ADR is kept current by appending a dated **`## Update —`**
section that records the new design and names the decision it supersedes (rather
than always minting a new ADR); each `Update` is the latest word on what it
touches.

- [0001 — Entity card + per-slot value transforms via JSONata](0001-entity-card-and-value-transforms.md) (updated 2026-06-25: co-located components + derived cards, structural params, domain-aware slider)
- [0002 — Multiple dashboards, popup surfaces, and in-place navigation](0002-multi-dashboard-popups-and-navigation.md) (updated 2026-06-25: card-name-agnostic backend)
- [0003 — Dynamic groups: live membership + per-entity card dispatch](0003-dynamic-groups.md) (partly superseded by 0004; updated 2026-06-25: query-filtered re-renders, presets dropped)
- [0004 — Label as a slot; AST (not JSONata) for queries; attribute memoization](0004-label-as-slot-and-predicate-engine.md) (updated 2026-06-25: literal/inherited/reactive slot model, structural params, identity-slot memoization)
