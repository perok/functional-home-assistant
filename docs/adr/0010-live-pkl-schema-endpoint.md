# ADR 0010 — The live Pkl schema endpoint + the `@fh-dashboard` / `@fh-home` aliases

- **Status:** Accepted (Track A: the `/system/pkl/*` HTTP endpoint; Track B: entries
  import the library and the dump through project-dependency aliases — both landed)
- **Date:** 2026-07-15
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

See also ADR [0006](0006-pkl-authoring-track.md) (Pkl as the authoring language);
the enabling pkl-core APIs are recorded in the `pkl-core-http-intercept-and-local-deps`
memory.

Both mechanisms were spike-verified on **pkl-core 0.31.1** (no version bump):

- **Interception (A):** a `ModuleKeyFactory` registered ahead of
  `ModuleKeyFactories.http` returns `ResolvedModuleKeys.virtual(...)` for a
  `…/system/pkl/…` URI with no server running; `hasHierarchicalUris = true` makes
  the nested `import "hass.pkl"` resolve through the same factory. The only gate is
  the module allowlist (`setAllowedModules([… "http:" …])`). **Now vestigial** —
  nothing imports over http (see Use cases), and the served dump's own
  `@fh-dashboard` import could not resolve that way regardless. Retained as a
  fallback; a candidate for deletion.
- **Not possible (spiked, and it shapes the design):** `package://` forces
  `https:` — `package://localhost:8099/x@1.0.0` issues
  `GET https://localhost:8099/x@1.0.0` and a plaintext host is never contacted. And
  an http-served module cannot resolve an `@alias`: `Cannot import dependency
  because there is no project found`.
- **Local dep (B):** `ProjectDependenciesResolver(Project.loadFromPath(p),
  PackageResolver.getInstance(sm, dummyHttpClient, cacheDir), writer).resolve()`
  writes a `PklProject.deps.json` for a `"type":"local"` dep with no network and no
  pkl CLI; `EvaluatorBuilder.applyFromProject` then resolves `@fh-dashboard/…`.
  Resolving the consumer alone also covers a nested package's own deps.
- **Import analysis (B):** `Analyzer`'s `DeclaredDependencies` + `moduleCacheDir`
  slots make `projectpackage:` imports analyzable, and `graph.resolvedImports`
  maps a LOCAL dep's modules back to their real `file:` paths — the basis of the
  precise watch set.

## Context

A dashboard entry pulls in two fh-owned Pkl artifacts:

- **`lib/hass.pkl`** — the hand-written HA domain schema (entity/area/floor
  classes). Static; ships with the module.
- **`home/dump.pkl`** — the *typed* dump of THIS home's live entities/areas/floors
  (`PklDump.render(DataDump.fetch(...))`), regenerated from the live instance.
  Gitignored; per-home; changes as the home changes. It lives in its own package
  (`@fh-home`) because its lifecycle is the opposite of the library's — see Track B.

## Use cases

Four consumers. They differ only in **where `lib/` comes from** and **who writes
`home/dump.pkl`** — never in how an entry resolves anything. That is the
invariant the whole design hangs on:

> **Evaluation always runs against a fully-local project**: a consumer
> `PklProject` binding `@fh-dashboard` and `@fh-home` to packages on the same
> machine as the evaluator. A running instance is something you **sync from** and
> **push to** — never something you **import from**.

| | evaluates on | `@fh-dashboard` is | `home/dump.pkl` written by | today |
|---|---|---|---|---|
| **End user, `/edit`** | the HA server | the seeded `lib/` | `prepareDumps`, at startup | works |
| **End user, local editor** | their laptop | their copy of `lib/` | a CLI pull from the instance | needs the CLI |
| **Repo developer** | laptop, local server | the repo's `lib/`, live-edited | `prepareDumps` vs a dev HA | works |
| **Component developer** | their laptop | repo `lib/` + their own components | a CLI pull from the instance | needs CLI pull + push |

**End user, `/edit` on the server.** Edits `dashboard.pkl` in the browser;
`LspBridge` spawns pkl-lsp as a **server-side subprocess** and the client sends
absolute on-disk paths in `initialize`, so completion resolves the seeded `lib/`
and the freshly-written `home/dump.pkl` from local files, through the project.
Nothing is fetched. This is the default path and it needs no network story at all.

**End user, local editor.** Has a copy of their dashboards dir (it lives under
`/homeassistant/fh-dashboards`, exposed via the File editor / Samba), so they
already have `lib/`, the manifests, and their entries. The one thing that goes
stale is the dump — it tracks a home that keeps changing. They need to
*re-materialize* `home/dump.pkl` from the instance, which is a **file fetch**, not
an import: `GET /system/pkl/dump.pkl` → write it into `home/`. Their project then
resolves exactly as the server's does.

**Repo developer.** Runs a local server with `DASHBOARDS_DIR` pointed at the
checkout; `prepareDumps` fills `home/dump.pkl` from a dev HA, and the watcher
live-reloads edits to `components.pkl`/themes because the precise import set
resolves `@fh-dashboard` back to those files.

**Component developer.** Writes their own cards locally and a local entry that
imports both them and `@fh-home/dump.pkl`. Their instance cannot evaluate that
entry — it has never seen their components. So they evaluate **locally** and push
the **result**: the wire model `{cards, card}` is self-contained (cards carry
their Mustache templates inline), so a pushed dashboard needs nothing on the
server. Once the components are published, a normal remote package dependency
replaces the push, and they stop being special.

### Why this rules out importing from the instance

Two spikes on 0.31.1 close off the alternatives, and both cut the same way:

- **`package://` is https-only.** `package://localhost:8099/x@1.0.0` issues
  `GET https://localhost:8099/x@1.0.0` — the scheme is forced, a plaintext server
  sees nothing, and there is no knob. So an alias can never point at the add-on's
  plain-http port. Packages are also **checksum-pinned**, which is right for a
  versioned library and fatally wrong for a dump that is rewritten every start.
- **An http-served module cannot resolve an alias.** A dump fetched over http and
  imported as a module fails its own `import "@fh-dashboard/hass.pkl"` with
  `Cannot import dependency because there is no project found` — `@` imports
  resolve against an *enclosing project*, and an http module has none. Only a
  sibling `import "hass.pkl"` works there (via `hasHierarchicalUris`).

So the dump text can serve the **package** world or the **http-module** world,
never both. The package world wins: it is the one that also supports third-party
component packages, and it keeps entry text free of host literals. `PklDump`
therefore emits `import "@fh-dashboard/hass.pkl"`, and `/system/pkl/` demotes from
"a module source editors import" to "**the artifacts this instance is
authoritative for, for a client to copy locally**" — which needs no cert, no
checksums, and works over plain http.

That also gives the endpoint's `ETag` a real consumer at last: a CLI pull is a
conditional `GET`, so an unchanged home costs a `304` instead of ~450KB.

## Decision

Two complementary mechanisms, one for each consumer class.

### Track A — the `/system/pkl/:name` HTTP endpoint (the instance's mirror)

`Server` serves `GET /system/pkl/{hass,dump}.pkl` as `text/plain` from a
`SystemPkl` provider (`SystemPkl.fromDisk(dashboardsDir)` reads `lib/hass.pkl`
and `home/dump.pkl`; `prepareDumps` has already written the live dump).
An unknown name is a 404. This is the live, always-in-sync mirror of what *that*
running home is authoritative for, so a client can copy it locally instead of
working against a checkout's stale file.

**It is a file-download API, not a module source, and it has no consumer yet.**
pkl-lsp does not fetch it — `LspBridge` runs pkl-lsp server-side against on-disk
paths (see Use cases) — and no `.pkl` file imports by URL since entries moved to
the aliases. Its consumer is the not-yet-built CLI pull. It is kept, rather than
deleted, because it is the whole remote-author story: a laptop cannot import from
the instance (https-only packages, no project for http modules), so *fetching the
file* is the only mechanism left, and this is it.

**Caching: `no-cache` + an `ETag`, and `no-cache` is the load-bearing half.**
`dump.pkl` is live per-home data under a URL that never changes, so anything that
stores it (a browser, a proxy on the split-horizon remote path) could hand an
author completions for devices they no longer own. `no-cache` forbids reuse
without revalidation — never silently stale. The `ETag` serves no *current*
consumer: pkl does no conditional requests at all (pkl-core 0.31.1 has no
`If-None-Match`/`ETag`/`Cache-Control` handling anywhere — verified against the
jar; its only caching is the per-evaluator in-memory module cache keyed by
resolved URI). It is there for the consumers that do revalidate — editor/browser
JS, remote tooling asking "did this home's entity set change?" without pulling a
~450KB dump. Hashing per request is trivial next to serving the body, and this
route is hit at editor-session start, never on the live hot path.

A custom `org.pkl.core.module.ModuleKeyFactory` (`SystemPkl.Factory`) can
intercept `…/system/pkl/…` URIs **by path** (host-agnostic) and resolve them from
the same `SystemPkl` provider via `ResolvedModuleKeys.virtual` — pure in-memory,
no socket — so the server never HTTP-fetches from itself. It is still threaded
onto the eval path as a **fallback** for any residual http import, but the
shipped entries no longer take it (Track B). `hasHierarchicalUris = true` lets a
served `dump.pkl`'s own `import "hass.pkl"` resolve to its `…/system/pkl/hass.pkl`
sibling; the evaluator's allowlist admits `http:` when this factory is present.

### Track B — the `@fh-dashboard` / `@fh-home` aliases (the authoring surface)

Entries do **not** import the library by http URL or relative path. There are two
Pkl **packages**, split by lifecycle, and the entries' directory is a **consumer
project** (`dashboards/PklProject`) mapping both aliases:

- **`@fh-dashboard` → `./lib`** (`lib/PklProject`, name `fh-dashboard`) — the
  shared authoring library: schema, card classes, themes, tokens, entry base.
  Versionable; one day publishable to a registry.
- **`@fh-home` → `./home`** (`home/PklProject`, name `fh-home`) — THIS home's
  live data: just the generated `dump.pkl`. Per-home, regenerated from the live
  instance, and **never publishable** — decoupling the dump from the live home is
  precisely what this whole ADR exists to avoid. It depends on `@fh-dashboard`
  for the schema.

An entry reads:

```pkl
amends "@fh-dashboard/entry.pkl"
import "@fh-dashboard/components.pkl" as c
import "@fh-home/dump.pkl" as dump
```

The split is what makes the publish story true rather than aspirational: with the
dump inside `@fh-dashboard`, publishing would have forced it out at exactly the
moment the schema became remote — the riskiest possible time to discover the
identity constraint above. Splitting now costs one manifest and proves the
arrangement works while everything is still local. `home/dump.pkl` also must not
sit at the dashboards top level, where `discoverEntries` would scan it as an
entry (`DashboardBuild.DumpPath` owns the location).

`PklBuild.resolveProjectDeps` resolves the mapping **in-process and network-free**
(`ProjectDependenciesResolver` + a dummy HTTP client — a *local* dependency is
never fetched), writes the `PklProject.deps.json` lockfile once (gitignored), and
`EvaluatorBuilder.applyFromProject` makes the aliases resolve to the local `lib/`
and `home/`. No relative paths, no embedded host, no self-fetch — and offline
build/test paths keep working (the resolve reads local files only). Resolving the
**consumer** project is enough: `@fh-home`'s own `@fh-dashboard` dependency
resolves with it, and only `dashboards/PklProject.deps.json` is written (verified
on 0.31.1) — no per-package pass.

**A module imports its OWN package's modules relatively, and another package's by
alias.** `components.pkl` → `import "hass.pkl"`, `entry.pkl` →
`import "components.pkl"`: an alias is declared by a *consumer* project, so a
package member cannot reference its own package by alias — and need not, since the
relative import already lands on the package base. `dump.pkl` is the other case:
it is a member of `@fh-home` reaching into a *different* package, so it writes
`import "@fh-dashboard/hass.pkl"` — declared as a dependency in `home/PklProject`,
and resolving to the same base `components.pkl`'s relative import does.

The day `lib/` is published to a registry, only the consumer `PklProject` mapping
flips (`["fh-dashboard"] = "package://…/fh-dashboard@1.0.0"`); entries do not
change, and neither does the dump's alias import. "Published + local fallback" is
this *resolution mapping*, chosen per project — not runtime failover.

## The load-bearing constraint: module identity

**Pkl identifies a module by the artifact an import RESOLVES to, not by the
import URI you wrote.** Two *files* holding byte-identical `hass.pkl` produce two
distinct module instances with distinct, non-interchangeable classes: a
`hass.LightEntity` from one is not the same type as one from the other, and
passing it where the other is expected is a Pkl type error. Entries pass `dump.*`
entities into `components.pkl` card factories, so the dump and the library MUST
reach the same `hass` **artifact**.

Spike-verified on pkl-core 0.31.1, feeding a `dump` entity to a card factory
whose parameter is `hass.Entity`:

| how the dump reaches the schema | result |
|---|---|
| `@fh-dashboard/hass.pkl` (alias, from its own package) | typechecks |
| `../lib/hass.pkl` (relative escape — a *different URI string*, same file) | typechecks |
| its own copied `home/hass.pkl` (a genuinely separate file) | **type error** |

So multiplying URIs onto one file is harmless; **duplicating the file is what
breaks**. (An earlier version of this ADR stated the rule as "one URI per
module", which predicts the first two rows fail. They don't. The failure mode is
real but its trigger is artifact duplication.) The error, when it does fire, is
the notoriously confusing `Expected value of type "hass#Entity", but got type
"hass#LightEntity"` — same-looking names, different modules.

Both packages satisfy this through **one `@fh-dashboard` package base**:
`components.pkl`'s package-internal `import "hass.pkl"` and `dump.pkl`'s
`import "@fh-dashboard/hass.pkl"` both land on
`projectpackage://fh.local/fh-dashboard@1.0.0#/hass.pkl` — one artifact, one set
of `hass` types. This survives publication: once `@fh-dashboard` is a registry
package, the dump's alias import resolves into the same package-cache artifact
`components.pkl` uses.

Consequence for tests: a probe must not reach the schema through a *second copy*
of it. Probes either go through the aliases (staged by `copyLib`/`PklFixture`) or
stay purely file-based against one `lib/` — never a mix that stages a duplicate.
The identity is guarded implicitly but non-vacuously: `SmokeDashboard` and the
`PklBuildSuite` fixtures pass `dump.entities.*` into `c.entityCard(...)`, which
is exactly the shape row 3 above proves is sensitive.

## The watch set

`PklBuild.importSet` computes the file-watch set from `Analyzer.importGraph`, and
**the `@fh-dashboard` alias resolves there too** — so the set is precise, with no
`lib/` special-casing anywhere.

Two of the `Analyzer` constructor's slots do it: the `moduleCacheDir` and the
`DeclaredDependencies` (`project.getDependencies` — the same `resolveProjectDeps`
output the evaluator gets). With those supplied and the `projectpackage`/`pkg`
factories registered, an `import "@fh-dashboard/components.pkl"` analyzes as
`projectpackage://fh.local/fh-dashboard@1.0.0#/components.pkl` and — because
`@fh-dashboard` is a **local** dependency — `graph.resolvedImports` maps it
straight back to the real `file:…/lib/components.pkl`. The existing `file:` filter
then picks up exactly the library modules the entry imports, and nothing else.
(Verified on pkl-core 0.31.1; `PklBuildSuite` pins it with a probe that imports
`@fh-dashboard/hass.pkl` and asserts an unimported sibling stays *out* of the set
— the assertion that separates "precise" from the superset, since the superset
contains the aliased module too and would pass vacuously.)

So editing a card class or the schema hot-reloads every entry that imports it, by
the ordinary import mechanism. `ServerApp.watchedSet` is just
`imports ++ entry files`; the earlier explicit "add every `*.pkl` under `lib/`"
is gone, and with it the "local-dev only toggle" that add was meant to seam —
watching follows imports, so a deployed add-on whose library never changes simply
never fires a reload.

The conservative superset (every `*.pkl` under the dir) remains as the fallback
for an analysis that throws, which is now an unexpected path rather than the
normal one for shipped entries.

## Alternatives rejected

- **Keep relative file imports for entries.** Works only in a checkout with a
  freshly-written dump next to the entry; and it is brittle (the base module's
  `card: c.Node` type must match the entry's `c` — a plain-file base amended by an
  `@fh-dashboard`-imported `c` would be two `components` URIs). `@fh-dashboard`
  gives one uniform, relocatable text.
- **Import the library over the http URL (Track A) from entries.** The prototype
  did this; it works, but embeds a host literal in every entry and puts the
  interception factory on the hot eval path. `@fh-dashboard` is the cleaner
  authoring surface. It is also now a dead end: an http-served module cannot
  resolve an `@alias` at all (no enclosing project), so a library reached this way
  could never itself use the aliases.
- **Point an alias at the instance (`package://<home>:8123/…`).** The natural
  guess for the remote author, and impossible: `package://` forces `https:` (a
  plaintext host is never contacted), and packages are checksum-pinned — right for
  a versioned library, fatal for a per-start dump. The CLI pull replaces it.
- **Write `@fh-dashboard/hass.pkl` inside `components.pkl`.** A package member
  referencing its own alias: the alias is not declared in the package's project,
  so it fails standalone, and in the best case resolves to the identical URI a
  relative import already yields. Relative is strictly better.
- **Fetch from self over the socket at startup.** A bootstrap cycle (HTTP server
  not yet up) and a pointless loopback; both in-process resolvers avoid it.
- **A published `hass.pkl`/`dump.pkl` package.** Decouples the schema+dump from the
  *live* home — the whole point is that they track the running instance, which is
  why they are served (Track A) and mapped locally (Track B), not versioned.
- **Keep the dump inside `@fh-dashboard`.** Where it started. It works only while
  the library is local: a published package can never carry per-home data, so the
  dump would have to move out at exactly the moment the schema went remote — the
  worst time to first exercise the identity constraint. `@fh-home` costs one
  manifest and settles it now.
- **Put `dump.pkl` beside the entries as a plain file.** No third manifest, and an
  entry's `import "home/dump.pkl"` would resolve. But it re-embeds a relative path
  in every entry — the thing Track B removed — and a top-level `dump.pkl` would be
  scanned as an entry. The alias keeps entry text uniform and relocatable.

## Open follow-ups

- **Canonical host.** The endpoint literal is `http://localhost:8080/system/pkl/…`.
  Because the server matches by path, it only has to resolve for *external*
  consumers; unify it on the single canonical URL from the PWA split-horizon
  remote-access story (`docs/pwa-remote-access.md`), so a remote author points that
  one name at their home exactly as the PWA does.
- **Force-rerun the dump.** `dump.pkl` is deliberately not watched (regenerated
  from HA state, not hand-edited). There is no way today to make the server
  re-fetch + re-render it on demand (e.g. after adding a device) short of a
  restart — add a build-time force-rerun path (endpoint or signal that re-runs
  `prepareDumps` and re-evaluates entries).
- **The `fh` CLI (`pull` / `push`).** The one missing piece for two of the four use
  cases, and the only consumer the `/system/pkl` endpoint has.
  - `pull`: conditional `GET /system/pkl/dump.pkl` (send `If-None-Match`, honour
    the `304`) → write `home/dump.pkl`. Plain http, no cert, no checksums.
  - `push`: evaluate locally, `POST` the `{cards, card}` JSON, hot-swap a
    renderer. Two known wrinkles: `Server.renderers` is a `Map[String,
    SignallingRef]` fixed at construction, so a push cannot mint a NEW slug —
    it must target an existing one (a seeded `preview` entry, or restructure to a
    `Ref[Map[…]]`); and the file watcher's reconcile would clobber a pushed slug
    on the next edit unless that slug is excluded. Push is a *write*: it must ride
    HA-authenticated ingress, never the documented-as-unauthenticated direct port.
- **Remote dependencies do not resolve today.** Two blockers for the published
  world, both small but deliberate: `resolveProjectDeps` hardcodes
  `HttpClient.dummyClient()` (a remote dep dies with `Dummy HTTP client cannot
  send request`), and it only resolves when the lockfile is *absent* — so a user
  adding a dependency to their `PklProject` would keep getting the stale
  `PklProject.deps.json`. Fixing both means deciding what startup does when the
  network is down or a checksum moved.
- **How a published package is actually addressed.** UNVERIFIED, and worth a spike
  before promising it: package metadata is fetched from `https://<authority><path>@<version>`
  (observed), so `package://github.com/<org>/<repo>/<pkg>@1.0.0` would `GET`
  `https://github.com/<org>/<repo>/<pkg>@1.0.0`, which GitHub will not serve.
  "Publish to GitHub and refer to the project path" therefore probably needs a
  metadata host (GitHub Pages, a redirect, or a registry) rather than the repo URL.
- **HTTPS/cert.** The endpoint allows plain `http:` (decided). Under the CLI-pull
  model this is no longer load-bearing: a file download needs no cert, and pkl-lsp
  never crosses the network. It returns only if something must *import* over the
  wire.
- **Publish `@fh-dashboard`.** The remote half (a `package://…` registry: network +
  checksum, unspiked) is a post-API-stability step; only the consumer mapping flips.
  The dump does not participate: it is already outside that package (`@fh-home`),
  and its `@fh-dashboard/hass.pkl` import resolves into the published artifact —
  spike-verified as the same identity `components.pkl` sees.
