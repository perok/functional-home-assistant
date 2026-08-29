# ADR 0027 — Transforms are CEL: the engine swap and what it kept

- **Status:** Accepted
- **Date:** 2026-08-29 (decision recorded in `docs/plan-simple-transforms.md`;
  this ADR lands after the swap, per the repo routine)
- **Scope:** `model/Transform.scala`, `runtime/Cel.scala`, `runtime/Transforms.scala`,
  `runtime/StateStore.scala` (null-attr rule), the Pkl library's transform strings,
  `modules/benchmarks` (engine cells + divergence gate)
- **Owns:** the choice of transform ENGINE and its value semantics. ADR 0001
  owns the slot/transform model itself; ADR 0016 owns which taps stay live;
  ADR 0017 owns signal slots. Those are untouched: the change is strictly
  inside the transform step, same dispatch shape, same wire facts.

## Context

Every per-slot value transform was a dashjoin JSONata expression. That cost
~5.9 kB per evaluation of the shipped shapes (bench: `RenderBench.jsonata`)
against ~1.8 kB for cel-java's planner runtime on the same shapes — and JSONata
brought a second value model to maintain an intuition about: falsy `""`,
null-on-missing-key, HALF_EVEN rounding, a 15-significant-digit `$string`.

Phase 0 (`docs/plan-simple-transforms.md`) pinned the divergence map first: a
golden divergence gate (`CelSpike`) sweeping both engines over the benchmark
fixture plus a hostile margin, asserting the SET of byte differences — so the
swap's output delta was known before anything rode on it.

## The decision

**cel-java 0.14.0 is the transform engine** — compile once at build/validate
(`Transform.parse`), eval-many per live value (`Transform.run`, planner
runtime, one activation per eval, `str`/`num` as the only registered helpers).
Dashjoin left the shipped runtime entirely; it survives only as a bench-local
reference (`modules/benchmarks/.../Jsonata.scala`) behind the divergence gate
and the `jsonata` bench cells.

- **CEL-native semantics, no compatibility shims.** Presence is `'k' in attr`
  (a raw `attr['k']` on an absent key throws — it is not a null check), concat
  is `+`, rounding is `math.round` (half-away), `double(v)` is explicit where
  JSONata coerced. `Transform.Direct` (bare `state` / guarded attr read) still
  bypasses the engine; renaming/expanding it to the Simple catalog is Phase 2
  and stays there.
- **The library re-authored, not translated at runtime.** The shipped strings
  are ours (`slider.pkl`, `control.pkl`, `core/tap.pkl`, more-info, fixtures);
  `$dashboardSlug` became the `dashboard_slug` binding. Validation stays the
  ONE gate — a transform that does not compile fails the build — and during
  the swap it caught exactly the defect class it exists for: a re-authored
  `fillExpr` splice had dropped a paren, and the library refused to build.
- **Two stringifier decisions, both measured:** the `str()` helper adopts the
  pre-existing 10-digit `numToString` (strips `.0`, `[a, b]` lists, HALF_UP)
  rather than CEL-native `string()` — this normalised most of Phase 0's
  fill/fillColor margins away; and a JSON `null` attribute is dropped at the
  activation boundary (`toJavaObject`), the same "null is absent" rule
  `jsonToString` already applied to state, so slot defaults keep working.

## Consequences

- **The output delta is pinned, not assumed.** Re-measured on the shipped
  bytes: 15 divergence rows in four classes — empty-string presence (2: JSONata
  found `""` falsy, `in` finds it present), fill digit rendering (11:
  JSONata's ~15-significant-digit `$string` vs the shared numToString), error
  wording (1), and one half-away step on an exactly-representable `.5`
  unreachable on integer attributes. `percent` and `attrLines` agree
  byte-for-byte across the whole sweep; `fillColor` now agrees everywhere too.
  The gate fails loudly on a new divergence (an engine upgrade, a reference
  edit, a wrong translation).
- **Wire bytes moved once, deliberately.** Transform strings, signal-path
  segments (`transformSegment` hashes everything but `state`), wire snapshots
  and the display-signal seeds all changed in the swap commit and stabilise
  there.
- **A CEL null result arrives as `dev.cel.common.values.NullValue`,** not Java
  null and not protobuf's — guarded by class name in `Cel`, so a null result
  still renders `""` and hands the slot's `default` its turn.
- **Per-eval cost drops ~3.3x on the shipped shapes** (Phase-0 bench
  measurement; `RenderBench.cel` vs `.jsonata` re-measures it on the shipped
  bytes), and the engine no longer ships in the add-on jar.
