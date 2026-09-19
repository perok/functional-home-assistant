# Architecture Decision Records

Short notes capturing a significant decision: its context, the options weighed,
and the outcome. Numbered sequentially.

**Convention (pre-v1 / alpha):** each ADR is a *current-state* document — it
describes today's design and the still-load-bearing rationale, including the
alternatives that were rejected along the way. When a decision changes, the ADR
is **rewritten in place** (git history keeps the archaeology); a genuinely new
decision gets a new ADR. Do not append dated update sections.

**An ADR that alters the live rendering pipeline must update
[`../architecture-rendering-pipeline.md`](../architecture-rendering-pipeline.md) in the same change.** That
file is the shape of the running system — the shared/per-client split, the four
node kinds, the reconnect path — and this directory is the reasoning behind it.
Neither substitutes for the other, and a diagram that lags the code is worse
than none because it gets trusted. ADRs 0011 and 0012 are the ones most likely
to move it; 0003 and 0007 own two of the three node kinds it draws.

- [0001 — Entity card + per-slot value transforms via JSONata](0001-entity-card-and-value-transforms.md)
- [0002 — Multiple dashboards, popup surfaces, and navigation](0002-multi-dashboard-popups-and-navigation.md)
- [0003 — Candidate sets: static membership, live presence and order](0003-candidate-sets.md)
- [0004 — The slot model; AST (not JSONata) for queries; attribute memoization](0004-label-as-slot-and-predicate-engine.md)
- [0005 — Node-scoped UI state and the URL mirror](0005-node-state-and-the-url-mirror.md)
- [0006 — Pkl as the dashboard authoring language](0006-pkl-authoring-track.md)
- [0007 — State-activated surfaces: if/else as an activation mode](0007-state-activated-surfaces.md)
- [0008 — Every node is a cell: backend-owned layout wrappers + the `fh-` layout contract](0008-every-node-is-a-cell.md)
- [0009 — How we test: functional tests over a fake HA, then browser smoke](0009-testing-strategy.md)
- [0010 — The live Pkl schema endpoint: `hass.pkl`/`dump.pkl` served over HTTP, resolved in-memory server-side](0010-live-pkl-schema-endpoint.md)
- [0011 — The live connection: resume an SSE reconnect, health, and what may never be dropped](0011-the-live-connection.md)
- [0012 — Each session renders what it is owed](0012-each-session-renders-what-it-is-owed.md)
- [0013 — How the entity dump is generated and typed](0013-dump-codegen-and-typing.md)
- [0014 — The dashboard as an installable local app](0014-installable-local-app.md)
- [0015 — Library structure: a core kit, the shipped components, and a facade](0015-library-structure.md)
- [0016 — What a tap does: per-entity defaults, and more-info as the floor](0016-what-a-tap-does.md)
- [0017 — Signal slots: a value that changes without re-rendering its card](0017-signal-slots.md)
- [0018 — A failed dashboard is a live error state](0018-a-failed-dashboard-is-a-live-error-state.md)
- [0019 — An action in flight: the guard is the feature, the spinner is the afterthought](0019-an-action-in-flight.md)
- [0020 — Components carry their own CSS; the theme is the paint](0020-components-carry-their-own-css.md)
- [0021 — One entrypoint: dashboards are data](0021-one-entrypoint-dashboards-are-data.md)
- [0022 — An id carries what kind of thing it names](0022-ids-carry-what-they-name.md)
- [0023 — Home Assistant is the identity provider; access is a per-dashboard rule](0023-dashboard-access.md)
- [0024 — A tap is a request the server can answer; the DOM should stand on its own](0024-a-tap-is-a-request-the-server-can-answer.md)
- [0025 — A value in flight: what was asked for is not what is showing](0025-a-value-in-flight.md)
- [0026 — The look is BeerCSS: an MD3 framework that styles semantic HTML](0026-the-look-is-beercss.md)
- [0027 — Transforms are CEL: the engine swap and what it kept](0027-transforms-are-cel.md)
- [0028 — The simple tier is opted into, not recognized](0028-simple-tier-is-opted-in.md)
- [0029 — A node's fingerprint digests its render INPUTS, not its output bytes](0029-digest-render-inputs.md) *(rejected after measurement)*
- [0030 — Subscribe to what we read](0030-subscribe-to-what-we-read.md)
