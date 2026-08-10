# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A type-safe, "functional" wrapper around [Home Assistant](https://www.home-assistant.io/). The core idea: **connect to a live Home Assistant instance, introspect its devices/entities/services, and code-generate strongly-typed Scala references to them**, so that automations written against this project reference real devices/entities by name with compile-time guarantees (rather than stringly-typed `entity_id`s). Conceptually similar to NetDaemon/AppDaemon, but with generated types.

Scala 3 + Typelevel stack (cats-effect, http4s, circe, chimney) + smithy4s for the REST API. Built with **sbt 2.0.0**.

## behavior

Do not make anything up and ask questions if anything is unclear.

## ADRs and plans: read first, rewrite in place

Design decisions live in [`docs/adr/`](docs/adr/README.md). Read the relevant ADR(s) before
changing the area they cover. Each ADR is a **current-state** document: check a change against
them, and when a change supersedes a decision, **rewrite the relevant ADR in place** — git history
keeps the archaeology, so no dated update sections while the design is pre-v1. A genuinely new
decision gets a new ADR.

[`docs/architecture-rendering-pipeline.md`](docs/architecture-rendering-pipeline.md) is the **map of the live rendering
system** — how one HA state change becomes bytes in a browser, what is shared per slug vs. per
connection, and the three node kinds. Read it before changing anything in `fh/view/runtime`, and
**update it in the same commit as the change**, ADRs included (an ADR owns the decision, that file
owns the shape). It is also where a proposal should point: say which box moves. Its "Known open
questions" section is a live list, not a backlog — delete an entry when it is answered.

`docs/plan-*.md` are **deferred design plans, not implemented code** unless they say otherwise —
do not assume an API described there exists in the sources.

When the user questions a decision in a plan or ADR, **discuss alternatives in chat first** (with
spikes as evidence, inline code examples) — do not rewrite the document until a direction is
picked.

## Build & run commands

```bash
sbt compile                         # compile everything
sbt test                            # INCREMENTAL in sbt 2.0 (only changed suites)
sbt testFull                        # run ALL tests regardless of change (e.g. fh-datastar-view/testFull)
sbt 'testOnly *SomeSuite'           # run a single test suite
sbt 'testOnly *SomeSuite -- *name*' # run a single test by name (munit)
scalafmt                            # format (standalone CLI, version pinned by .scalafmt.conf,
                                    # scala3 dialect); a PreToolUse hook already runs this
                                    # before every `git add` — see .claude/settings.json
sbt doCodegen                       # regenerate typed device/entity code, then format it
sbt 'home / run'                    # run the main app (AppHome), env vars set from build.sbt
sbt dashboardBuild                  # build phase: regenerate modules/fh-datastar-view/dashboard.json
sbt dashboardServe                  # runtime: serve the Datastar dashboard (http://localhost:8080)
sbt fh-datastar-view/frontendInstall  # npm ci for the dashboard frontend
sbt fh-datastar-view/frontendBundle   # vite build -> managed resources (runs on compile)
```

## Hooks (`.claude/settings.json`, `.claude/hooks/`)

Three `PreToolUse` guardrails run automatically — don't route around them without reading why
they exist first:

- **scalafmt before `git add`** — runs `scalafmt` (standalone CLI) so nothing unformatted ever
  reaches CI's `scalafmt --test`.
- **`guard-protected-paths.sh`** (on `Edit`/`Write`) — blocks edits inside any folder literally
  named `generated` (build output, wiped by `doCodegen`) and blocks any edit touching the
  `version:` line in `home-addon/config.yaml` (the release trigger — see "Releasing is the
  maintainer's call" above).
- **`block-shell-file-edits.sh`** (on `Bash`) — blocks `sed -i`, `perl -i`, `python`/`python3`
  file writes, `cat >`/`>>`, and `tee` against tracked files. Use the `Edit`/`Write` tools
  instead: they require reading the file first and enforce a unique match, which catches
  stale-file and ambiguous-replacement errors that shell edits don't. `/tmp` and the scratchpad
  dir are exempt.

  **Escape hatch**: for a genuine bulk/multi-file mechanical edit (a repo-wide rename,
  find+sed across many files) where a Read+Edit round trip per file would be far more
  expensive than one shell command, prefix the command with `ALLOW_SHELL_EDIT=1` to bypass
  the check for that one call, e.g. `ALLOW_SHELL_EDIT=1 sed -i 's/old/new/' $(git grep -l old)`.
  Use it deliberately for actual bulk edits — not as a way around the check for a single edit
  that `Edit`/`Write` would handle fine.

**`fh-datastar-view` needs node + npm to build.** Its frontend (`src/js`, TypeScript
and JavaScript) is bundled by vite 8 into managed resources via `project/NpmPlugin.scala`,
wired as a `resourceGenerators` entry — so an ordinary `compile`/`test`/`assembly` runs
`npm ci` and `vite build` when the sources change, and nothing built is committed. Both
tasks no-op when a content fingerprint of their inputs still matches.

Note: `run`/`runMain` are forked with the **working directory set to the module's base dir**
(e.g. `modules/fh-datastar-view`), so relative paths in `*App` mains are module-relative.

`doCodegen` is an alias for `fhTaskCodeGen ; home-codegen / scalafmt`. It connects to the live HA instance at `haUrl` (configured in `build.sbt`, currently `http://192.168.1.174:8123`) using `haSecret`, wipes `modules/home-codegen/src/main/scala/ha/generated`, and regenerates it. **That generated directory is gitignored** — it is a build product, not source. Codegen requires the HA instance to be reachable.

## Architecture

The codegen pipeline is the spine of the project. Data flows: **live HA instance → `ha-api` client → `fh-codegen-plugin` generators → generated Scala in `home-codegen` → consumed by `home` app**.

### Modules (`modules/`)

- **`ha-api`** — The Home Assistant client. REST API is defined in Smithy (`src/main/smithy/test.smithy`) and code-generated by the smithy4s sbt plugin into `perok.ha.*`. The WebSocket API is hand-written (`api/homeassistant/ws/`). `HomeAssistantApi[F]` (`api/homeassistant/HomeAssistantApi.scala`) is the unified trait combining REST + WS; `fh.api.FHApi` is the entry point that builds it from a URL + token (`FHApi.fromEnv` reads `SERVER`/`SECRET` env vars).
- **`fh-domain`** — Shared domain types: `DeviceId`, `EntityId`, `Entity`, `Manifest`, `ConfigEntry`, etc. under `ha.runtime.definitions`. Depended on by everything.
- **`fh-codegen-plugin`** — The actual code generators. `fh.codegen.Plugin` is the `IOApp` entry point invoked by the sbt task; it fetches services/entities/devices/manifests/config-entries/triggers from the API and runs `CodeGenEntities`, `CodeGenDevices`, `CodeGenServices`, `CodeGenConfigEntries`, `CodeGenManifests`. `ThingReference[T]` is the central abstraction — a named, packaged unit of generated code that knows its own file path and package. Generation uses plain string templating (scalameta does not support Scala 3 / Dotty).
- **`home-codegen`** — Output target for generated code (under `ha.generated.*`). Enables the `FHCodegenPlugin`. Contents are gitignored.
- **`home`** — The runnable application (`AppHome`, an `IOApp.Simple`). Depends on `ha-api` and `home-codegen`, so automations here can reference generated devices/entities by name.
- **`fh-datastar-view`** — A simpler HA web frontend (port of the TS prototype in `../ha-frontend`). Has its own `CLAUDE.md`.
- **`fh-api`, `fh-automation`** — Stubs / WIP (marked "TODO needed?" in build.sbt).

### `fh-datastar-view` — the Datastar dashboard

A two-phase HA dashboard frontend: authors write Pkl, the server renders HTML and keeps it live
with [Datastar](https://data-star.dev) (SSE fragment patches + action POSTs). Its full guidance —
workflow, key files, Pkl semantics gotchas, phase discipline — lives in
`modules/fh-datastar-view/CLAUDE.md`, which loads when you work in that module. **Read it before
changing anything there.**

### The sbt plugin glue

`project/NpmPlugin.scala` is the other project-local `AutoPlugin` (ported from the sbt 1
one in perok/workshop-programs-as-values): `frontendInstall` (`npm ci`) and
`frontendBundle` (`npm run build`, then copy `target/frontend` into
`Compile / resourceManaged`). Both are `Def.uncached` with an explicit content-hash
stamp, because sbt 2's task cache cannot see that npm wrote a tree or that the output
was deleted; `fileInputs` is declared purely so `~` watches the sources.

`project/FHCodegenPlugin.scala` is a project-local sbt `AutoPlugin`. It defines `fhTaskCodeGen`, which deletes the output dir and runs `fh.codegen.Plugin` via `runMain` with `(outputDir, haUrl, haSecret)` as args. Note: it writes to **`scalaSource`** (unmanaged source), not `sourceManaged`, because there is no good cache key to invalidate on — so codegen is manual via `doCodegen`, not automatic on compile. `build.sbt` wires `haUrl`/`haSecret` into the `home-codegen` project.

### Deployment (`home-addon/`)

Intended as a Home Assistant add-on (`Dockerfile` + `entrypoint.sh`) that watches a folder for new assembly jars (via `inotifywait`) and restarts the running `java -jar`. This is early WIP/scaffolding.

#### Releasing is the maintainer's call — never bump the version

`version:` in `home-addon/config.yaml` is **the release trigger, not metadata**. `cd`
publishes exactly when main names a version that is not on GHCR yet, so bumping it and
merging to main *is* the release: container built and pushed, `vX.Y.Z` tagged, GitHub
release created. That is deliberate design — the maintainer publishes by bumping, when
they want to publish.

So: **do not edit that line.** Not as part of unrelated work, not to "make a PR
shippable", not because a branch's version matches something already published. An
unchanged version on a merged PR is the NORMAL case, not a defect to report — the
release happens later, when the maintainer decides. If a version question seems to
matter, say so and stop; do not act on it.

## Conventions & gotchas

- Generated file names sanitize device/entity names (spaces → `-`, emoji → unicode names) because the Scala compiler rejects emoji in filenames — see `ThingReference.toPath`.
- The HA bearer token is currently **hardcoded in `build.sbt`** (`secretToken`). Treat it as a real credential.
- `sbt-tpolecat` enforces strict compiler options; `warnError` is excluded so warnings don't fail the build.
- Generated package root is `ha.generated` (set in `Plugin.scala` as `AbsolutePosition(outputDir, List("ha", "generated"))`).

## Read the compiler's warnings

`-Wunused:privates`/`locals`/`params`/`imports` are ON (sbt-tpolecat), and
`warnError` is excluded so warnings do NOT fail the build — which means they are
easy to never see. Two consequences worth knowing:

- **The compiler already finds dead private members.** Do not grep for them.
  Grep is still needed for unused PUBLIC API, which the compiler cannot prove.
- **When filtering test/compile output, do not filter out `[warn]`.** A run
  reduced to `grep "==> X|Total"` hides exactly the signal that would have said
  a helper became unreachable.

## Comments: the code says what, a comment says why

The runtime currently carries roughly as many comment lines as code lines. That is too many, and
new work should not add to the ratio. Before writing a comment, try to delete the need for it — a
better name, a smaller function, or a type usually says it better and cannot go stale.

**Do not write:** a restatement of the signature or the control flow; a narration of what a
well-named call does; a history of what the code used to be or what was tried and reverted (that
belongs in the commit message, or in a plan/ADR if it is a decision); a section header over three
lines of code.

**Do write:** the reason a non-obvious choice was made over the obvious one; an invariant a reader
would otherwise have to reconstruct; a trap that has actually bitten (with what it looked like);
anything that took a spike to learn. One or two lines each — if it needs a paragraph, it is
probably a design decision and belongs in the ADR, with the code pointing at it.

A useful test: delete the comment and ask whether a competent reader would now make a mistake. If
not, leave it deleted.

## Design principles (apply when touching existing code, not just when writing new code)

These come out of a whole-codebase FP simplification review and are still being applied
incrementally (see the "FP simplification pass" plan in memory/`TODO.md` history) — treat them
as standing review criteria, not a one-time cleanup that's now "done".

- **Terminal errors are `FHError`, not threaded `Either`/`Option`/sentinel strings.**
  `fh.view.FHError` (`modules/fh-datastar-view/.../fh/view/FHError.scala`) is a `RuntimeException`
  carrying an HTTP status; `FHError.badCondition/notFound/unavailable/internal` pick the code at
  the raise site, `FHError.handle` is the one app-level boundary that turns any raised `FHError`
  into `status + message`. A condition is "terminal" — and belongs as a raised `FHError`, not a
  `Left`/`None` the caller re-inspects — when there is genuinely no local recovery: a malformed
  request, a resource that doesn't exist, a misconfigured/un-bootstrapped workspace. Routes that
  are exercised directly in tests (without the app-level `FHError.handle` wrapping them) recover
  locally with the same `case e: FHError => IO.pure(FHError.response(e))` shape (see
  `Server.pushResponse`, `Server.guardSystemPkl`) so behavior is identical either way. When you
  find an `Either[String, A]` / `Option[A]` return whose "empty" case is really "this can't be
  served, ever" rather than a value the caller branches on, that's a refactor candidate — see
  `fh.view.build.SystemPkl` (module/packageArtifact/packagesIndex) for the shape.
- **Parse, don't validate.** Prefer producing a value that makes an illegal state
  unrepresentable over re-validating the same precondition at every consumer. `Dashboard.Validated`
  (produced only by `Dashboard.validate`, carrying already-compiled JSONata transforms) is the
  model: `Renderer`/`Transforms` take the validated type instead of re-checking and throwing
  "validate should have rejected this". Look for the same smell elsewhere: a `None`/`Left` that
  really means "this workspace/value is unusable" and gets re-derived or re-thrown-defensively at
  multiple call sites instead of being parsed once at the boundary into a type that carries the
  proof.
- **Functional core / imperative shell.** Keep pure logic (diffing, rendering, validation, AST
  evaluation) separate from the `os.*`/`IO`/network shell, and prefer extracting pure logic out of
  a class that's only reachable today via a full-boot test harness (e.g. `Server`'s pure diff core
  in `Patches`) — that's usually the biggest testability win available. Mutation stays where
  performance genuinely demands it (`Renderer.identityCache`, per-slug diff caches, jmustache Java
  interop) — this is not a blanket "no mutable state" rule.
- **Name recurring implicit concepts.** If the same shape (a `(String, String)` tuple, a
  hand-rebuilt URI/path string, a re-derived precondition) shows up re-interpolated or re-checked
  in several places (`PackageRef` in `fh.view.build` is the existing example — one value type now
  owns the `package://…` URI, the cache-dir layout, and the version-parsing regex that used to be
  duplicated ~5 places), that's a sign to name it as a real type rather than leaving it implicit.
- **Types hold truth.** A signature should tell the whole story: avoid `Option[String]` whose
  `None` meaning lives only in a doc comment, `List[String]` used as an ad-hoc log/protocol, or
  functions with many same-typed positional args (extract a named `case class` request/config
  instead — see `ServerApp.Config`, the `Patches.DiffRequest` shape).
- **Sum-type the state — but only when the flags are the SAME fact.** When several parallel
  `Option`/`Boolean` flags encode one concept and only some combinations are valid, collapse them
  into one ADT and derive the old projections from it ("types hold truth" applied to state). First
  check they really are one fact, though: distinct facts want distinct shapes, and merging them
  fakes one with the other. `current: Option[Conn]` + `healthy: Boolean` look mergeable, but
  they're two facts — a per-connection toggle (`SignallingRef[Option[Conn]]`, whose `.isDefined`
  IS the banner) and a one-shot "seeded at least once" latch (`Deferred[Unit]`); a 3-state `enum`
  just fakes the latch with a state you never leave. Fusing them also over-couples: it makes the
  banner wait for a background catch-up (reseed) that liveness never needed.
- **One mechanism, not two parallel ones.** When two structures do variants of the same job (a
  `Deferred` for one-shot replies + a `Queue` for streamed ones), collapse them into the single
  primitive that subsumes both (a `Queue` you take once — the WS transport's id→`Queue` routing).
  Uniform handling often removes a race/special-case *by construction* rather than needing a patch
  for it.
- **Push behavior onto the type that owns the data, not a central switchboard.** A
  transport/dispatcher should move bytes and route by id; how to decode/interpret a message belongs
  on the message type itself (`CommandResponse.decodeMessage`), keeping the hub format-agnostic and
  each variant's logic next to its definition.
- **Prefer a queue + single owner over a lock + shared mutable refs.** Serialize
  ordering-sensitive work (id allocation, linear sends) with one consumer fiber draining a queue,
  not `Mutex` + ref-juggling to survive cancellation. (`cats.effect.std.Hotswap` is the matching
  tool for *resource rotation* — reconnects — but it has no readable "current" and no retry, so it
  complements, not replaces, a state signal + backoff loop.)
- **Refactor behind stable public signatures.** Change internals freely but keep the exposed
  type/shape fixed (`getStates: IO[List[GetStatesData]]`, `HaFeed(api, store, healthy)`) so
  consumers and tests stay untouched and the diff reads honestly.

## Skills

These are available but load only when invoked — reach for them when the trigger applies:

- **`scala3-syntax`** — before writing any new Scala 3 source. The build runs `-source:future`,
  `-language:strictEquality`, `-Yexplicit-nulls`; Scala 3 written from memory routinely produces
  syntax this profile rejects or warns off.
- **`scala-fp`** — Cats / cats-effect / fs2 work: `Ref`, `Deferred`, `Queue`, `Resource`, fibers,
  `Topic`, tagless final. The runtime is entirely Typelevel, so this covers most of
  `fh-datastar-view` and `ha-api`.
- **`scala-code-optimizer`** — refactoring, modernizing, or auditing a `.scala` file.
- **`scala-weaver-test`** — writing or changing weaver suites (`fh-datastar-view` tests,
  `scripts/fh.test.scala`).
- **`sbt`** — sbt gotchas specific to this repo.
- **`datastar`**, **`beercss`** — dashboard work; see `modules/fh-datastar-view/CLAUDE.md`.

### Using `scalex` for Scala navigation

This repo has the `scalex` skill available (a Scalameta-based code-intelligence CLI: `def`,
`refs`, `impl`, `members`, `body`, `hierarchy`, `explain`, …). Prefer it over `grep`/`Grep` for
Scala-symbol lookups — finding a definition, enumerating call sites before a rename/move, checking
what implements a trait — since it understands Scala syntax (givens, extensions, companion
objects) that plain text search misses or over-matches. It only indexes git-tracked `.scala`/
`.java` files. **Do not invoke it unprompted** — use it when it's the right tool for a task already
in progress (e.g. mid-refactor, checking call sites), not proactively at the start of unrelated
work.
