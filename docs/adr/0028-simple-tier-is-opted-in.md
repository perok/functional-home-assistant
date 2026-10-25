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

1. **The tier is the transform's FORM, not a spelling.** A slot's `transform`
   is ONE wire fact with two forms: a bare JSON string (a CEL expression — the
   engine tier) or a `Simple` structure as a JSON object (the fast tier). The
   form IS the tier selection; there is no recognition machinery at all, and
   nothing else on the slot describes its tier.

   **A condition is a `Simple` structure.** The `Predicate` a `c.iff`, a candidate set's guard
   or a boolean input takes (`Cmp`, `And`/`Or`/`Not`, `Count`, `First`) is the tier's bool half,
   so `transform = q.entity(e).stateIs("on")` is decided by `Conditions`, the one interpreter,
   with no wrapper object and no CEL. In Scala, `Transform.Simple` is `Simple.Value | Predicate`:
   one decoder, one key and one interpreter (`Transform.runSimpleValue`) over both halves.

   **The library writes no CEL the author did not.** A slot's default transform is
   `simple.state()`, an attribute with its unit is `AttrUnit`, a boolean input's condition is its
   slot's transform (ADR 0034), and `c.select` reads its current option with a `Cmp` and its label
   with a `Match`. CEL the library writes on purpose stays CEL: the clock, the slider's composed
   readouts, the colour wheel's hue, more-info's attribute list, the bar's history peak.
2. **A shape joins the catalog when it is a STATIC LOOKUP and TOTAL** — decided entirely at
   build time, and defined over every value a live entity can produce. Today that is
   `state`, `attr`, `suffixUnit`, `prefix`, `suffix`, `match`, `percent`, `fill`, `duration`.

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

   **Every read is of the slot's entity unless it names another** (`entity`, as a `Cmp` already
   did). A named entity joins the slot's live entities, so it wakes the slot, and ADR 0023's
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

   `match` is a lookup — `Map[String, SlotValue]` plus an `otherwise` — over a `property` (the
   state unless it says `attr:<name>`), and it REPLACED the
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

   An absent `otherwise` means the read itself, not "the cases are exhaustive": `c.select`'s
   label shows an option it does not know as itself. Nothing at this layer knows a domain's state
   vocabulary — only the vendored Pkl module does — so an exhaustiveness check cannot live here;
   and HA ADDS states (`open`/`opening` arrived in `lock` after the domain shipped), so an
   unmatched state must degrade rather than blank a card months later. A helper that covers every
   variant of a vendored state union belongs in the domain module, where the vocabulary is.
3. **`kind` names the shape, `op` the operator, and both levels are derived.** Pkl writes
   `{kind: "Op", op: "Percent", attribute, min, max}` and `{kind: "Match", cases, otherwise}`,
   each the Scala case name, as every discriminator on the wire is (ADR 0006); any other `kind`
   is a condition's. They are two
   fields because they answer two questions, and `kind` is the spelling `LayoutNode` and
   `Predicate` already use. In Scala, `Transform.Simple` is a sealed trait
   holding `Simple.Op`, an enum of the operators whose decoder is derived with discriminator
   `op`, and `Simple.Match`, a derived case class. The operator cases are exported onto `Simple`,
   so code still says `Simple.Percent`.

   The one hand-written line picks the level: it reads `kind` and hands the object to the `Op`,
   `Match` or `Predicate` decoder. circe's Scala 3 derivation cannot do it, because it flattens a nested sum
   to its innermost case, so the outer discriminator would have to name the operator
   (circe#2110, fixed that way by circe#2156). The other hand-written instance is for
   `SlotValue`, a union circe can't derive. It tries BOOLEAN first: `Decoder[String]` fails on a
   JSON boolean, and the narrower type goes first.

   Decoding straight into the runtime type, with no flatter wire form parsed afterwards, is what
   keeps these three:

   - The renderer keeps an **exhaustive match**, which is what stops a new shape quietly
     skipping the parity suite. Matching on a `String` operator would let one fall through to a
     default, green and unproven.
   - **No evaluation pays a map lookup** for an argument it could have read off a field.
   - A malformed structure is a **build-time failure naming the dashboard** rather than a slot
     that renders blank forever: a missing field, an unknown `kind` or an unknown `op` fails the
     decode, with its JSON path.

   In Pkl, each operator is a class of its own, named as its `Simple.Op` case and extending an
   abstract `SimpleValue` that fixes `kind`; its `op` is that same name. Each class requires
   exactly the fields its operator reads, so no argument is optional, and `WireShapeSuite` checks
   every class's fields and `op` against its Scala case. The `const function` constructors in `core/simple.pkl` are still the door
   authors use. `match` keeps a class of its own, because its payload is a Mapping whose arms
   may be Boolean.

   Rejected: a `params` map beside one String `value`. It needed a hand-written step after
   decoding, numeric strings included, and a decoder should only decode (#607); a field per
   argument needs no parser. `value` also meant an attribute for some operators and literal text
   for others, which is why the fields are now `attribute` and `literal`. Also rejected:
   - one `SimpleValue` class holding every operator's fields as optional, which Pkl could not
     check and `WireShapeSuite` could not compare with any single Scala case;
   - one `kind` per operator, which derives with no hand-written line but makes one field answer
     both questions.
4. **Each shape is defined on its own terms**, on its Scala case, and must be total. Where one
   has an idiomatic CEL spelling it is documented there, and `TransformSuite`'s battery evaluates
   that spelling through the engine, asserting **byte-equality with the fast read over the
   hostile sweep**, so a shape and the CEL an author would write never disagree. A shape with no
   CEL spelling — a named entity, a condition, `AttrUnit` (a number plus a string errors) — is
   held by tests of its own. The mapping is a test suite, not a runtime mechanism.
5. **No engine fallback: the opted-in tier owns its values.** Where the engine would ERROR on a
   mistyped value (`double("on")`, `' ' + 5`), the structure renders its absent-value form
   (`"0 %"`/`"100%"`, the state alone). The numeric domain of percent/fill mirrors `double()` —
   numbers and parseable strings — so plausible values still agree byte-for-byte. Each divergence
   is documented on its case and pinned in the suite's divergence tests.
6. **Naming keys on the structure.** Signal names and the once-cache hash `Transform.Simple.key`
   (`attr:brightness`, `percent:brightness:1.0:255.0`, `@sensor.b:attr:battery` for a named
   entity, `p:cmp(…)` for a condition, as an expression value's key spells it) — injective by
   construction, independent of any spelling, and never valid CEL, so no key is a CEL
   transform's. `simple.state()` and the CEL `state` share `state`, as they share every value. `match` is the one case whose key is LENGTH-PREFIXED: its siblings can join on
   `:` because their arity is fixed, and a variable-arity map joined that way would let a
   separator inside a key forge a different map. A collision is two transforms sharing one
   signal, so injectivity here is not a nicety.
7. **Authoring is a namespace: `core/simple.pkl`** (the `c.tap` module-as-namespace pattern, typed
   facade re-export `c.simple`). `Slot.transform` takes the structures; `labelSlot`/`valueSlot`/
   `secondarySlot` accept a Simple beside `String`/`Expr`. The components opt in: the slider's
   `value`/`percent`/`fill` slots and its default state readout, `control`'s two state matches, and
   `slot.pkl`'s own auto-unit value and guarded secondary read, the default transform, and every
   boolean input's condition. The slider's `percentExpr`/
   `valueExpr`/`minExpr`/`maxExpr` stay CEL strings deliberately — they are the splice surface for
   composed readouts, which no atomic shape covers.
8. **Validate stays the one gate.** A Simple structure is checked structurally
   — degenerate ranges (the check the recognizer's `range()` used to own) are
   build errors — and a CEL string must compile, located like any bad
   expression. Both tiers are validated by the same pass.

## Consequences

- **The wire changed once, deliberately**: an opted-in slot carries the
  structure AS its `transform` — an object where a CEL string used to sit.
  (The first cut shipped the structure as a parallel `simple` field beside the
  untouched string default; review collapsed it into the one union fact, the
  shape the design always named.) Snapshots regenerated and read.
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
