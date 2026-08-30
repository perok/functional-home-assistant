# ADR 0028 — The simple tier is opted into, not recognized

- **Status:** Accepted
- **Date:** 2026-08-30
- **Scope:** `modules/fh-datastar-view`
- **Refines:** ADR 0027 (transforms are CEL), which shipped the fast tier as a recognition.

## Context

Phase 2 (ADR 0027) shipped the `Transform.Simple` fast tier as a **recognition over the canonical
CEL strings** the re-authored library bakes: byte-anchored regex shapes, a parity battery, and a
fallback contract (`runSimple` → `None` meant the engine's bytes — error text included — won).
It worked, and no wire byte moved. Three costs followed from the design itself:

- **The tier selection was invisible.** Whether a slot rode the fast path depended on its
  expression matching an anchored spelling — an author (and a reviewer) could not see it, and a
  cosmetic re-spell silently moved a slot between tiers.
- **Every value had two implementations on the hook.** The `None`-fallback meant percent and unit
  shapes were half fast-path, half engine, wherever a value went unmodeled.
- **Each shape cost a regex.** The recognizer grew anchored patterns, with guard-read agreement
  checks and float-literal parsing, all to detect strings we ourselves had spliced one page
  earlier.

## The decision

1. **The tier is a field, not a spelling.** A slot carries either a CEL string
   (`SlotSource.transform`) or a `Simple` structure (`SlotSource.simple`) — mutually exclusive,
   rejected by validate if both are authored. There is no recognition machinery at all.
2. **The catalog is unchanged and stays closed.** The same nine atomic forms over one read
   (`state`, `attr`, `attrOrId`, `unit`, `prefix`, `suffix`, `enum`, `percent`, `fill`). The
   composite `{value, op, prefix, postfix}` micro-format was considered and rejected: it reopens
   the language Phase 2 closed. Anything beyond the nine is CEL, explicitly.
3. **Each case is DEFINED by its idiomatic CEL spelling** — documented on the Scala case and on
   the Pkl wire class — and `TransformSuite`'s battery evaluates that spelling through the engine,
   asserting **byte-equality with the fast read over the hostile sweep**. The mapping is a test
   suite, not a runtime mechanism.
4. **No engine fallback: the opted-in tier owns its values.** Where the engine would ERROR on a
   mistyped value (`double("on")`, `' ' + 5`), the structure renders its absent-value form
   (`"0 %"`/`"100%"`, the state alone). The numeric domain of percent/fill mirrors `double()` —
   numbers and parseable strings — so plausible values still agree byte-for-byte. Each divergence
   is documented on its case and pinned in the suite's divergence tests.
5. **Naming keys on the structure.** Signal names and the once-cache hash `Transform.Simple.key`
   (`attr:brightness`, `percent:brightness:1.0:255.0`) — injective by construction, independent of
   any spelling.
6. **Authoring is a namespace: `core/simple.pkl`** (the `c.tap` module-as-namespace pattern, typed
   facade re-export `c.simple`). `Slot.simple` takes the structures; `labelSlot`/`valueSlot`/
   `secondarySlot` accept a Simple beside `String`/`Expr`. The components opt in: the slider's
   `value`/`percent`/`fill` slots and its default state readout, `control`'s two enums, and
   `slot.pkl`'s own auto-unit value and guarded secondary read. The slider's `percentExpr`/
   `valueExpr`/`minExpr`/`maxExpr` stay CEL strings deliberately — they are the splice surface for
   composed readouts, which no atomic shape covers.
7. **Validate stays the one gate.** Double-tier slots and degenerate ranges (the check the
   recognizer's `range()` used to own) are build errors, located like bad CEL.

## Consequences

- **The wire changed once, deliberately**: an opted-in slot carries the structure as an object
  beside the untouched `transform: "state"` default. Snapshots regenerated and read.
- **Rendered bytes are unchanged for every well-typed value.** The only visible change is the
  documented one: a mistyped value now renders the absent-value form instead of the engine's error
  text — visible diagnosis traded for tier ownership, pinned in the suite.
- **A shape beyond the nine cannot sneak into the fast tier** — there is nothing to fool. Growth
  is a catalog decision, made here and gated by review.
- **Opted-in slots' signal names changed once** (structure keys, not CEL hashes) and are stable
  from here; re-indenting or re-spelling any CEL in the library can no longer move a tier or a
  name.
- `RenderBench.simple` measures the explicit mix (4 opted-in shapes + 2 engine shapes) the
  production dispatch now walks.
