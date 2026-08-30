# ADR 0027 — Transforms are CEL: the engine swap and what it kept

- **Status:** Accepted
- **Date:** 2026-08-29 (decision and Phase-0 gate in
  `docs/plan-simple-transforms.md`; this ADR lands after the swap, per the
  repo routine)
- **Scope:** `model/Transform.scala`, `runtime/Cel.scala`, `runtime/Transforms.scala`,
  `runtime/StateStore.scala` (null-attr rule), the Pkl library's transform strings,
  `modules/benchmarks` (engine cells + divergence gate)
- **Owns:** the choice of transform ENGINE and its value semantics. ADR 0001
  owns the slot/transform model itself; ADR 0016 owns which taps stay live;
  ADR 0017 owns signal slots. Those are untouched: the change is strictly
  inside the transform step — same dispatch shape, same wire facts.

## Context

Every per-slot value transform was a dashjoin JSONata expression. That cost
~5.9 kB per evaluation of the shipped shapes (bench: `RenderBench.jsonata`)
against ~1.8 kB for cel-java's planner runtime on the same shapes — and JSONata
brought a second value model to keep an intuition about: falsy `""`,
null-on-missing-key, HALF_EVEN rounding, a 15-significant-digit `$string`.

Phase 0 (`docs/plan-simple-transforms.md`) pinned the divergence map first: a
golden divergence gate (`CelSpike`) sweeping both engines over the benchmark
fixture plus a hostile margin, asserting the SET of byte differences — so the
swap's output delta was known before anything rode on it.

## The decision

**cel-java 0.14.0 is the transform engine** — compile once at build/validate
(`Transform.parse`), eval-many per live value (`Transform.run`: planner
runtime, one activation per eval, `str`/`num` as the only registered helpers).
Dashjoin left the shipped runtime entirely; it survives only as a bench-local
reference (`modules/benchmarks/.../Jsonata.scala`) behind the divergence gate
and the `jsonata` bench cells.

- **CEL-native semantics, no compatibility shims.** Presence is `'k' in attr`
  (a raw `attr['k']` on an absent key throws — it is not a null check), concat
  is `+`, rounding is `math.round` (half-away), `double(v)` is explicit where
  JSONata coerced. `Transform.Direct` (bare `state`, guarded attribute read)
  still bypasses the engine; widening it into the Simple catalog is Phase 2
  and stays there.
- **The library re-authored, not translated at runtime.** The shipped strings
  are ours (`slider.pkl`, `control.pkl`, `core/tap.pkl`, `core/slot.pkl`,
  `core/surface.pkl`, more-info, demo boards, fixtures); `$dashboardSlug`
  became the `dashboard_slug` binding. Validation stays the ONE gate — a
  transform that does not compile fails the build — and during the swap it
  caught exactly its defect class: a re-authored `fillExpr` splice had dropped
  a paren, and the library refused to build.
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
  The gate fails loudly on any new divergence (an engine upgrade, a reference
  edit, a wrong translation).
- **Wire bytes moved once, deliberately.** Transform strings, display-signal
  path segments (`transformSegment` hashes everything but `state`), the wire
  snapshots and the seeds all changed in the swap commit and stabilise there.
- **A CEL null result arrives as `dev.cel.common.values.NullValue`,** not Java
  null and not protobuf's — guarded by class name in `Cel`, so a null result
  still renders `""` and hands the slot's `default` its turn.
- **Per-eval cost, re-measured on the shipped bytes** (JMH, `-f 2 -wi 5 -i 5
  -prof gc`, 200 leaves × 6 shapes = 1200 evals/op; allocation figures are
  exact, CPU carries the bench's usual ±20-30%):

  | Cells | CPU/eval | Allocation/eval |
  |---|---|---|
  | `RenderBench.cel` (shipped engine) | **1.4 µs** | **~0.9 kB** |
  | `RenderBench.jsonata` (retired) | 2.9 µs | ~6.3 kB |
  | `RenderBench.direct` (no engine) | 0.024 µs | ~45 B |
  | `celComplex` / `jsonataComplex` (ceiling) | 1.4 / 7.4 µs | ~1.6 / ~15.6 kB |

  So the swap bought **7x memory / 2.2x CPU** on the shipped shapes and
  **9.5x memory / 5.3x CPU** on the hostile ceiling. The JSONata gap is mostly
  MEMORY — dashjoin burns 7x the allocations for 2.2x the time. The JSONata
  engine no longer ships in the add-on jar.
- **The per-eval activation is a resolver, not a map.** `Cel.run` hands the
  planner a `CelVariableResolver` that produces each binding on demand, so the
  five-entry `HashMap` build is gone and an expression that reads no attribute
  never forces `EntityState.javaAttributes` at all. Measured before/after:
  `cel` 2 154 237 → 1 056 636 B/op (allocation halved, ~1.8 kB → ~0.9 kB per
  eval), `celComplex` 512 002 → 328 002 B/op, with CPU following (1.6 →
  1.4 µs/eval). The resolver is one small object per eval; values are the
  entity's own cached references, unboxed.
- **cel-java's compile-time optimizers were measured and declined.** The
  codelab's pairing (`ConstantFoldingOptimizer` + `SubexpressionOptimizer`,
  wired via `CelOptimizerFactory` between compile and `createProgram`) ran
  green through the gate and suites but bought no CPU (every difference inside
  the error bars) and cost a consistent ~1-2% MORE allocation per eval
  (~2.15 MB/op → ~2.18-2.19 MB/op): the planner materializes a folded constant
  node where inline arithmetic was free, and the shipped shapes carry no
  repeated subtree for CSE to extract — each attribute read appears once, and
  the sharing that exists is already authored as `cel.bind`. The "complex"
  ceiling shape IS the repeated-subtree case (its map-literal lookup appears
  twice) and still measured byte-identical, so the decline is not an artifact
  of an easy fixture. Compile is once-per-transform at validate, so the
  optimizers' own cost was never the issue; the eval side just does not pay.
