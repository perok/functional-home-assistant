# Simple transforms: a closed catalog of value reads with no engine

## What this changes

Value transforms today are either **literal** (no transform at all), two **direct** shapes
(`$state`, `$attr.x` — `Transform.Direct`, issue #237's fast path), or **JSONata** on the live
render path. The shipped library bakes a small, closed set of *string* shapes — some of them
actually *per render* — and every one of those that is not `$state`/`$attr.x` pays a full engine
cost for near-trivial work. This extends the direct path into a **closed catalog of simple
forms**: recognized byte-for-byte, evaluated with arithmetic/lookups that beat an engine by
~100x, and provably identical to what JSONata would produce (the oracle is JSONata itself).

The general `transform` (engine) stays exactly as it is for everything outside the catalog,
including the two shipped blocks that genuinely need a language: the slider's `fillColor` kelvin
ramp and more-info's `each/sort/join` attribute block. This is **not** a second implementation of
the language — it is the existing direct-path mechanism widened to a bounded, enumerated set, and
the guard test that keeps it from growing into one is updated to pin the new boundary.

## Author-facing surface (approved) and where each meets the shipped bytes

The simple read is `state` or `attr(name)`, optionally on a named **other entity** — cross-entity
needs no new machinery: `SlotSource.entityId` already points a slot at another entity, and
`Transforms.run` is called with that entity. The formats:

| Format | Emitted string the library ships | Where | Recognized as |
|---|---|---|---|
| `state` / `attr(x)` (raw) | `$state` / `$attr.<x>` | everywhere | `State` / `Attr(x)` (already direct) |
| `name` fallback to id | `$attr.<x> ? $attr.<x> : $entity_id` | author `exprOf`, bench `TransformName` | `AttrFallbackId(x)` |
| unit suffix | `$attr.<x> & ($attr.u ? " " & $attr.u : "")`, same with `$state` | `core/slot.pkl` `valueSlot`, `UNIT` | `ReadUnit(read, "u")` |
| literal suffix/prefix | `$state & " W"`, `"lit" & $state` | `c.exprOf(power, #"$state & " W""#)` etc. | `WithLiteral(read, " W")` |
| percent(range) | `($v := $attr.<x>; $v != null ? $string($round(($v - m) * 100 / (M - m))) & " %" : "0 %")` | `slider.pkl` `percentExpr` | `Percent(x, m, M)` |
| fill(range) | `$string(($v := $attr.<x>; $v != null ? 100 - (($v - m) * 100 / (M - m))) : 100)) & "%"` | `slider.pkl` `fillExpr` (non-toggle) | `Fill(x, m, M)` |
| enum map, default `""` | `$state = "on" ? "lit" : ""` / `"? "on" : ""` / toggle `"? "0%" : "100%"` | `control.pkl` :118,:222, toggle `fillExpr` | `EnumState(pairs, default)` |

`m`/`M` are the Pkl-interpolated integer literals for `min`/`max` (`\(min!!)` / `\(spec.min)`,
always signed integers). The toggle fill is already the `EnumState` form (its output literals are
`0%`/`100%`, no `%`-suffix operator). The more-info `attrLines` AND the `fillColor` ramp are
outside the catalog and stay on the engine, on purpose.

## Why backend string recognition, not a structured wire field

Earlier chat floated baking a structured Pkl value (Design A). The plan diverges: recognize the
**exact strings the library already emits** (Design B). The `transform` String is already
load-bearing — it is the key of `transformStrings`/`compiled`, the cache key of `read = "once"`,
and the source of the signal name and the `transformSegment` (`t`+hash). Adding a field would
need `WireShapeSuite`, every snapshot, and a parallel signal-name path — and would *still* have
to handle the old strings. Recognition is zero wire, zero snapshot, zero signal-name change, and
by construction safe: an unrecognized string falls through to the engine exactly as it does today.

## Value semantics (byte-parity, not approximation)

The simple evaluator must reproduce dashjoin's exact results, because a swap that changes a
rendered byte is behavior drift. Each form's semantics are pinned by the compiled engine:

- **strings** (`State`, `Attr`, `WithLiteral`, `ReadUnit`, `EnumState`) — plain reads and `&`
  concat; value types only `String`/`null`/numbers via the existing `asString`. `ReadUnit`:
  unit `null` or `""` → no suffix (dashjoin `boolize`: empty string is *falsy*); otherwise `" " + unit`.
- **`AttrFallbackId`** — `boolize(attr)` (dashjoin's truthiness, including empty-string falsy)
  decides which branch; else engine result is the attr.
- **`Percent`** — arithmetic in doubles: `x = ((v - m) * 100.0) / (M - m)`, then **`$round` is
  replicated exactly**: `BigDecimal(x.toString).setScale(0, HALF_EVEN)` (dashjoin 0.9.10 `.round`
  — banker's rounding, verified in the sources). Out = `r + " %"`. Missing attr → `"0 %"`.
- **`Fill`** — `d = 100 - ((v - m) * 100.0) / (M - m)`, then **`$string` is replicated exactly**:
  integral → `Math.round(d)`; else `new BigDecimal(d, MathContext(15)).stripTrailingZeros` with
  `E+`/`E-` → `e+`/`e-` (0.9.10 `Functions.string`, verified byte-for-byte). Missing attr →
  `$string(100)` → `"100%"`, then `+ "%"`.
- **Type safety**: `runSimple` returns `Option[String]`; a value shape the simple evaluator does
  not model (numeric arithmetic on a String attribute, a non-string unit, non-finite numbers)
  returns `None` and `Transforms.run` falls back to the engine — so an odd attribute type renders
  the *same* bytes (error message included) as today. Simple is a safe *fast path*, never a
  different answer.

Parity is enforced, not asserted in prose: the `TransformsSuite` oracle pattern (JSONata is the
expected) extends over every catalog form with a value sweep, and for `Percent`/`Fill`
specifically spans a battery of whole, half and off-by-a-hair edge values that a shipped card can
actually produce.

## Dispatch

`Transforms` keeps its `compiled` map (still compiled upfront; the map stays total, invariant
uniform — we deliberately do not skip JSONata compilation for recognized strings). Add a second
map, `simple: Map[Expr, Simple]`, built once from `compiled.keys` beside today's `direct`.
`run` tries `simple` first, falls back to the engine. `Dashboard.scala` (validate/transformStrings)
and the renderer are untouched; `Renderer.transformSegment` keeps hashing non-`$state`/`$attr`
strings the same way, because the strings themselves do not change.

Naming: `Transform.Direct` → `Transform.Simple` (recognizer `simple`, runner `runSimple`). The
kind is no longer "reads and applies nothing".

## Tests

- **New**: a catalog parity suite — each recognized string, run both ways (`runSimple` vs
  `Transform.run` over the sweep), byte-equal per value. For `Percent`/`Fill` include values that
  force the double paths (non-integral results, `.5`-rounding, min-bound, absent attribute).
- **`TransformsSuite` guard is rewritten, not fudged.** The current test "only the two shapes are
  direct" pins the OLD policy and its examples *move into* the new catalog: `$state & " W"`
  (→ `WithLiteral`) and `$state = "on" ? "Open" : "Closed"` (→ `EnumState`) become recognized.
  The boundary it then guards is the new one — near-misses that still read like simple forms
  (`$attr.a.b`, `$attr."quoted"`, `$attr.a[0]`, `$states`, `$round($number($state), 1)`,
  the `fillColor` and `attrLines` blocks) still resolve to `None`/engine. This is the "claim your
  change falsified" rule applied to a test that encoded the superseded spec.
- **`TransformSuite` "slider fill" is a stale claim fix.** It pins the *pre-`$string`* bytes
  (`… ? 100 - ((…)) : 100`, expecting `"39.3700787402"`) while `slider.pkl` has shipped
  `$string((…)) & "%"` for a while. It is switched to the true shipped string; expected outputs
  become `"0%"`, `"50%"`, `"39.3700787401575%"` (the `MathContext(15)` rounding), `"100%"` — and
  it doubles as the `Fill` parity pin. The shipped behavior being pinned does not change.
- **No snapshot churn**: `PklBuildSuite`/`WireShapeSuite` untouched (wire unchanged; evaluated
  output is byte-identical by construction).

## Benchmarks

- Fix the stale `RenderBench.TransformFill` constant — it is the pre-`$string` form, not the
  bytes `slider.pkl` ships ("the shapes … in the exact form they ship" is falsified by it).
- Add `simple` cells beside `direct`: the catalog forms through `runSimple`, the same shapes
  through `run` for contrast. The `%`/`$string` fill cell measures the *true* shipped bytes both
  ways.

## Docs (same commit as the change)

- `docs/terminology.md` — **Slot**: a transform is "a recognized simple form, or a JSONata
  expression" (today it says a JSONata expression, which this change moves).
- `docs/architecture-rendering-pipeline.md` — a short two-tier note where the transform cost is
  described; no box moves (the change is inside the render step), and the `Transform` row in the
  "where each box lives" table gains the simple path.
- `Transform.scala`/`Transforms.scala` scaladocs state the catalog and the parity contract.

## Out of scope

- Swapping the engine (CEL stays a benchmark counterfactual, not a candidate).
- The `fillColor` kelvin ramp and more-info `attrLines` (genuinely need `$each`/`$count`/paths).
- Author-facing `round(n)` decimals, composed reads (`"a" & unit & "%"`), templated percent —
  v1 is atomic forms over one read. Anything new the library later bakes lands in the catalog
  only through review; unrecognized strings are engine work, same as today.
- A new ADR: this belongs to issue #237/#240's existing story; decide the ADR after the code lands.