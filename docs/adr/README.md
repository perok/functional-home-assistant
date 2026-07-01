# Architecture Decision Records

Short notes capturing a significant decision: its context, the options weighed,
and the outcome. Numbered sequentially. While the module's design is still in
flux (pre-v1), an ADR is kept current by appending a dated **`## Update —`**
section that records the new design and names the decision it supersedes (rather
than always minting a new ADR); each `Update` is the latest word on what it
touches.

- [0001 — Entity card + per-slot value transforms via JSONata](0001-entity-card-and-value-transforms.md) (updated 2026-06-25b: `params` dissolved, `entity_id` is a magical slot; domain-aware slider)
- [0002 — Multiple dashboards, popup surfaces, and in-place navigation](0002-multi-dashboard-popups-and-navigation.md) (updated 2026-06-26: the `Mount` node is deleted — host HTML lives in templates, the `Surface` is data-driven via `chrome`/`stack`/`bakeInto`/`bakeAs`, card library holds just `popup`; 2026-07-01 note records two un-adopted field-collapse options — drop stacking → all-chrome-less, and derive `mount` from `bakeInto`/`bakeAs`)
- [0003 — Dynamic groups: live membership + per-entity card dispatch](0003-dynamic-groups.md) (partly superseded by 0004; updated 2026-06-25: query-filtered re-renders, presets dropped)
- [0004 — Label as a slot; AST (not JSONata) for queries; attribute memoization](0004-label-as-slot-and-predicate-engine.md) (updated 2026-06-25b: `params` gone, `entity_id` the magical inheritance root; literal/inherited/reactive slot model, identity-slot memoization)
- [0005 — Node-scoped UI state and the cookie persistence tier](0005-node-state-and-the-cookie-tier.md) (cookie = the only client store the server reads on the GET → flash-free first-paint restore via `bakeInto`/`bakeAs`; declared-`state` sugar deferred until a 3rd consumer)
