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

1. **The tier is each step's FORM, not a spelling.** A slot's value is a source and the steps run
   on it in order (`ValueExpr`, ADR 0006). A step is `Cel` (the engine tier) or one of the
   `Step.Simple` cases (the fast tier), each an object whose `kind` names it. The form IS the tier
   selection; there is no recognition machinery at all.

   **A condition is the tier's bool half.** What a `c.iff`, a candidate set's guard, an order term
   or a boolean input takes is a bool `ValueExpr` of simple steps: a read and `Compare`, `Not`,
   `AllOf`/`AnyOf` over a `List`, `Count`/`First` over a set's `Present` ids
   (`ValueExpr.isCondition`). So `q.entity(e).stateIs("on")` is decided by `Conditions`, the one
   interpreter, with no CEL; `core/predicate.pkl`'s classes (`Cmp`, `PAnd`, …) are how Pkl writes
   one.

   **The library writes no CEL the author did not.** A slot's default reading is the state, an
   attribute with its unit is the attribute and a `WithUnit` step, a boolean input's condition is
   its slot's source (ADR 0034), and `c.select` reads its current option with a `Cmp` and its label
   with a `Match`. CEL the library writes on purpose stays CEL: the clock, the slider's composed
   readouts, the colour wheel's hue, more-info's attribute list, the bar's history peak.
2. **A shape joins the catalog when it is a STATIC LOOKUP and TOTAL** — decided entirely at
   build time, and defined over every value a live entity can produce. Today that is
   `Prefix`, `Suffix`, `WithUnit`, `Percent`, `Fill`, `Duration`, `Match` and `Count`, over a
   source that reads the state, an attribute, a condition or the candidates present.

   Totality is the load-bearing half, and it is why the rule is not "the hot shapes". CEL's
   `double(state)` ERRORS on `unknown`, and a CEL error renders as its own message — on a wall
   panel, months after anyone read the dashboard source. A shape whose garbage case has an
   honest rendering (`0 %`, `100%`, the empty string) is worth having here even when it is
   nowhere near a hot path.

   Speed is the other half, and it is narrower than it first looks: a drag paints the slider's
   fill client-side, so the shapes that evaluate at volume are the ones a WHOLE-DASHBOARD render
   touches — page load, reconnect, repaint — where every slot on every card resolves at once.
   That cost is uniform across shapes, which is an argument for the tier covering what it can
   rather than for ranking members by frequency.

   Anything failing either half — arithmetic over two reads, a composed string, a decision the
   live value drives beyond a comparison — is CEL, explicitly. That is what keeps this from
   growing back into a micro-language.

   **Every read is of the slot's entity unless it names another** (`Value.Entity.of`, as a `Cmp`
   already did); a step's operand is a value too, a literal or a read. A named entity joins the slot's live entities, so it wakes the slot, and ADR 0023's
   bound; one the store does not hold reads as an empty state, the shape's absent form. Reading
   one entity was a limit of CEL's bindings, not of the shapes.

   `attrOrId` was dropped: no component ever called it, and it carried a Pkl class, a decode
   arm, a key arm, an evaluation arm and a parity row for nothing. The benchmark that justified
   this tier had been measuring it as its headline probe.

   `duration` is the clearest case the rule admits, and the reason the rule is not about speed.
   It reads the STATE numerically — the only operator that does, which is what a duration sensor
   IS — scales it by the seconds its unit is worth, and renders `4h 13m` / `13m` / `45s`. A
   remaining-time sensor ticks about once a minute, so it is nowhere near a hot path; what earns
   it a place is that `double('unknown')` ERRORS, and `unknown` is exactly what an appliance
   between programmes reports. The empty string is the honest answer there — `0s` would claim it
   had just finished — and only a total operator can give it.

   Two further reasons it is not a CEL string in whichever card wants it:

   - **The scale cannot be baked into a spelling.** HA's `duration` device class fixes no unit,
     so the same "45 minutes left" arrives as `45` from one appliance and `2700` from another.
     A card inlining the CEL would inline one integration's unit and be right by luck.
   - **It is domain-free.** Every `duration` sensor in the house wants exactly this, so the
     alternative is the same twenty-line ternary copied per card — the duplication the tier
     exists to end, and the shape most likely to be copied slightly wrong.

   Its resolution is deliberately coarser than the reading: no seconds tier above a minute,
   because an appliance updates a remaining time on its own slow schedule and finer digits would
   be invented precision that ticks in jumps. That is a rendering decision the catalog owns, so
   every card renders a duration the same way.

   `match` is a lookup — `Map[String, Lit]` plus an `otherwise` — over the value before it,
   whatever the source read, and it REPLACED the
   two-armed `enum`, which is `match` with one entry. The count did not move, and the shapes it
   bought are ones no two-armed test could reach: HA's `isWaiting` (three states, one value), a
   state-derived icon class (arms to different values), and a `jammed` look. A `Map` rather than
   an ordered list of arms, because the test is equality: nothing about it is sequential, so a
   duplicate key and a first-match-wins question are unrepresentable rather than undefined.

   Its values are `String | Boolean`, which makes `match`, beside a condition, the shape that
   produces a real BOOLEAN — the only value that can turn a boolean attribute off (ADR 0017). A dedicated
   membership case (`stateIn`) was considered and rejected in Scala: it would be a second
   state-to-value mechanism beside this one, and it could not express the inverse
   (`otherwise = true`) without a third. It survives as Pkl SUGAR over `matchOf`, which is
   spelling and not mechanism. The arms must be all Strings or all booleans, checked by
   `Dashboard.validate` — CEL requires one type across a map's values and both ternary arms, so
   a mixed lookup has no idiomatic spelling to be equivalent to.

   `otherwise` is required, and "the cases are exhaustive" is not an option: nothing at this
   layer knows a domain's state vocabulary — only the vendored Pkl module does — so an
   exhaustiveness check cannot live here; and HA ADDS states (`open`/`opening` arrived in `lock`
   after the domain shipped), so an unmatched state must degrade rather than blank a card months
   later. A helper that covers every variant of a vendored state union belongs in the domain
   module, where the vocabulary is. `otherwise` may be a value shape rather than a literal, read
   off the same entity: `c.select`'s label passes the read of its own property, so an option it
   has no label for shows as itself. That is spelled, not implied — an absent `otherwise` meaning
   "the read" was built and dropped, because forgetting one then showed raw states silently and
   turned a boolean match into text. A read there is text, so it counts as a String arm.
3. **`kind` names the step, derived.** Pkl writes `{kind: "Percent", min, max}` and
   `{kind: "Match", cases, otherwise}`, each the Scala case name, as every discriminator on the
   wire is (ADR 0006). In Scala, `Step` is a sealed trait over `Cel`, `Simple` (the fast tier,
   `Count` included) and `Stage` (`Chart`, `ThemeClasses`, which the renderer runs), derived with
   discriminator `kind`; circe's Scala 3 derivation flattens the nested sums to their cases, which
   is the one-level wire wanted.

   Decoding straight into the runtime type, with no flatter wire form parsed afterwards, is what
   keeps these three:

   - The renderer keeps an **exhaustive match**, which is what stops a new step quietly skipping
     its tests. Matching on a `String` operator would let one fall through to a default, green
     and unproven.
   - **No evaluation pays a map lookup** for an argument it could have read off a field; a
     pipeline is composed into one function per plan (`Renderer.compose`).
   - A malformed structure is a **build-time failure naming the dashboard** rather than a slot
     that renders blank forever: a missing field, an unknown field or an unknown `kind` fails the
     decode, with its JSON path.

   In Pkl, each step is a class of its own in `core/simple.pkl`, named as its case (`CountStep`
   and `ThemeClassesStage` where a bare name would clash), and requires exactly the fields it
   reads, so no argument is optional; `WireShapeSuite` checks every class's fields and `kind`
   against its Scala case. The `const function` constructors are still the door authors use.

   Rejected: a `params` map beside one String `value`. It needed a hand-written step after
   decoding, numeric strings included, and a decoder should only decode (#607); a field per
   argument needs no parser. Also rejected: one class holding every step's fields as optional,
   which Pkl could not check and `WireShapeSuite` could not compare with any single Scala case.
4. **Each step is defined on its own terms**, on its Scala case, and must be total. Where one has
   an idiomatic CEL spelling it is documented there, and `TransformSuite`'s battery evaluates that
   spelling through the engine, asserting **byte-equality with the fast read over the hostile
   sweep**, so a step and the CEL an author would write never disagree. A step with no CEL
   spelling — an operand naming an entity, `WithUnit` over a number (a number plus a string
   errors), `Count` — is held by tests of its own. The mapping is a test suite, not a runtime mechanism.
5. **No engine fallback: the opted-in tier owns its values.** Where the engine would ERROR on a
   mistyped value (`double("on")`, `' ' + 5`), the structure renders its absent-value form
   (`"0 %"`/`"100%"`, the state alone). The numeric domain of percent/fill mirrors `double()` —
   numbers and parseable strings — so plausible values still agree byte-for-byte. Each divergence
   is documented on its case and pinned in the suite's divergence tests.
6. **Naming keys on the structure.** Signal names and the once-cache hash `ValueExpr.key`, the
   source's key and each step's (`attr:brightness|percent(lit:d:1.0,lit:d:255.0)`,
   `@sensor.b:attr:battery` for an operand naming an entity, `p:…` for a condition) — injective by
   construction and independent of any spelling. A plain state read keys as `state`, which the
   signal path spells out. Free text is LENGTH-PREFIXED (a CEL source, `match`'s map): a
   fixed-arity step can join on `:`, and a variable-arity one joined that way would let a
   separator inside a key forge another. A collision is two values sharing one signal, so
   injectivity here is not a nicety.
7. **Authoring is a namespace: `core/simple.pkl`** (the `c.tap` module-as-namespace pattern, typed
   facade re-export `c.simple`). `Reading.steps` takes the steps; `labelSlot`/`valueSlot`/
   `secondary` accept a reading beside `String`/`Expr`. The components opt in: the slider's
   `value`/`percent`/`fill` slots and its default state readout, `control`'s two state matches, and
   `slot.pkl`'s own auto-unit value and guarded secondary read, the default reading, and every
   boolean input's condition. The slider's `percentExpr`/
   `valueExpr`/`minExpr`/`maxExpr` stay CEL strings deliberately — they are the splice surface for
   composed readouts, which no atomic shape covers.
8. **Validate stays the one gate.** A simple step is checked structurally — degenerate ranges are
   build errors — and a CEL step must compile, located like any bad expression. Every step's type
   is checked in order (`ValueType`), so a `Percent` after a list fails the build. Both tiers are
   validated by the same pass.

## Consequences

- **The wire carries the structure**: an opted-in slot's steps are objects where a CEL string
  would sit. Snapshots regenerated and read.
- **Rendered bytes are unchanged for every well-typed value.** The only visible change is the
  documented one: a mistyped value now renders the absent-value form instead of the engine's error
  text — visible diagnosis traded for tier ownership, pinned in the suite.
- **A shape beyond the catalog cannot sneak into the fast tier** — there is nothing to fool. An
  unknown `kind` is a decode failure, not a dead slot. Growth is a catalog decision,
  made here and gated by review.
- **Opted-in slots' signal names changed once** (structure keys, not CEL hashes) and are stable
  from here; re-indenting or re-spelling any CEL in the library can no longer move a tier or a
  name.
- `RenderBench.simple` measures the explicit mix the production dispatch walks, and its opted-in
  probes are now the shapes the library ACTUALLY emits, weighted as it emits them — the state
  lookup first (icons, taps, inert guards, the toggle), then the raw state, then the guarded attr
  read, then the slider's range pair. The earlier set led with `attrOrId`, which no component has
  ever called.
