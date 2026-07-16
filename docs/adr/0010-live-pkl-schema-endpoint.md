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
- **Package URLs + rewrites (spiked end-to-end):** `package://` maps to an
  `https:` outbound request (`package://fh.local/x@1.0.0` →
  `GET https://fh.local/x@1.0.0`), but `HttpClient.Builder.addRewrite(from, to)`
  retargets it. The **full remote-package flow works over plain http**: a
  hand-rolled metadata JSON (`{name, packageUri, version, packageZipUrl,
  packageZipChecksums.sha256, dependencies}`) plus a zip with the modules at its
  root, served by a plaintext local server, resolves (`"type":"remote"` with the
  sha256 pinned in `PklProject.deps.json`) and evaluates. https is not a floor
  anywhere in the chain. Laptop-side (pkl CLI / pkl-lsp) the same rewrite is the
  documented `evaluatorSettings.http.rewrites` air-gap mechanism, untested here
  (no CLI in this environment).
- **Absolute-path local dep (spiked):** a consumer `PklProject` may declare
  `["fh-dashboard"] = import("file:///opt/…/lib/PklProject")` — a dependency
  *outside* the project dir. It resolves and evaluates; `deps.json` records the
  dep **relativized** against the project dir (`"path": "../opt/…"`), so the
  lockfile is machine-specific and must be regenerated wherever the layout
  differs.
- **An http-served module cannot resolve an `@alias`** (spiked): `Cannot import
  dependency because there is no project found`. `@` imports resolve against an
  enclosing project; an http module has none.
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
| **End user, `/edit`** | the HA server | the bundled lib, as a version-pinned package from the persistent cache | `prepareDumps`, at startup | works |
| **End user, local editor** | their laptop (git copy, or no checkout at all) | their checkout's `lib/`, or the same versioned package fetched from the instance (`/system/pkl/packages/`) | a CLI pull from the instance | works (a `pull` CLI would remove the manual steps) |
| **Repo developer** | laptop, local server | the repo's `lib/`, live-edited | `prepareDumps` vs a dev HA | works |
| **Component developer** | their laptop | repo `lib/` + their own components | a CLI pull from the instance | server side works; needs the CLI |

**End user, `/edit` on the server.** Edits `dashboard.pkl` in the browser;
`LspBridge` spawns pkl-lsp as a **server-side subprocess** and the client sends
absolute on-disk paths in `initialize`, so completion resolves the library (from
the persistent package cache — `moduleCacheDir` is declared IN the generated
`.fh/base.pkl` the user's `PklProject` amends, so pkl-lsp finds it with no
extra configuration) and the
freshly-written `home/dump.pkl`, through the project. Nothing is fetched. This is
the default path and it needs no network story at all.

**End user, local editor.** Their own workspace — their entries and manifests —
evaluated **locally**, with completion. (Editing the instance's files in place
is not a supported laptop mode; live editing on the instance is what `/edit` is
for.) No checkout needed: the `fh` script (checked into the repo at
`scripts/fh`, installed with one curl from GitHub raw) wires a directory to
the instance —
`fh init` writes the manifests, and BOTH names resolve as packages from the
instance via `/system/pkl/packages/`: `@fh-dashboard` at the user's pin, and
`@fh-home` as a *content-versioned* dump snapshot (see "resolved by
content-derived versions") that `fh pull` re-pins. Resolution and push
evaluation run inside the script (in-process pkl-core); the workspace is a
plain pkl project, so stock tooling — pkl-lsp completion, the `pkl` CLI —
works on it identically, offline once cached. `UseCaseSuite` drives the real
script + the real pkl CLI end-to-end. (A git copy of a full checkout still
works identically via path-form manifests.)

**Repo developer.** Runs a local server with `DASHBOARDS_DIR` pointed at the
checkout; `prepareDumps` fills `home/dump.pkl` from a dev HA, and the watcher
live-reloads edits to `components.pkl`/themes because the precise import set
resolves `@fh-dashboard` back to those files.

**Component developer.** Writes their own cards locally and a local entry that
imports both them and `@fh-home/dump.pkl`. Their instance cannot evaluate that
entry — it has never seen their components. So they evaluate **locally** and push
the **result** to `POST /system/push/:slug`: the wire model `{cards, card}` is
self-contained (cards carry their Mustache templates inline), so a pushed
dashboard needs nothing on the server. Push mints the slug at runtime and is
ephemeral (nothing is written to disk; a restart returns the instance to its
on-disk entries). The push body is validated exactly as an evaluated entry is —
same `DashboardBuild.decode` — because a pushing developer reads no server log,
so an unknown card comes back as a `400` naming it.

For their cards to exist at all, the entry must name their module in
`componentModules` (ADR 0006, decision 7): Pkl cannot infer it, since
`reflect.Module.imports` yields URIs as plain strings and there is no
reflect-by-string to walk them back into modules.

Once the components are published — a GitHub release, referenced as
`package://pkg.pkl-lang.org/github.com/<owner>/<repo>/<release>` (see
follow-ups) — a normal remote package dependency in the consumer's `PklProject`
replaces the push, and they stop being special: any user adds the dep next to
`@fh-dashboard`, names the module in `componentModules`, and uses the cards.
This works on a pure-`/edit` instance: the server's resolver fetches real
remote packages, honoring the manifest's own `http.rewrites` (see Track B).

### The dump vs. checksums — resolved by content-derived versions

The tension is **checksums, not transport**. Serving packages from the instance
over plain http is entirely possible — `HttpClient.addRewrite` retargets the
`https:` outbound request a `package://` URI produces, and a rewrite to
`http://…` reached a plaintext server in a spike. Transport is not the obstacle.

The obstacle is that **a package version is checksum-pinned by design**.
Resolution records the sha256 into `PklProject.deps.json` and verifies it on
every later use. That is exactly right for a versioned library and exactly
wrong for a file rewritten on every start **under an unchanged version**: the
pin either breaks immediately or has to be re-minted per fetch, at which point
it is not a pin.

Spiked (0.31.1), serving changed bytes under an unchanged version: the failure
mode is not even a checksum error — it is **silent staleness**. The package
cache is keyed by `name@version` and wins over the network at every layer:
eval with the old lockfile serves the old dump (expected), but so does a **full
re-resolve** — `ProjectDependenciesResolver` satisfies the metadata lookup from
the cache too, so the regenerated `deps.json` re-pins the OLD checksum without
ever contacting the instance. Nor is re-pinning the escape (spiked): the
manifest IS authoritative — a dependency's declared `checksums { sha256 = … }`
is verified at resolve and written into the lockfile — but against a warm
cache holding old bytes, a bumped pin under the same version **fails the
resolve outright** (verified from the cache, zero requests, no refetch); only
evicting the cache entry heals it, and every tool sharing the cache (pkl-lsp)
errors until then.

**The resolution: derive the version from the content** —
`package://fh.invalid/fh-home@1.0.0-g<hash-of-zip>`. The "live artifact"
becomes a sequence of immutable snapshots: each version's bytes never change,
so checksums stay honest by construction; a structural change mints a NEW
cache entry (no eviction, no staleness, pkl-lsp coherent at every moment); an
unchanged home re-derives the same version (the zip is deterministic). The
laptop's `fh pull` then stops copying files: it fetches the current
dump-package metadata and rewrites the `@fh-home` pin (`uri` + `checksums`
together — integrity declared, not trust-on-first-use) in the laptop's
manifest, and `PklBuild.staleLockfile` re-resolves from there. Unchanged home →
same version → the bump is a no-op.

The package form also wins on module identity, where the alternatives lose: a
dump fetched over http as a *module* cannot resolve its own
`import "@fh-dashboard/hass.pkl"` (`Cannot import dependency because there is
no project found`), and `modulepath:` is local evaluator configuration — the
file pull with extra steps. A package's *metadata* declares its dependencies,
so the dump package carries its `@fh-dashboard` pin and resolves `hass.pkl`
onto the same cached artifact the entry's alias uses. `PklDump` keeps emitting
`import "@fh-dashboard/hass.pkl"` unchanged.

This is implemented end to end: `DumpPackage` builds + seeds the snapshot on
every dump write, and the `fh` script's `init`/`pull` write the pins (see Track
A). The `/system/pkl/` **file-download API** stays alongside it — the dump as
plain text for humans and debugging, with the `ETag` making an unchanged home a
`304` — but the packaged pin is the supported laptop mechanism.

## Decision

Two complementary mechanisms, one for each consumer class.

### Track A — the `/system/pkl/:name` HTTP endpoint (the instance's mirror)

`Server` serves `GET /system/pkl/{hass,dump}.pkl` as `text/plain` from a
`SystemPkl` provider (`SystemPkl.fromDisk(dashboardsDir)` reads `lib/hass.pkl`
and `home/dump.pkl`; `prepareDumps` has already written the live dump).
An unknown name is a 404. This is the live, always-in-sync mirror of what *that*
running home is authoritative for, so a client can copy it locally instead of
working against a checkout's stale file. (On a package-form add-on workspace
there is no `lib/`, so `hass.pkl` 404s there — harmless, because nothing needs
it over http anymore: the schema travels inside the lib package below, and the
dump's `@fh-dashboard/hass.pkl` import resolves against the puller's own pin.)

**`GET /system/pkl/packages/<name>@<version>[.zip]`** serves the instance's
resolved packages: the metadata JSON at the bare name, the module zip at
`.zip` — exactly pkl's remote-package protocol, straight from the same
persistent cache the instance evaluates from (`SystemPkl.packageArtifact`; the
artifact name is shape-checked so it can never index outside the cache, and the
route is name-agnostic — the lib AND the dump snapshots travel through it). A
laptop workspace with no repo checkout pins
`package://fh.invalid/fh-dashboard@<v>` and maps it here with one
`evaluatorSettings.http.rewrites` line (`https://fh.invalid/` →
`http://<home>:8080/system/pkl/packages/`), landing on the same sha256-pinned
artifacts the instance runs — checksums stay honest because a package version
is immutable (the dump joins under a content-derived version; see above:
`DumpPackage` builds + seeds `fh-home@1.0.0-g<hash>` into the cache on every
dump write). The cache serves every version it holds, so a laptop pinned to an
older lib — or an older dump snapshot — still resolves. No cache headers: pkl
fetches per resolve, and a proxy-cached zip would turn the dev-image drift case
into a confusing stale-checksum failure. `UseCaseSuite` pins the flow end-to-end
— pkl's real resolver + evaluator over a real socket against this route.

**`GET /system/pkl/packages`** (no artifact) is the discovery index: the
current version + metadata sha256 of both packages — exactly what a pull pins.

Its client is the `fh` script (`init` / `pull` / `push` / `update`): a
self-contained **scala-cli script** (Typelevel toolkit + decline) checked into
the repo at `scripts/fh`, whose distribution channel is the GitHub repo itself
(`curl -fsSLo fh https://raw.githubusercontent.com/perok/functional-home-assistant/main/scripts/fh && chmod +x fh`).
`fh update` re-fetches that URL and replaces the local copy when the sha256
differs, keeping the previous copy as `fh.backup.<date>` (the user-file
convention) — so the checked-in file on `main` is the single authoritative
source and installed copies self-heal toward it. It is deliberately a script
and not a jar subcommand: after the content-versioned dump design, the laptop
side is *only* fetch-and-write (`init`/`pull` = fetch the index, write
`.fh/base.pkl` + seed `PklProject`, resolve dependencies) plus one evaluation
(`push`). Both run **in-process on pkl-core** (`ProjectDependenciesResolver`,
then `ValueRenderers.json` — the *same call* the instance's backend renders
its wire JSON with, so pushed JSON matches by construction; the e2e test
drives the real script through the real route). Stock pkl tooling still works
on the workspace — pkl-lsp completion is the point of having one, and the
`pkl` CLI evaluates it identically — but the script requires none of it.
Laptop dependencies: scala-cli alone (it runs the script and fetches the
pinned toolkit + pkl-core), which keeps the setup cross-platform for free.
The laptop workspace mirrors the add-on's ownership
split: `.fh/base.pkl` machine-owned (instance URL rewrite, `.fh/cache`, the
checksummed `@fh-home` pin — uri + checksums always written together),
`PklProject` the user's from `init` on (their `@fh-dashboard` pin; the base's
`@fh-dashboard` default is deliberately checksum-free, since a user override
would inherit a stale checksum from the amended-over default).

**The `.pkl` routes are a file-download API, not a module source.** pkl-lsp
does not fetch them — `LspBridge` runs pkl-lsp server-side against on-disk
paths (see Use cases) — and no `.pkl` file imports by URL since entries moved
to the aliases. The dump route stays for humans and debugging (the packaged
snapshot is the supported laptop mechanism). The packages route is different in
kind: it IS a module source, consumed by pkl's own resolver — which is why only
immutable, versioned artifacts are served through it.

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

`PklBuild.resolveProjectDeps` resolves the mapping **in-process**, writes the
`PklProject.deps.json` lockfile (gitignored; re-resolved whenever a `PklProject`
outdates it), and `EvaluatorBuilder.applyFromProject` makes the aliases resolve
to the local `lib/` and `home/`. The network is touched only for a REMOTE
dependency not already in the package cache: local deps read files, a cached
remote version satisfies the resolver without a request (the client is lazy and
is never built then — offline build/test paths and add-on boots keep working),
and an uncached remote dep (a published third-party card package, a bumped lib
pin) is fetched for real through a client derived from the manifest's own
`evaluatorSettings.http.rewrites` — the documented air-gap mechanism, and the
same mapping the pkl CLI would honor, so one manifest serves both worlds
(spike-verified on 0.31.1; `applyFromProject` applies the same settings on the
eval side by itself). A failed fetch propagates as the entry's build error with
pkl's own message naming the package, and resolve-before-write keeps the
previous lockfile intact. No relative paths, no embedded host, no self-fetch.
Resolving the **consumer** project is enough: `@fh-home`'s own `@fh-dashboard`
dependency resolves with it, and only `dashboards/PklProject.deps.json` is
written (verified on 0.31.1) — no per-package pass.

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
this *resolution mapping*, chosen per project — not runtime failover. The add-on
workspace already lives on the package side of that flip (below); the repo
checkout keeps the local-path mapping, which is what makes the repo developer's
live-edit watch set work.

### The add-on workspace — the lib as a decoupled, version-pinned package

On a Home Assistant install, the library never enters the user's directory. The
lib **versions independently of the runtime** (its version is `lib/PklProject`'s
own `version`; the authoring layer is where churn lives by design — the runtime
is a stable renderer of the wire model), and the user's workspace depends on it
as `package://fh.invalid/fh-dashboard@<version>`, resolved from a **persistent
package cache** under `/data/pkl-cache` that survives image upgrades.

`AddonBootstrap` (run by the server at startup when `FH_BUNDLED_LIB` /
`FH_SEED_DIR` / `FH_PKL_CACHE_DIR` are set — `run.sh` only exports the paths)
does, idempotently:

1. **Seed the cache** (`LibPackage`): packages the image's `/opt/fh/lib` into
   the two-file resolved-package layout
   `<cache>/package-2/fh.invalid/fh-dashboard@<v>/fh-dashboard@<v>.{json,zip}`
   (deterministic zip — sorted entries, fixed timestamps — so the sha256 doubles
   as a drift detector; lib bytes changing under an unchanged version is logged
   as a WARNING and overwritten, since the instance must run what it ships).
   Spiked (0.31.1): a warm cache satisfies BOTH `ProjectDependenciesResolver`
   and evaluation against `HttpClient.dummyClient()` — fresh and offline
   installs resolve with no network and no interception.
2. **Generate the manifests**, split machine-owned from user-owned along an
   `amends` chain (spike-verified on 0.31.1: a `PklProject` can amend a local
   base module; the child inherits `dependencies` and `evaluatorSettings`, and
   its own mapping entries override the base's):
   - `.fh/base.pkl` — **machine-owned, refreshed every start**: `moduleCacheDir`
     (so pkl-lsp and any pkl tool resolve from the same cache), the `@fh-home`
     binding, and a *default* `@fh-dashboard` pin at the bundled version.
     Add-on internals can change here without merge heuristics or backups.
   - `PklProject` — seeded once (`amends ".fh/base.pkl"` + the pin at the
     then-bundled version), then the **user's file, never rewritten**. The
     user's pin overrides the base's default — so it is the ONE place the pin
     lives; deleting the entry opts into tracking the bundled version.
     Third-party packages are declared here.
   - `home/PklProject` — **machine-owned** like the `dump.pkl` beside it,
     regenerated every start with its `@fh-dashboard` pin synced (textually)
     from the user's manifest, killing the two-place-pin-bump footgun that the
     module-identity constraint would otherwise turn into a type error.
3. **Migrate old installs**: a workspace-resident `lib/` is renamed to
   `lib.backup.<date>` (anything user-visible we remove or replace is renamed to
   a dated backup, **never deleted**); any machine-era consumer manifest
   (recognized by amending `pkl:Project` directly — both the copy-if-empty
   path-form and the interim single-file package form) is backed up and
   rewritten to the amends-the-base shape, **preserving a pin it carried**; a
   pre-machine-form `home/PklProject` gets one dated backup on the way over;
   and the lockfiles are deleted.
4. **Seed starter entries** (`/opt/dashboards-seed`, entries only — no
   manifests, no lib) only into a workspace with no top-level `*.pkl`.

`PklProject.deps.json` is no longer resolve-once: `PklBuild` re-resolves
whenever a `PklProject` is newer than the lockfile (and boot deletes it
outright), so the pin in the manifest is always what evaluates.

The decoupling is the point: a **runtime** upgrade never moves the user's pin —
the new image seeds its bundled lib version into the cache *alongside* the old
one, and the user's dashboards keep evaluating against the version they pinned.
A **lib** upgrade is the user's deliberate pin bump — one line in *their*
`PklProject` (`/edit` today, a future `fh sync`); the home manifest follows at
the next start. Pin-in-user-file is then correct semantics rather than a
stranding hazard, and the instance and the laptop become symmetric: the same
`package://fh.invalid/fh-dashboard@<v>` pin resolves from the local cache on the
instance and over an `http.rewrites` mapping toward `/system/pkl/packages/` on
a laptop (below). What the split demands in exchange
is a wire-format **compatibility contract** between independently-shipping
artifacts — see follow-ups.

`AddonBootstrapSuite` pins this whole contract: lib-free workspace, offline
evaluation from the pre-seeded cache, module identity under the package form
(the dump's remote-dep `@fh-dashboard` and the consumer's land on ONE cached
artifact), dated-backup migration, pin-bump sync into the home manifest, quiet
second boot, loud drift.

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
`projectpackage://fh.invalid/fh-dashboard@1.0.0#/hass.pkl` — one artifact, one set
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
`projectpackage://fh.invalid/fh-dashboard@1.0.0#/components.pkl` and — because
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

## Refreshing the dump while running

`dump.pkl` is deliberately **outside** the watch set above: it changes when the
*home* changes (the HA registry), not when an author edits — so it gets its own
refresh path instead of a file watch. Both triggers funnel into one
validate-then-swap flow (`fh.view.build.DumpRefresh`, serialized by a mutex in
`ServerApp`); the source file watcher stays as-is and the two compose — a swap
simply ends in the same `reloadEntries` hot-swap the watcher uses.

**Triggers.**

- **Registry events** (`ServerApp.watchRegistryEvents`): a raw WS subscription
  (`HomeAssistantApi.rawEvents` — the typed `Event` decoder fits only
  `state_changed`, so the subscription seam yields the undecoded `event` JSON)
  to `entity/device/area/floor_registry_updated` plus `component_loaded`
  (integrations set up at runtime; also the only signal for YAML-defined
  entities without a `unique_id`, which never touch a registry). Debounced 5s —
  registry changes come in bursts. Toggleable: add-on option `watch_registry`
  (default on) → `FH_WATCH_REGISTRY`.
- **On demand**: `POST /system/dump/refresh` (the `/edit` editor's
  "refresh dump" button). Unauthenticated with the same rationale and caveat as
  `/system/push` — when auth lands for the direct port it must cover this route.

**The flow.** Fetch + render the dump; byte-identical → no-op. Otherwise the
whole workspace is copied to a temp dir (lockfiles dropped so dependencies
re-resolve; the package cache is not copied — the add-on's is an absolute path
and a repo checkout binds `@fh-dashboard` locally), the new dump written there,
and every entry evaluated against it. An entry failing under the new dump
blocks the swap **only if it builds under the current one** — a dashboard the
user has mid-edit must not veto registry changes forever. On green: the old
dump is renamed to `dump.pkl.backup.<date>` (the shared backup convention —
machine-regenerated, but a swap is destructive of live data an entry may still
be pinned to, and the user asked for the trail), the new dump written, its
content-versioned package seeded (`DumpPackage`), and the renderers hot-swap.
On rejection nothing on disk moves and the server warns (log +
per-dashboard errors in the endpoint response).

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
- **Point `@fh-home` at the instance as a package (`package://<home>/…`).**
  Technically possible — `addRewrite` retargets the `https:` request to the
  add-on's plain http port — but wrong for the dump on checksum grounds (above),
  and it would make every consumer configure a rewrite to read a file. The CLI
  pull is the same bytes with none of the ceremony. Serving `@fh-dashboard` this
  way is different: the library IS a versioned artifact, so a version-pinned
  package keeps the checksum honest — that is exactly the implemented add-on
  design (see Decision), and the planned `/system/pkl/packages/` endpoint
  extends it to the git-copy laptop (see follow-ups).
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

- **The wire-format compatibility contract.** The price of lib/runtime
  decoupling: independently-shipping artifacts need, at minimum, the runtime
  rejecting output from a lib newer than it understands with a clear error — a
  schema-version stamp checked in `Dashboard.validate`.
- **Canonical host.** The endpoint literal is `http://localhost:8080/system/pkl/…`.
  Because the server matches by path, it only has to resolve for *external*
  consumers; unify it on the single canonical URL from the PWA split-horizon
  remote-access story (`docs/pwa-remote-access.md`), so a remote author points that
  one name at their home exactly as the PWA does.
- **WS reconnect.** The hand-written WS layer (`HAWSApiLowLevel`) never
  reconnects, so an HA core restart silently kills every subscription — the
  state feed *and* the registry watcher above — until the add-on restarts.
  Pre-existing, but the registry watcher raises the stakes: a device added
  right after an HA restart is exactly the event it exists to catch.
- **`fh` script leftovers.** `init`/`pull`/`push` are implemented (the script
  in Track A); what remains:
  - the file watcher's reconcile would clobber a pushed slug that shadows a
    real entry on the next edit (pushed slugs are ephemeral and in-memory, so
    a distinct slug simply survives until restart);
  - Windows has no story beyond WSL/Git-Bash (the script is POSIX sh);
  - `push` renders with the *laptop's* pkl CLI version — a pkl release with a
    changed JSON renderer would surface as a push `400`; acceptable while the
    server validates, worth a version note in the script if it ever bites.
  - `Server.push` internals, for the record: the registry is a `Ref[Map[…]]`
    and the shared fan-out one multiplexed topic, so a push mints a NEW slug
    at runtime — no seeded `preview` entry needed.
- **Manifest-level checksum pinning** (pkl threat model, "package integrity").
  A dependency in `PklProject` can declare its own pin — `checksums { sha256 =
  "…" }` next to `uri` — upgrading the lockfile's trust-on-first-use to a
  declared, resolve-time-verified integrity check (spiked on 0.31.1: mismatch
  is a hard resolve failure; the sha pins the **metadata JSON's** bytes, which
  transitively pin the zip via `packageZipChecksums` — same artifact the
  URI-embedded `::sha256:` form pins; see the dump section for the
  authoritative-manifest and stale-cache semantics). `LibPackage.metadata` is
  deterministic, so the sha is stable per version. Adopted for `@fh-home`: the
  `fh` script writes its `uri` + `checksums` together on every `init`/`pull`.
  Deliberately NOT adopted for `@fh-dashboard` anywhere: a user's pin override
  in `PklProject` would *inherit* a stale checksum from the amended-over base
  default (Mapping amend merges the value object), and on the add-on a dev
  image that rebuilds the lib under an unchanged version (today a WARNING +
  cache overwrite) would turn a checksummed pin into a boot-time resolve
  failure in a file bootstrap may not rewrite — so the lockfile pins the lib
  instead.
- **`/system/push` is unauthenticated (decided), and must be covered when auth
  lands.** It is a *write*, so the instinct is to gate it to HA-authenticated
  ingress. That gate is not currently expressible: `config.yaml` sets
  `ingress_port: 8080` and also lists `8080/tcp` under `ports`, so ingress and
  direct traffic arrive on **one listener**, distinguishable only by the
  `X-Ingress-Path` header — which any direct client can simply send. A header
  check would be security theatre wherever the direct port is published.
  Accepted because push grants nothing new: the direct port is already
  documented as unauthenticated and the server drives HA with its own token, so
  anyone who can reach it can already control every device — push adds
  defacement to a strictly larger hole. The plan is auth on the direct port,
  which covers this route with it. If push ever needs to outlive that (or the
  trust model changes), the real fix is a **separate ingress-only port**: move
  `ingress_port` to one never listed in `ports`, and mount the write routes only
  on that listener, so the boundary is reachability rather than a client-supplied
  header.
- **Publishing is a GitHub release; `pkg.pkl-lang.org` is the registry.** No
  hosting to run: cut a GitHub release and consumers depend on
  `package://pkg.pkl-lang.org/github.com/<owner>/<repo>/<release>` — the service
  redirects to the release assets. (An earlier revision of this ADR guessed that
  the repo URL itself would have to serve metadata and that a separate host was
  needed. Wrong: `package://github.com/…` indeed does not work, but the registry
  path is the supported form. See apple/pkl discussions#1479.)
- **Air-gapped / mirrored installs** (`pkl-lang.org/blog/using-packages-in-air-gapped-environments`).
  Once `@fh-dashboard` is published, an HA box with no internet cannot resolve it.
  `evaluatorSettings.http.rewrites` is the answer, and it lives in `PklProject`, so
  it is a per-project setting the add-on could ship — mirror both
  `https://pkg.pkl-lang.org/` and `https://github.com/` (the registry redirects to
  release assets, so both need mirroring). Rewrite targets may be plain `http://`
  (spiked). Checksums verify normally behind a rewrite: `PklBuildSuite`'s
  third-party test resolves a sha256-pinned package through `http.rewrites`
  to a plain-http server.
- **HTTPS/cert.** The endpoint allows plain `http:` (decided). Under the CLI-pull
  model this is no longer load-bearing: a file download needs no cert, and pkl-lsp
  never crosses the network. It returns only if something must *import* over the
  wire.
- **Publish `@fh-dashboard`.** The remote half (a `package://…` registry: network +
  checksum, unspiked) is a post-API-stability step; only the consumer mapping flips.
  The dump does not participate: it is already outside that package (`@fh-home`),
  and its `@fh-dashboard/hass.pkl` import resolves into the published artifact —
  spike-verified as the same identity `components.pkl` sees.
