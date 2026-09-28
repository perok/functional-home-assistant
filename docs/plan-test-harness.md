# Plan: one test harness, wired as production is

The server suites build a `Server` five ways, and only one of them is close to what `ServerApp`
serves. The goal is one functional harness that runs the production assembly with only the edges
stubbed, and a smaller set of tests that each earn their place.

## Where it stands

| Path | Suites | What it skips |
|---|---|---|
| `SharedHarness`: `new Server`, drives `recordFrame` + `Patches.resume` itself | 4 | publishers, routes, feed |
| direct `Server.resource` (incl. `LiveWorld`) | ~15, ~45 call sites | `HaFeed`, queries, auth routes, `FHError.handle`, narrowing |
| `TestServer` → `ServerApp.assemble` (step 1) | functional, smoke, Pkl behaviour, `ServerAppSuite` | only the edges |
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
   gate move onto `TestServer`, with state driven through `fake.emit`.
3. **Migrate suite by suite**, the direct `Server.resource` callers first (SessionLifecycle,
   ServerRoutes, the Tap suites, SharedPass). Per test, before porting:
   - **What regression would it catch that nothing else does?** A test pinning an internal
     (a log's shape, a cache's key) that a boundary test already covers is deleted, not ported.
   - **Overlap:** the same property asserted at two layers keeps the lowest layer that can observe
     it — pure logic in a core suite, wiring and timing at the HTTP/SSE boundary.
4. **Delete** `Server.resource`, `SharedHarness`, `LiveWorld` and the seams only they used.

## Decisions

- **Real time is the default.** `TestControl` is opt-in for suites about concurrent behaviour,
  where simulated time is what makes the interleaving reproducible.
- **Model dashboards are handed to `assemble` as the site's starting content** rather than pushed
  after boot, so a `Scene` test pays no Pkl evaluation and starts from a narrowed feed.
