---
name: datastar
description: Local Datastar reference for the fh-datastar-view dashboard — attribute syntax, SSE event types, patterns, and the "Datastar way" philosophy. Use when writing or reviewing Datastar attributes/templates, SSE patch logic, or signal usage.
---

# Datastar (local reference)

This project vendors the Datastar docs — consult them **instead of web search**.
All paths are relative to the repo root:

- `docs/reference/datastar/skills/datastar.md` — the full overview: philosophy
  ("Tao"), SSE event types, request/response flow, anti-patterns.
- `docs/reference/datastar/reference/attributes.md` — every `data-*` attribute.
- `docs/reference/datastar/reference/sse.md` — `datastar-patch-elements` /
  `datastar-patch-signals` wire format.
- `docs/reference/datastar/patterns/howtos.md` — concrete recipes.
- `docs/reference/datastar/patterns/tao.md` — the anti-pattern list (optimistic
  updates, custom history management, signal overuse).

Read the file matching the question; start with `skills/datastar.md` when unsure.

## Project-specific conventions (fh-datastar-view)

- Attributes use **colon** syntax: `data-on:click`, `data-bind`, `data-signals`
  (not `data-on-click`).
- The backend pushes only `datastar-patch-elements` fragments whose HTML actually
  changed (per-node last-rendered cache in `Server.scala`).
- Value-carrying actions ride in the URL path
  (`POST /sse/action/:domain/:service/:entityId/:key/:value`), built client-side
  with string concatenation (`'.../key/' + $signal`) — template-literal URL
  interpolation is not used.
