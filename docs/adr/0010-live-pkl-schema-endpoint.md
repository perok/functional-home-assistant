# ADR 0010 — The live Pkl schema endpoint + the `@fh-dashboard` authoring alias

- **Status:** Accepted (Track A: the `/system/pkl/*` HTTP endpoint; Track B: entries
  import the library through the `@fh-dashboard` project-dependency alias — both landed)
- **Date:** 2026-07-14
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

See also the design plan `docs/plan-pkl-live-endpoint-and-deps.md` (the spike
evidence + step-by-step log) and ADR [0006](0006-pkl-authoring-track.md) (Pkl as
the authoring language).

## Context

A dashboard entry pulls in two fh-owned Pkl artifacts:

- **`lib/hass.pkl`** — the hand-written HA domain schema (entity/area/floor
  classes). Static; ships with the module.
- **`lib/dump.pkl`** — the *typed* dump of THIS home's live entities/areas/floors
  (`PklDump.render(DataDump.fetch(...))`), regenerated from the live instance.
  Gitignored; per-home; changes as the home changes.

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
`SystemPkl` provider (`SystemPkl.fromDisk(dashboardsDir)` reads
`lib/{hass,dump}.pkl`; `prepareDumps` has already written the live `dump.pkl`).
An unknown name is a 404. This is the live, always-in-sync endpoint that pkl-lsp
and remote authors fetch for completion/diagnostics — the schema+dump of *that*
running home, not a checkout's stale file.

A custom `org.pkl.core.module.ModuleKeyFactory` (`SystemPkl.Factory`) can
intercept `…/system/pkl/…` URIs **by path** (host-agnostic) and resolve them from
the same `SystemPkl` provider via `ResolvedModuleKeys.virtual` — pure in-memory,
no socket — so the server never HTTP-fetches from itself. It is still threaded
onto the eval path as a **fallback** for any residual http import, but the
shipped entries no longer take it (Track B). `hasHierarchicalUris = true` lets a
served `dump.pkl`'s own `import "hass.pkl"` resolve to its `…/system/pkl/hass.pkl`
sibling; the evaluator's allowlist admits `http:` when this factory is present.

### Track B — the `@fh-dashboard` alias (the authoring surface for entries)

Entries do **not** import the library by http URL or relative path. `lib/` is a
Pkl **package** (`lib/PklProject`, name `fh-dashboard`), and the entries'
directory is a **consumer project** (`dashboards/PklProject`) that maps the alias
`@fh-dashboard` to `./lib`. An entry reads:

```pkl
amends "@fh-dashboard/entry.pkl"
import "@fh-dashboard/components.pkl" as c
import "@fh-dashboard/dump.pkl" as dump
```

`PklBuild.resolveProjectDeps` resolves that mapping **in-process and network-free**
(`ProjectDependenciesResolver` + a dummy HTTP client — a *local* dependency is
never fetched), writes the `PklProject.deps.json` lockfile once (gitignored), and
`EvaluatorBuilder.applyFromProject` makes `@fh-dashboard/…` resolve to the local
`lib/`. No relative paths, no embedded host, no self-fetch — and offline
build/test paths keep working (the resolve reads local files only).

**Package-internal modules use RELATIVE imports** (`components.pkl` →
`import "hass.pkl"`, `entry.pkl` → `import "components.pkl"`). The `@fh-dashboard`
alias is declared only in the *consumer* project, so a package member cannot (and
must not) reference its own package by alias — and it does not need to (see the
identity constraint below).

The day `lib/` is published to a registry, only the consumer `PklProject` mapping
flips (`["fh-dashboard"] = "package://…/fh-dashboard@1.0.0"`); entries do not
change. "Published + local fallback" is this *resolution mapping*, chosen per
project — not runtime failover.

## The load-bearing constraint: one URI per module

**Pkl identifies a module by its resolved URI.** Two imports of byte-identical
`hass.pkl` under two different URIs produce **two distinct module instances with
distinct, non-interchangeable classes**: a `hass.LightEntity` from one is not the
same type as one from the other, and passing it where the other is expected is a
Pkl type error. Entries pass `dump.*` entities into `components.pkl` card
factories, so `dump.pkl` and `components.pkl` MUST see the same `hass` URI.

Track B satisfies this **through the package base**: because the entry pulls both
`components.pkl` and `dump.pkl` in through `@fh-dashboard`, each module's relative
`import "hass.pkl"` resolves under the one package base
(`projectpackage://fh.local/fh-dashboard@1.0.0#/hass.pkl`) — a single URI, hence
a single set of `hass` types. `dump.pkl`'s emitted `import "hass.pkl"` is
unchanged; it just resolves under the package instead of as a file sibling. This
was spike-verified on pkl-core 0.31.1 (a `dump` entity fed to a `components` card
factory typechecks; a deliberately-split second URI errors as the classic
"expected hass#Entity, got hass#Entity").

Consequence for tests: a probe that mixes `components.pkl` with a plain
`hass.pkl`/`dump.pkl` **file** import would split the URI. Probes therefore either
go fully through `@fh-dashboard` (staged by `copyLib`/`PklFixture`) or stay purely
file-based (siblings in one `lib/`, one file URI) — never mixed.

## The watch set

`PklBuild.importSet` computes the file-watch set from `Analyzer.importGraph` with
file-only factories, keeping only `file:` modules. A shipped entry's
`@fh-dashboard/…` imports resolve as `projectpackage:`, which that analyzer cannot
resolve — so it falls back to the conservative superset (every `*.pkl` under the
dir). That is exactly what we want for the real dashboards: the whole authoring
directory is watched. `ServerApp.watchedSet` *also* adds every `*.pkl` under
`lib/` explicitly, so a card-class or schema edit always triggers a live reload
even when a precise (file-import) analysis omits the library. The generated
`lib/dump.pkl` rides along harmlessly (it is rewritten only on a live-state
re-fetch, not hand-edited; a build-time force-rerun is a separate follow-up).

This explicit `lib/` add is a deliberate seam for a future "local-dev only"
toggle: watching the library source is a checkout/dev-loop concern, irrelevant to
a deployed add-on whose library is fixed per image.

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

## Canonical host (Track A follow-up)

The endpoint literal in the `SystemPkl` route is `http://localhost:8080/system/pkl/…`.
Because the server matches by path, it only has to resolve for *external*
consumers; the follow-up unifies it on the single canonical URL from the PWA
split-horizon remote-access story (`docs/pwa-remote-access.md`), so a remote
author points that one name at their home exactly as the PWA does.
