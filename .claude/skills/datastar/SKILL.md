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

## Verified against the pinned bundle — the vendored docs are WRONG here

The vendored pages are upstream prose, not the shipped code. Where they disagree with
`modules/fh-datastar-view/assets-cache/*-datastar.js` (pinned v1.0.2), the bundle wins —
and it has disagreed. Check the bundle for anything load-bearing; `grep -o` on the
minified source is enough to settle most questions in a minute.

**Signal filtering on an action.** `attributes.md` showed `@post('/api', {include: ...})`.
The real option is nested:

```
filterSignals: { include: /.*/, exclude: /(^|\.)_/ }
```

- Nested under **`filterSignals`**. A top-level `include`/`exclude` is silently ignored —
  no error, you just get the defaults.
- Patterns are **regexes, not globs** (`typeof e === "string" ? RegExp(...) : e`), so the
  `.` in `'user.*'` matches any character.
- **`_`-exclusion is the DEFAULT** (`/(^|\.)_/` — the name, or any path segment, starting
  with `_`). That is why this project names client-only signals `_reload`/`_sse`. Nothing
  needs to ask for it, and `exclude: '_*'` is actively harmful: as a regex that is "zero or
  more underscores", which matches everywhere and strips EVERY signal from the request.

## Project-specific conventions (fh-datastar-view)

- Attributes use **colon** syntax: `data-on:click`, `data-bind`, `data-signals`
  (not `data-on-click`).
- The backend pushes only `datastar-patch-elements` fragments whose HTML actually
  changed (per-node last-rendered cache in `Server.scala`).
- Value-carrying actions ride in the URL path
  (`POST /sse/action/:domain/:service/:entityId/:key/:value`), built client-side
  with string concatenation (`'.../key/' + $signal`) — template-literal URL
  interpolation is not used.
- **Signals are not free.** Every non-`_` signal is serialised into every request this
  page makes — each action POST and each SSE reconnect. Name a signal the server never
  reads from a request body with a `_` prefix. The deliberate exceptions are `conn` (read
  from action POST bodies) and the resume cursor (`logId`/`storeVersion`/`headHash`/
  `styleHash`, read on reconnect); see ADR 0011.
- **`Server.cursorOf` reads the cursor ALL-OR-NOTHING from signals**, falling back to the
  `data-init` URL params only if the whole set is missing. So `_`-prefixing *some* of those
  four does not shrink the payload — it drops the read to the query params, whose
  `storeVersion` is frozen at page-render time, and the client silently resumes from its
  original version forever.
