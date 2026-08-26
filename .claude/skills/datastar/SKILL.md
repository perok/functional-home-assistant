---
name: datastar
description: Datastar reference for the fh-datastar-view dashboard — points to context7 for general docs, plus pinned-bundle corrections and project conventions context7 doesn't have. Use when writing or reviewing Datastar attributes/templates, SSE patch logic, or signal usage.
---

# Datastar

For general Datastar docs — attribute syntax, SSE event types, philosophy ("Tao"),
patterns/howtos, anti-patterns — use the **context7 MCP tool**
(`resolve-library-id` → `/websites/data-star_dev`, then `query-docs`) instead of
web search or vendored copies. It tracks upstream data-star.dev directly, so it
stays current without us maintaining a local mirror. We pin Datastar v1.x and
track latest v1 intentionally; v1 updates are not expected to break this
project, so current-upstream docs are the right reference, not a pinned
snapshot.

What context7 can't tell you — covered below instead:

## Verified against the pinned bundle — docs are prose, not the shipped code

Any docs (context7 included) describe upstream behavior, not necessarily
`modules/fh-datastar-view/assets-cache/*-datastar.js` (pinned v1.0.2). Where they disagree,
the bundle wins. Check the bundle for anything load-bearing; `grep -o` on the minified
source is enough to settle most questions in a minute.

**Signal filtering on an action.** Docs have shown a flat `@post('/api', {include: ...})`
form in the past. The real option is nested:

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

**`null` DELETES a signal — it does not set one to null.** The store proxy is
explicit (`if (a == null) delete r[o]`), and every binding already subscribed to
that name is orphaned: reading the name afterwards re-creates it as `""`, which
nothing is watching, so those elements never update again. **Nothing is reported
anywhere and the rest of the page keeps working** — one dead signal, not a dead
page, which is what makes it hard to spot. A server-sent `{"s": null}` does the
same thing as a client-side `$s = null`.

Consequence: **no signals frame may ever carry a JSON null.**
`Datastar.signalsJson` carries the rule (a rule and not a type, because
`Patch.Signals` is deliberately `Json`-valued — the cursor rides in it as a
nested object).

**`''` is a PRESENT attribute to `data-attr`, and a falsy one to `data-style`.**
Two plugins, two readings of the same value, and only one of them is documented:

- `data-attr` treats `''` as HTML's boolean-attribute spelling and SETS the
  attribute. A `data-attr:disabled` reading a signal that rests at `''` sits
  permanently disabled. Spell the predicate — which is what Datastar's own docs
  do: `data-attr="{disabled: $foo == ''}"`.
- `data-style` treats `''` as falsy and restores the original inline style.
- `data-attr` handles **null** correctly: an expression evaluating to null
  removes the attribute, so `data-attr:aria-label="$foo"` is exactly as it looks.
  The trap is in assigning null to the signal, not in the plugin.

**`data-ignore-morph` is BOTH-SIDED, and it survives an ancestor's morph.** Read off the
bundle, not yet measured — the docs state neither half. In the per-node morph:

```js
dt=(e,t)=>{ … if(r.hasAttribute(Re)&&s.hasAttribute(Re))return e; …   // Re = data-ignore-morph
```

- **Both** the node in the DOM and the node in the ARRIVING fragment must carry the
  attribute. A server that emits the element without it in one of its two rendering forms
  gets an ordinary morph — i.e. the subtree it meant to protect is blown away.
- It returns the existing node *before* attribute reconciliation, so the element's own
  attributes are preserved too, not just its children.
- This is the per-node walk, so the skip applies **while an ancestor is being morphed** —
  not only when a patch targets that element. (The top-level entry has its own guard,
  `e.parentElement?.closest('[data-ignore-morph]')`, which is one-sided.)

Unverified and worth a spike before relying on it: whether an `Inner`-mode patch aimed AT a
marked element is refused by the same guard.

**`data-bind` beats a co-located `data-attr:value` from the first pass.**
`data-bind` sets `.value` through the IDL before any user interaction, which
raises the browser's dirty-value flag, so the content attribute never positions
the control even once. Reasoning from the HTML spec alone gets this wrong (it
predicts "inert only after a drag"); the answer is inert at t=0.

All four measured in `DatastarMorphContractSuite`, which carries a CONTROL for
the silent ones — a deliberately throwing expression IS reported — because an
earlier version of that test concluded far too much from a silence it had not
shown was meaningful. **Do that:** when a spike's result is a surprise, add the
control that separates "the thing I think happened" from "my harness is lying"
before writing the conclusion down.

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
