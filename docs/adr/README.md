# Architecture Decision Records

Short notes capturing a significant decision: its context, the options weighed,
and the outcome. Numbered sequentially.

**Convention (pre-v1 / alpha):** each ADR is a *current-state* document — it
describes today's design and the still-load-bearing rationale, including the
alternatives that were rejected along the way. When a decision changes, the ADR
is **rewritten in place** (git history keeps the archaeology); a genuinely new
decision gets a new ADR. Do not append dated update sections.

- [0001 — Entity card + per-slot value transforms via JSONata](0001-entity-card-and-value-transforms.md)
- [0002 — Multiple dashboards, popup surfaces, and in-place navigation](0002-multi-dashboard-popups-and-navigation.md)
- [0003 — Dynamic groups: live membership + per-entity card dispatch](0003-dynamic-groups.md)
- [0004 — The slot model; AST (not JSONata) for queries; attribute memoization](0004-label-as-slot-and-predicate-engine.md)
- [0005 — Node-scoped UI state and the cookie persistence tier](0005-node-state-and-the-cookie-tier.md)
- [0006 — Pkl as the dashboard authoring language](0006-pkl-authoring-track.md)
- [0007 — State-activated surfaces: if/else as an activation mode](0007-state-activated-surfaces.md)
- [0008 — Every node is a cell: backend-owned layout wrappers + the `fh-` layout contract](0008-every-node-is-a-cell.md)
- [0009 — How we test: functional tests over a fake HA, then browser smoke](0009-testing-strategy.md)
