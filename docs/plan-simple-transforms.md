# Transforms on CEL: an engine swap plus a closed fast-path catalog

Status: supersedes the prior draft of this file (which pinned a JSONata recognizer).
Decided 2026-08-29: **CEL is the next engine, no matter what.** The prior draft's premise —
recognize the shipped JSONata strings so a JSONata engine can be skipped — folded into the
CEL workstream instead: the library will be re-authored in CEL, so the shipped strings are OURS,
and recognising them is detecting our own canonical forms rather than hardcoding someone else's
dialect.

**Phase 1 is shipped** (same day) — the swap is on the `engine-cel-spike` branch, `testFull`
691/691, gate re-pinned. What it took, beyond the bullets below:

- The bench-local `CelTransforms` (the Phase-0 stand-in engine) is deleted; the bench now runs
  the production `Transform` for its CEL cells and keeps dashjoin ONLY as a bench-local
  reference (`modules/benchmarks/.../Jsonata.scala`) for the JSONata cells and the gate's
  reference side. `fh-datastar-view` itself carries no JSONata.
- The stringifier decision (Dec. #2's open end): the shipped `str()` helper IS the shared
  10-digit `numToString` — strips `.0`, renders lists `[a, b]`, HALF_UP. That normalised most
  of the Phase-0 fill/fillColor margins away; the re-measured gate pins **15 rows** (empty-string
  presence 2, fill digit rendering 11, error text 1, the HALF_EVEN half 1).
- Two engine-adjacent fixes the swap surfaced, both pinned by suites: a kept JSON `null`
  attribute made CEL's map index throw where JSONata read null — `toJavaObject` now drops null
  attrs, the same "null is absent" rule `jsonToString` already applied to state; and a CEL null
  RESULT arrives as `dev.cel.common.values.NullValue`, not Java null (guarded by class name in
  `Cel`).
- One real defect the gate caught mid-swap: the re-authored `fillExpr` splice dropped a paren
  the JSONata original had — validation (the one gate) refused the library, exactly as designed.

## Decisions

1. **CEL is the engine.** `com.google.cel` (cel-java), PLANNER runtime, compile-once/eval-many
   (the Phase-0 bench stand-in proved the pattern and the ~3.3x per-eval gain over dashjoin on
   the same shapes). It replaces dashjoin JSONata everywhere a transform is evaluated.
2. **CEL-native value semantics.** No dashjoin-compatibility functions, no imported rounding or
   stringification. Presence is `!= null`, rounding is `math.round`, numbers stringify as CEL
   renders them, concat is null-safe only where the expression makes it so. Rendered bytes that
   sat on a JSONata margin change (fill@155: dashjoin `$string` `39.3700787401575%` → CEL-native
   `string()` `39.37007874015748%`), round halves move by one (`$round` was HALF_EVEN,
   `math.round` is half-away), and an *empty-string* attribute stops being falsy. This is
   intended, accounted for, snapshot-regen'd and documented — not an accident the swap trips
   over. The exact byte set this produces is now pinned in the Phase 0 table; whether the swap
   additionally normalises number text to the catalog's 10-digit `numToString` is a Phase-1
   stringifier decision (both are CEL-native enough to write, and the pin makes the change of
   approach visible).
3. **The strict catalog stays as the fast tier.** `Transform.Direct` becomes `Transform.Simple`:
   the closed set of recognized forms, evaluated by hand-rolled reads/arithmetic. Against CEL it
   is still ~40x cheaper on those forms (~45 B vs ~1.8 kB per eval on the bench measures), and CEL
   is still the expressive fallback for everything outside the catalog. The two tiers and a
   continuous differential suite = no surprising behavior, no surprising cost.
4. **`transform: String` remains the single wire fact.** It stays the `transformStrings`/
   `compiled` key, the `once`-cache key, the signal name and the `transformSegment` source. The
   catalog is a recognition over the canonical strings the (re-authored) library emits, with the
   engine as the parity oracle. A structured `simpleTransform` wire field is still rejected: it
   would duplicate the load-bearing string into a second map that must byte-stay in sync per slot,
   and it has no author today — the components emit strings. (The one concrete rot that argues for
   generating strings — slider.pkl's shipped `fillExpr` has a stray paren and does not compile —
   must be caught by the same validation the swap already exercises, and disappears entirely when
   the components are re-authored to CEL.)

## Phases

### Phase 0 — pin the divergence map before anything rides on it ✅ done

The bench's `CelSpike` is now a **golden divergence gate** (not the all-or-nothing parity `main`
it was): a value sweep per shape — present vs absent vs empty-string, whole/half/fractional and
just-off-x.5 arithmetic results, list-valued attributes, an odd-typed margin — asserting the
*set* of divergences equals the table below. Re-run with
`sbt 'benchmarks/Compile/runMain fh.view.runtime.CelSpike'`; green means the swap's output delta
is exactly this table, a new divergence (an engine drift or a wrong translation) fails loudly.

Measured result: **19 divergences across 7 shapes, in four classes — and `percent` and
`attrLines` bleed nothing, byte-identical over the whole sweep (every fixture value, a
fractional, an absent, and a float-list probe each).**

| Shape | Margin | dashjoin today | CEL (new truth) |
|---|---|---|---|
| name | `friendly_name=""` | falsy → `entity_id` | `'k' in attr` present → `""` |
| unit | `unit_of_measurement=""` | falsy → `on` | present → `on ` (trailing space) |
| fill | every value, integer included | `$string` MC15: `97.244094488189%`, `100%` | CEL `string()`: `97.24409448818898%`, `100.0%` |
| fill | `brightness=129.27` | `49.5%` | `49.49999999999999%` (double dust) |
| fill | `brightness="on"` (odd type) | `JSONata error: The left side of …` | `cel error: … For input string: "on"` (error text only) |
| fillColor | kelvin ramp | `rgb(231,193,162)` | `rgb(231.0,193.0,162.0)` — `math.round` returns DOUBLE here |
| complex | cover `position=63.5` | HALF_EVEN → `36` | half-away → `37` |
| percent | (swept, none) | — | — |
| attrLines | (swept, none) | — | — |

Half-rounding shows only on an exactly-representable `.5` (cover's 100-scaled range makes 63.5 → 
36.5 exact); on integer attributes x.5 is unreachable, so percent/fillColor/fill cannot trip it —
confirmed. The absent-key rows the fixture dodged are covered: **CEL's raw
`attr["k"]` throws an evaluation error on a missing key**, so `attr["k"] != null` is NOT a null
check. The translations now gate with `'k' in attr` (documented in `CelShapes`), and with that
idiom the absent-key reads all agree byte-for-byte — the earlier run's `cel error:
evaluation error at <input>:…` rows vanish.

Phase 1 therefore inherits exactly these four deliberate margins: empty-string presence (2 rows),
number text (16, → stringifier decision in Dec. #2), one half-away step, error wording.

### Phase 1 — the engine swap ✅ done (2026-08-29)

- `com.google.cel` becomes a main dependency of `fh-datastar-view`; `Transform.parse` → CEL
  compile, `Transform.run` → planner-runtime eval (the `CelTransforms` pattern: compile once at
  validate, `createFrame`-free per-eval activation, stringify via `asString`). Validation stays
  the one gate; its error text becomes CEL's.
- **Re-author the shipped transform strings in CEL.** The component sources are the bounded set
  from the inventory below whose rows are JSONata today: `slider.pkl` (percent/fill/value/expr),
  `control.pkl` (the enum forms), `core/slot.pkl` (value slot + unit), more-info
  `each/sort/join`, every `c.expr`/`c.exprOf` in the demo boards and fixtures. Where the CEL
  translations in `CelShapes` already exist, they are the first draft. The `$dashboardSlug`
  binding becomes `dashboard_slug`. This is the step that changes rendered bytes; the readout
  expectations move to CEL-native values (`TransformSuite` "slider fill" → the CEL form and
  `…402%`).
- **Regen the contract, deliberately:** wire snapshots (`sbt dashboardSnapshotsUpdate`, then read
  the diff), visual baselines (CI's before/after), whatever names a transform string (signal
  names, once-cache keys) — all change ONCE and then stabilise.
- Signal/slot semantics, Mustache, the renderer's node walk, candidate sets: untouched (the
  change is inside the transform step, same dispatch shape).

### Phase 2 — re-target the catalog ✅ done (2026-08-29)

- **`Transform.Direct` is now `Transform.Simple`** with the closed form set,
  recognized over the CEL-canonical strings the re-authored library emits: the
  raw `state` read, the guarded attribute read (`'x' in attr ? attr['x'] : null`,
  whose fast path returns `""` for the absent key — the engine's NullValue arm,
  byte-identical), the parenthesized fallback-to-id name, the unit suffix, a
  literal prefix/suffix, the `state == 'x' ? 'a' : 'b'` enum, and the slider's
  range percent and fill (the Pkl-spliced `min`/`max` float literals are parsed
  out of the string; the repeat-occurrence guard checks the spliced min really
  appears twice). The fill colour and more-info's comprehension stay on the
  engine — they genuinely need the language.
- **`Transforms.run` tries `simple` first**; `runSimple: Option[String]`
  returns `None` on an unmodeled VALUE (a non-numeric position, a non-string
  unit) so the engine's bytes — error text included — win. A parity battery in
  TransformSuite runs every recognized form both ways over the hostile sweep
  (min edge, off-a-hair negative, fractional, absent, empty-string, odd types,
  a list-valued attr) and asserts byte-equality per value; TransformsSuite's
  boundary test pins the near-misses (the bare unparenthesized name form, an
  int literal where a float is spliced, a half-formed enum) to `None`.
- **No wire byte moved**: recognition is over the strings the library already
  bakes, so the snapshots are untouched by construction.
- **Benchmarks**: the retired-engine cells are gone; `RenderBench.simple`
  measures the production dispatch (fast tier + fallback) against the
  `cel` engine-only baseline and the `direct` raw-read floor. Measured
  (1200 evals/op): `simple` 1001 µs / 968.9 kB vs `cel` 1592 µs / 1074.2 kB —
  **-37% CPU / -10% allocation** on the mixed workload (4 of 6 shapes fast);
  the fast reads land at ~61 B vs ~895 B per engine eval, while percent/fill
  stay numToString-heavy on both paths. `direct`: 24 ns / 61 B per read.

## Inventory of shipped transform strings (unchanged surface, now the re-author list)

| Slot / shape | String shipped today (JSONata) | CEL translation (again) |
|---|---|---|
| raw `state` | `$state` | `state` |
| raw `attr(x)` | `$attr.<x>` | `attr["x"]` |
| name fallback | `$attr.<x> ? $attr.<x> : $entity_id` | `'x' in attr ? attr["x"] : entity_id` …<br/><sub>`attr["x"] != null` throws on an absent key (Phase 0), so presence is `'x' in attr`</sub> |
| unit suffix | `$state & ($attr.u ? " " & $attr.u : "")` | `'u' in attr ? state + ' ' + attr["u"] : state` … |
| literal | `$state & " W"` / `"lit" & $state` | `state + ' W'` / `'lit' + state` |
| percent | `($v := …; … $string($round(($v - m) * 100 / (M - m))) & " %" : "0 %")` | `CelShapes.TransformPercent` |
| fill | `$string(($v := …; … 100 - ((…)) : 100)) & "%"` *(stray-paren bug)* | `CelShapes.TransformFill` |
| enum | `$state = "on" ? "lit" : ""` … | `state == 'on' ? 'lit' : ''` … |
| fillColor | kelvin ramp (`$round`, `$each` of rgb) | `CelShapes.TransformFillColor` |
| attrLines | more-info `$each`/`$sort`/`$join` | `CelShapes.TransformAttrLines` (`str()` helper) |

`m`/`M` stay the Pkl-interpolated min/max literals. The fillColor ramp and attrLines stay on the
engine (they genuinely need the language) in both worlds.

## Tests & docs discipline (same commit as each phase's change)

- Phase 1: snapshot regen is the deliberate, laser-read diff of the sanctioned command; the
  `fixup` note in the module's CLAUDE.md (whose premise was "no snapshot churn because nothing
  changes") is not violated — this phase *means* to change bytes.
- `docs/terminology.md` **Slot**: a transform becomes "a CEL expression, or a recognized simple
  form". `docs/architecture-rendering-pipeline.md` gains the two-tier note and a corrected engine
  row. `Transform`/`Transforms` scaladocs state the catalog + parity contract.
- A new ADR lands *after* the swap (engine choice + CEL-native semantics + the fast-path
  catalog), per the repo routine.

## Phase 3 (decided 2026-08-30, building on `simple-transform-surface`): the structured transform surface

Fork (b) is chosen, sharpened by review: **the tier is an explicit opt-in on
the wire, and there is NO recognition machinery at all** — no regex, and no
engine fallback either. A slot carries either a CEL string (`transform`) or a
`Simple` structure (`simple`); the field IS the tier selection, and the two are
mutually exclusive (validated). The nine cases are the Phase-2 eval set,
unchanged, still atomic forms over one read: anything beyond them is CEL,
explicitly — the composite `{value, op, prefix, postfix}` micro-format is
deliberately NOT built.

- **Each case is DEFINED by its idiomatic CEL spelling**, documented on the
  case (scaladoc + the Pkl classes in `core/slot.pkl`); the parity battery in
  TransformSuite evaluates that spelling through the engine and pins
  **byte-equality** with the fast read over the hostile sweep — the mapping
  similarity check, as a test suite.
- **No engine fallback**: the opted-in tier owns its values. Where the engine
  would error on a mistyped value (`double("on")`, `' ' + 5`), the structure
  renders its absent-value form (`0 %`/`100%`, the state alone) — the
  documented divergence, itself pinned in the suite's divergence table. The
  numeric domain of percent/fill mirrors `double()` (string numbers included),
  so plausible values still agree byte-for-byte.
- **Naming** keys on the structure (`Simple.key`, e.g. `attr:brightness`,
  `percent:brightness:1.0:255.0`) — signal names for opted-in slots are
  structure-derived, not CEL-derived.
- **Validate stays the gate**: exclusivity (both tiers authored → error) and
  the degenerate-range check that `range()` used to own.
- **Authoring**: `core/simple.pkl` (module-as-namespace, the `c.tap` pattern, re-exported through
  the facade as `c.simple`) — the nine wire classes + `const` constructors (`state()`, `attr(x)`,
  `attrOrId(x)`, `unit(x)`, `prefix(l)`, `suffix(l)`, `enumOf(eq, then, else)`,
  `percent(x, min, max)`, `fill(x, min, max)`) and `Slot.simple`; `labelSlot`/`valueSlot`/
  `secondarySlot` accept a Simple alongside `String`/`Expr`.
- **Re-authoring ✅ done (2026-08-30)**: the slider's `value`/`percent`/`fill` slots and default
  state readout, `control`'s two enums, and `slot.pkl`'s auto-unit value + guarded secondary read
  now opt in; the slider's `percentExpr`/`valueExpr`/`minExpr`/`maxExpr` stay CEL strings as the
  documented splice surface. Snapshots regenerated and read: simple slots carry the structure
  beside the untouched `transform` default (16 unit, attr/fill/state/percent shapes across the
  fixtures). ADR 0028 records the decision.

## Out of scope

- Keeping any dashjoin/JSONata in production. Cross-entity reads (the existing
  `SlotSource.entityId` mechanism covers them — no new machinery). Author-facing composed reads
  (`round(n)` decimals, templated multi-read strings): catalog v1 is atomic forms over one read;
  anything new the library bakes lands only through review, and unrecognized strings are CEL
  engine work, same as today.