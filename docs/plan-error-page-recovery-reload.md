# Plan: error-page recovery via a dedicated `recover` endpoint + Datastar, and the review follow-ups (fh-datastar-view)

**Status: agreed design, not yet implemented.** This is the follow-up to `plan-failed-dashboard-recovery.md`
(implemented, ADR 0018): a review of that work surfaced five points; the design discussion settled
each of them. This file is the concrete todo list for the implementation commit.

## The points

### 1. A dedicated `recover` SSE endpoint replaces the `?error-page=1` param

**What's wrong today.** The error page's recovery connection opens the SHARED patch stream
(`sse/dashboard/<slug>/patch?error-page=1`) and drags the whole session machinery with it —
`conn` minting (Server.scala:511), registry registration (642), `holds`, cursor bookkeeping —
for a stream that uses none of it. The `error-page` query param is also the wrong seam: it
routes inside `openingPatches` (guards at 828-830) and pollutes the shared stream's logic with
an error-page special case.

**Design.** A new route `GET /sse/dashboard/:slug/recover`:

- No session/`conn`/`holds`/cursor. No `openingPatches`. `liveFor(slug) = None` (unknown slug)
  → 404, there is nothing to recover.
- Stream = `live.renderer.discrete.zipWithPrevious` (no `.drop(1)` — the initial pair is load-bearing), mapped:
  - first pair whose baseline is `Failed` → a **marker event** (`event: recover-open`, no data).
    This is the connection-success proof the test awaits: it can only exist once the stream
    subscribed to the renderer ref and observed `Failed`. Datastar ignores unknown event types,
    so the marker is inert on the page.
  - any pair whose current state is `Ready` → `Server.reloadPatch` (covers the render-vs-connect
    race — fix landed between the page render and this connect — and every `Failed -> Ready`
    recovery).
  - anything else (current `Failed`) → nothing. So opening under `Failed` sends a marker and
    **never a reload**: the anti-loop is structural, not a guard.
  - keep-alive comment merged (same cadence as the shared stream) for consistency.
- Single subscription, no `get`-then-subscribe window, so no missed-transition race.

**Consequences.** `Server.ErrorPageParam`/`errorPageOf` and the two `openingPatches` guards
(828-830) are deleted. The bookmarked-raw-`/patch` case (836: a stale tab or direct URL entry on
a failed slug gets a reload) stays — that is the shared stream's defensive path, not the error
page's own connection.

### 2. The error page reloads via Datastar, not a hand-rolled EventSource

**What's wrong today.** The error page carries an inline `<script>` (Server.scala:1524-1537):
raw `EventSource`, `indexOf` substring match on `"_reload":true`. That was the "protocol
robustness" critique point — the fix (strip the `signals ` prefix, `JSON.parse`, field-check)
is moot because the decision is to delete the script.

**Decision.** The error page uses the Datastar convention the live page already uses (the
connBanner pattern, Server.scala:1683-1685): load the Datastar module and express the reload
declaratively. The page has no renderer, so still no theme and no session — but Datastar's
`@get` + `data-effect` remove the JS entirely:

```
<head>
  <script type="module" src="${assets.rewrite(Server.DatastarCdn)}"></script>
</head>
<body data-init="@get('sse/dashboard/$slug/recover', ${Server.SseRetry})">
  <div data-signals="{${Server.ReloadSignal}: false}"
       data-effect="$$${Server.ReloadSignal} && window.location.reload()">
    <h1>…</h1><pre>…</pre><p>…</p><a href="edit/file/…">…</a>
  </div>
</body>
```

The page stays a Scala template — there is no script left to house, so the vite move (which was
about JS hygiene) buys nothing here. The broader inlined-HTML/JS consolidation
(`UrlSyncScript`, `swRegisterCall`, `fhConn`, `scrollCall`) stays a separate, later move; the
error page no longer needs to be its first consumer. The `data-slug` attribute is dropped (no
JS reads it anymore), and the `errorPage` doc comment is rewritten for the new mechanism.

### 3. `reloadEntries` only logs a broken dashboard on the transition into `Failed`

**What's wrong today.** The `Left` branch (ServerApp.scala:565-574) logs "is now broken" on
EVERY rebuild of an already-broken entry — the watcher fires on every file write, so an author
editing a broken `.pkl` gets a log line per save.

**Fix.** It is a `modify` gate, not an fs2 operator — the transitions live inside a
`SignallingRef`, there is no stream here. Use `prev` in the `Left` branch the way the `Right`
branch already does for "recovered": return the note only when `prev` was NOT already `Failed`.
(Symmetric: `Right` notes only when `prev` WAS `Failed`.)

### 4. The anti-loop/recovery test becomes deterministic — no sleeps, no TestControl

**What's wrong today.** The test "an error-page SSE connection is not reloaded on open under a
failed slug, but reloads when the slug recovers" (FailedDashboardSuite.scala:217-254) sits on
`IO.sleep(500.millis)` + `assertNothing`. A real-time sleep is vacuous on a slow machine — the
open hasn't happened yet, so "nothing arrived" proves nothing.

**Fix.** The "connection itself is successful" marker (point 1) is the causal proof: awaiting
the `recover-open` marker means the stream subscribed under `Failed`. Rewrite the test against
`GET /sse/dashboard/dashboard/recover`:

1. open under `Failed`, await the marker event (a "stream to await in"),
2. assert nothing reload-triggering yet,
3. flip the ref to `Ready`, await the reload,
4. assert the recovery reload is the **first** reload and the only one.

`TestControl` is rejected for this test: it virtualizes time but cannot assert a causal fact —
both the real sleep and a virtual sleep say "pass a window and hope the open completed." It
would earn its place only on a true timing property (e.g. the watcher's
`events.debounce(200.millis)` in `watchSourcesWith`), which nothing currently tests.

### 5. The watcher seam's doc comment notes the OS watcher is untested

**What's wrong today.** `watchSourcesWith`'s seam covers the `events -> reloadEntries` wiring but
not the OS `WatchService` itself. The review said a note that the watcher itself isn't covered is
OK but "kinda given" — so it gets one sentence, not a test.

**Fix.** Extend the `watchSourcesWith` scaladoc (ServerApp.scala:488-492) with a sentence noting
the OS watcher itself is exercised only manually.

### 6. Docs travel with the code (module CLAUDE.md rule)

Same commit as the code, as ADR 0018 did last time:

- **ADR 0018, rewritten in place**: the "Recovery is the SSE reload" bullet and the error-page
  description change — the error page now loads Datastar and opens `…/recover` (no
  `ErrorPageParam`), and the seam table's `openingPatches` row drops the error-page connect
  cases. The "no theme, no Datastar" phrasing becomes "no renderer, no theme, no session".
- **`docs/architecture-rendering-pipeline.md`**: the error-page/recovery sections move to the
  `recover` endpoint + Datastar reload.

## Sequence (one commit)

1. **Point 1** — the `recover` route + stream; delete `ErrorPageParam`/`errorPageOf` and the
   two guards.
2. **Point 2** — rewrite `errorPage` (Datastar module + `data-init`/`data-signals`/`data-effect`,
   delete the inline script and `data-slug`, rewrite the doc comment).
3. **Point 3** — the `reloadEntries` note gate.
4. **Point 4** — rewrite the anti-loop test; add: recover-under-`Ready` emits an immediate
   reload (the render-vs-connect race), recover on an unknown slug → 404, and the error-page
   content test now asserts `data-init` → `/recover` + Datastar module + `data-effect` and no
   `EventSource`/`http-equiv`.
5. **Point 5** — the doc-comment sentence.
6. **Point 6** — ADR 0018 + the pipeline doc.

Verify after the commit: `sbt 'fh-datastar-view/testFull'` (Metals `compile-module` +
`test`), and `scalafmt` (the PreToolUse hook runs it on `git add`).

## Out of scope / resolved by decision

- **`JSON.parse` hardening of the inline script** — moot: the Datastar decision deletes the
  script (point 2).
- **Moving the error page (or the other inline HTML/JS) into vite** — deferred; a separate
  consolidation, per the discussion.
- **`TestControl`** — not used here; reserved for timing properties, none tested today.
- The rest of `plan-failed-dashboard-recovery.md`'s scope (ADR 0018's other decisions:
  boot tolerance, default selection, live repair) — already implemented, unchanged.
