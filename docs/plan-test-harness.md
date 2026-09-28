# Plan: one test harness, wired as production is

The server suites build a `Server` five ways, and only one of them is close to what `ServerApp`
serves. The goal is one functional harness that runs the production assembly with only the edges
stubbed, and a smaller set of tests that each earn their place.

## Where it stands

| Path | Suites | What it skips |
|---|---|---|
| direct `Server` (`LiveWorld`, one `ResumeSuite` test) | 2 | `HaFeed`, queries, auth routes, `FHError.handle`, narrowing |
| `TestServer` → `ServerApp.assemble` | every other server suite, functional, smoke | only the edges |
| pure `Renderer` / `Patches` | 29 files | nothing: the functional core |

`Server.resource` has no production caller.

## Steps

1. **`ServerApp.assemble(edges)` (its own PR).** `run`'s wiring moves into a function whose
   parameters are the edges: the HA socket, the per-user connection and login URLs, the outbound
   HTTP clients, the workspace, what the site starts with, telemetry, the watcher flags. `run`
   reads config and the environment, calls it and binds Ember. `TestServer` becomes a call to it
   with a `FakeHomeAssistant`, stub HTTP clients and a temp workspace, and drives the same composed
   `HttpApp` production serves: auth routes, error boundary and narrowing included.
2. **One client API.** `LiveWorld`'s multi-client connect, decoded events and server-side settle
   gate move onto `TestServer` (`connect`, `change`, `frame`), with state driven through
   `fake.emitFrame`, and its callers with them. `RenderCacheContentionSuite` stays on `LiveWorld`
   (see Decisions).
3. **Migrate suite by suite** (done). Per test, before porting:
   - **What regression would it catch that nothing else does?** A test pinning an internal
     (a log's shape, a cache's key) that a boundary test already covers is deleted, not ported.
   - **Overlap:** the same property asserted at two layers keeps the lowest layer that can observe
     it — pure logic in a core suite, wiring and timing at the HTTP/SSE boundary.
4. **Delete** `Server.resource` beyond what `LiveWorld` needs, with the seams only it used.
   `SharedHarness`, `recordAndPull` and `CountingRenderer` went with step 3.

## Decisions

- **Real time is the default.** `TestControl` is opt-in for suites about concurrent behaviour,
  where simulated time is what makes the interleaving reproducible.
- **Model dashboards are handed to `assemble` as the site's starting content** rather than pushed
  after boot, so a `Scene` test pays no Pkl evaluation and starts from a narrowed feed.
- **Render cost is measured on the connection loop, outside `assemble`.** `RenderCacheContentionSuite`
  counts renders through a `Renderer` subclass, which `assemble` cannot take, so it keeps
  `LiveWorld` over `Server.resource`: real connections, doorbell and pulls, without the feed,
  narrowing and auth routes, none of which renders. Rejected: a renderer factory on `Prepared`
  (a production seam for one suite), and calling `Server.pull` directly, which would assume that
  the recorder, the doorbell loop and the route's selection render nothing instead of measuring it.
  A render count that proved something else moves to what it proved: the first-connect resume is
  `Server.resumeFrom`, pinned in `CursorSuite`.
- **The session windows are a field of `ServerApp.Edges`** (`Server.SessionWindows`), which
  production fills with the defaults. Not an edge but time, and a reap test cannot wait out two
  minutes.
- **HA down is the fake's socket closing** (`TestServer.haDown`), so the page and the stream read
  the feed's own health rather than an injected constant.
- **What one pull owes is asked of a viewer** (`TestServer.viewer`): a real page load whose stream
  never opens, pulled by hand through `Server.pull`, with frames through the fake and the real
  recorder. Several frames before one pull is the slow client, deterministically. Rejected: a pure
  path over `Patches`, which still needs a copy of `pull`'s bookkeeping in the tests, the copy
  `SharedHarness` was and that had drifted (no morph merging, a cursor on an empty pull, a fresh
  render cache).
- **The source watcher is an edge** (`Edges.sourceWatcher`, a `ServerApp.SourceWatcher`): production
  wraps fs2's OS watcher, `TestServer` a fake that delivers an event only for a watched path, so
  `ts.edit` drives the reload production runs and proves the watch set covers the file. Ours and not
  fs2's `Watcher`, which is sealed. The `reloadSite` tests that assert what one reload did not do
  stay on `reloadSite` and a bare `LiveSite`: through the debounce, "nothing changed" has no
  completion to wait for.
- **One test keeps a direct `Server`.** `ResumeSuite`'s "claims the changelog's version" needs a
  recorder that has not caught up, a window the assembled server closes too fast to observe, and
  that is fiber ordering rather than time, so `TestControl` does not open it either.
