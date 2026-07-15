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
  the module allowlist (`setAllowedModules([… "http:" …])`).
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

Two consumers must resolve these, and they resolve imports differently:

- the **server** evaluating its own entries at startup/reload (in-process, via
  pkl-core) and the **offline** build/test paths (no live home);
- external **editors** — the `/edit` in-browser pkl-lsp subprocess and any remote
  author's Pkl tooling — which resolve imports by *actually fetching* URLs and
  cannot run fh's Java module factories.

## Decision

Two complementary mechanisms, one for each consumer class.

### Track A — the `/system/pkl/:name` HTTP endpoint (for external editors)

`Server` serves `GET /system/pkl/{hass,dump}.pkl` as `text/plain` from a
`SystemPkl` provider (`SystemPkl.fromDisk(dashboardsDir)` reads `lib/hass.pkl`
and `home/dump.pkl`; `prepareDumps` has already written the live dump).
An unknown name is a 404. This is the live, always-in-sync endpoint that pkl-lsp
and remote authors fetch for completion/diagnostics — the schema+dump of *that*
running home, not a checkout's stale file.

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
  authoring surface and keeps the http endpoint for its real job (external
  editors that must fetch).
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
- **HTTPS/cert.** The endpoint allows plain `http:` (decided). A cross-network
  deployment over `https:` needs a cert reachable by pkl-lsp — out of scope so far.
- **Publish `@fh-dashboard`.** The remote half (a `package://…` registry: network +
  checksum, unspiked) is a post-API-stability step; only the consumer mapping flips.
  The dump does not participate: it is already outside that package (`@fh-home`),
  and its `@fh-dashboard/hass.pkl` import resolves into the published artifact —
  spike-verified as the same identity `components.pkl` sees.
