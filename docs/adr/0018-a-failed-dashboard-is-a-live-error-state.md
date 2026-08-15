# 0018 — A failed dashboard is a live error state

> Supersedes the design plans `docs/plan-failed-dashboard-recovery.md` and
> `docs/plan-error-page-recovery-reload.md` (deleted; the alternatives they
> rejected are recorded in the section below).

## Context

A dashboard whose Pkl source is broken — a bad edit, a removed entity, a stale
import — failed hard. At startup a single broken entry made `prepareRenderers`
raise, and an all-failed workspace took the whole boot down: the server that
exists to *fix* the sources could not start. On a source watcher tick the same
failure was silently dropped (`rendererRefs.get(slug).traverse_`), leaving the
previous renderer serving stale HTML with no fix path and no hint that the
edit had even been seen. And `defaultSlugFrom` picked only among *built*
dashboards, so a broken configured default silently bounced the root to
whatever did build.

`BuildApp`/`sbt dashboardBuild` stays eagerly failing on purpose — that path is
a person at a terminal wanting an error. The runtime is a person at a browser
who needs the server alive.

## Decision

**A failed dashboard is a live error state, not a skipped entry.** Every
discovered entry is registered at boot, watched, and served; what differs is
what serving means. The per-slug `SignallingRef` holds a
`Server.RendererState` — `Ready(renderer)` or `Failed(message)` — and the five
places that must behave differently on `Failed` match on it. Everything else
keeps taking a concrete `Renderer`; a single converter collapses the state at
the seam.

- **Boot tolerates any failure, including all of them.** `prepareRenderers`
  keeps only an empty directory fatal; per-entry failures are collected into
  `Prepared.failed` and seeded as `Failed` refs, so an all-failed workspace
  still boots — to the editor and each slug's error page.
- **The error page is the fix path.** `GET /d/:slug` on a `Failed` slug serves
  a self-contained HTML document: no renderer, no theme, no cursor — it names
  the slug, the escaped build error, and carries an editor link to
  `<slug>.pkl`. HTML requests get the page; non-HTML consumers (`nodeDebug`,
  action POSTs, `publisherFor`) see a failed slug as absent, exactly as they
  see an unknown one. A connected SSE session is told to `reload` across both
  directions of a `Ready`⇄`Failed` transition — the error document has no
  `#dashboard` to patch and no head to patch into, so a full reload is the
  only sound transition either way.
- **Recovery is a dedicated Datastar stream, not a meta-refresh.** The error
  page's only script is the Datastar module; its `data-init` opens the
  dedicated `sse/dashboard/<slug>/recover` stream
  ([[Server#recoverStream]]) — a plain subscription to the slug's state with
  no session, no cursor, no `openingPatches`. Its first element doubles as the
  connection marker ([[Server.recoverOpenMarker]], a `recover-open` SSE
  COMMENT — the browser's EventSource discards comments before any listener,
  so Datastar never even receives it): a still-`Failed` slug emits the marker,
  NOT a reload — a reload here would loop, since the page just loaded — and an
  already-`Ready` slug emits a `_reload` patch (the fix landed between render
  and connect, after the transition's reload was sent to nobody). Every later
  `Failed -> Ready` transition — and every re-broken edit, whose CHANGED
  `Failed` message the page must show — emits a `_reload` patch that
  `data-effect` turns into `window.location.reload()`. The reload that
  announces recovery therefore arrives precisely when the slug recovers or its
  error changes, never on a poll schedule, and the anti-loop is structural —
  an UNCHANGED `Failed` state never emits a reload, on open or thereafter.

  **Known limitation: a re-break while the SSE is down goes stale.** The
  message-change reload only compares against the PREVIOUS element of the SAME
  connection. If the stream disconnects (or the error page is opened, served,
  and the connection drops) and the slug is re-broken while it is down, the
  reconnected stream's first element under `Failed` is the marker, not a reload
  — the page keeps the last message it rendered until the next state change.
  Closing it would need the page to tell the server which message it last
  showed (a `_`-prefixed signal serializing into the reconnect URL), which is
  client state the design deliberately avoids; the live-connection path that
  always sees every transition ([[Server.reloadRepaints]]) does not miss it.
- **Repair is live.** `reloadEntries` re-evaluates every entry on each source
  edit and sets **every** ref: `Right` → `Ready`, `Left` → `Failed(message)`.
  A dashboard broken since startup recovers without a restart; a live one that
  breaks shows the error page; a repaired entry's import set joins the watch
  graph. The known edge, accepted: a never-built entry's loose `file:` imports
  are not watched until it builds once (the entry file and the `PklProject`
  manifest always are, so the common paths fire).
- **The configured default stays the default even when it is broken.** If
  `DEFAULT_DASHBOARD` names any discovered entry, it wins — its error page at
  the root is the point. Only a configured value naming no entry falls through
  to the built-`dashboard` → entry-`dashboard` → first-built → first-entry
  order, which keeps an all-failed workspace serving the first entry (editable)
  rather than 404ing.

### The seam, not the machinery

The `Failed` state deliberately never propagates down into the rendering
machinery. `RendererState.rendererOf: Option[Renderer]` collapses it, and the
bulk of `Server.scala` sees `None` where a failed slug lives — the recorder,
`Patches.resume`, `recordFrame`, the page render, surface swap and mount fill
all keep taking a concrete `Renderer`. Exactly five seams match on the state:

| seam | `Ready` | `Failed` |
|---|---|---|
| `rendererFor` | the renderer | `None` → 404 for non-HTML consumers |
| `publisherFor` | record frames | an empty stream (nothing to record) |
| `openingPatches` | the narrowest patch | `None` → a `reload` patch (defensive: only a stale connection or a direct SSE URL reaches it — the error page opens the `recover` stream) |
| `reloadRepaints` | repaint connections | `reload` patch across the transition |
| `recoverStream` | an immediate `reload` on open (the fix landed between render and connect) | the inert `recover-open` comment on open; a `reload` on each `Failed -> Ready` and on a changed `Failed` message |
| `pageResponse` | the page | the self-contained error page |

`withSession`, `nodeDebug`, and the session machinery ride `rendererFor`;
`push` always writes `Ready` (a swap after a fix is a swap back to health).
The startup failure messages are not logged — a `Failed` slug's publisher is
an empty stream, so `failed.log` never sees `publisherFor`'s output.

## Rejected alternatives

- **Skip the broken entry.** It becomes invisible: no error page, no editor
  shortcut, and — until this change — no recovery short of a restart, because
  a startup-failed entry had no ref for `reloadEntries` to set. "Skip" was
  also the default-flavored problem: the root silently points at a different
  dashboard, and the fix is masked.
- **Keep serving the previous renderer on a broken edit.** The edit is then
  not a failure the user can see — the dashboard looks fine and is stale, with
  no affordance pointing at the break. A failing entry with no previous
  renderer (startup) still had nowhere to go. The error page is the more
  honest state, and the `reload` repaint makes the transition explicit.
- **Retry/backoff of the build.** Unneeded: the source watcher already
  re-evaluates on every edit, so the repair loop is the edit itself.
- **Re-mint a failed slug's registry entry on its first successful build.**
  "Minting" means constructing a fresh `LiveSlug` on demand — new renderer
  ref, log, doorbell, and recorder fiber (the shape `push` uses for a brand
  new slug). It only sounds small: the ref map is immutable and seeded at
  boot, so a late slug means threading a mutable map through `run` and
  `reloadEntries`; the error page needs a parallel slug→error structure with
  its own sync; and the registry would still need to know a slug is "known
  but failed" to tell its error page from a 404. The chosen design is
  simpler: one ref per slug registered at boot, repair is a single `.set`.
- **`SignallingRef[IO, Option[Renderer]]` with the message stored
  elsewhere.** The error page needs the failure message, and a side-table kept
  in step with the ref is exactly the drift this design exists to remove.
  `Failed(message)` carries it on the value.
- **Recovery on the shared patch stream via an `?error-page=1` query
  param.** Drags the whole session machinery along — `conn` minting, registry
  registration, `holds`, cursor bookkeeping — for a connection that uses none
  of it, and routes the seam inside `openingPatches`. The dedicated
  `recover` stream has no session, no cursor, no `openingPatches`, and a
  bookmarking or direct-URL connection to the shared stream keeps its
  defensive `reload` path.
- **A hand-rolled inline `EventSource` on the error page.** Raw `indexOf`
  substring matching on a `"_reload":true` flag. The page uses the Datastar
  convention the live page already does (`@get` + `data-effect`), which
  deletes the script entirely.
- **`TestControl` for the anti-loop test.** It virtualizes time but cannot
  assert a causal fact — both a real sleep and a virtual sleep say "pass a
  window and hope the open completed". The `recover-open` marker is the
  causal proof the test awaits: receiving it means the stream subscribed to
  the ref under `Failed`.
