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

   Anything failing either half — a second operator, a cross-entity read, a decision the live
   value drives — is CEL, explicitly. That is what keeps this from growing back into a
   micro-language.

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

   `match` is a lookup — `Map[String, SlotValue]` plus a required `otherwise` — and it REPLACED the
   two-armed `enum`, which is `match` with one entry. The count did not move, and the shapes it
   bought are ones no two-armed test could reach: HA's `isWaiting` (three states, one value), a
   state-derived icon class (arms to different values), and a `jammed` look. A `Map` rather than
   an ordered list of arms, because the test is equality: nothing about it is sequential, so a
   duplicate key and a first-match-wins question are unrepresentable rather than undefined.

   Its values are `String | Boolean`, which makes `match` the shape that produces a real
   BOOLEAN — the only value that can turn a boolean attribute off (ADR 0017). A dedicated
   membership case (`stateIn`) was considered and rejected in Scala: it would be a second
   state-to-value mechanism beside this one, and it could not express the inverse
   (`otherwise = true`) without a third. It survives as Pkl SUGAR over `matchOf`, which is
   spelling and not mechanism. The arms must be all Strings or all booleans, checked by
   `Dashboard.validate` — CEL requires one type across a map's values and both ternary arms, so
   a mixed lookup has no idiomatic spelling to be equivalent to.

   `otherwise` is REQUIRED and is not an `Option` meaning "the cases are exhaustive". Nothing at
   this layer knows a domain's state vocabulary — only the vendored Pkl module does — so that
   check cannot live here; and HA ADDS states (`open`/`opening` arrived in `lock` after the
   domain shipped), so an unmatched state must degrade rather than blank a card months later. A
   helper that covers every variant of a vendored state union belongs in the domain module,
   where the vocabulary is.
3. **Flat on the wire, typed in the renderer.** The wire and Pkl form is TWO shapes, not one per
   case: `SimpleValue {kind: "value", op, value, params}` — an operator name, its single String
   argument, and whatever else it needs — and `SimpleMatch {kind: "match", cases, otherwise}`
   beside it.

   **`kind` says which SHAPE and `op` says which OPERATOR**, and they are two fields rather than
   one because they answer two questions. `kind` is also the spelling `LayoutNode` and
   `Predicate` already use, so the wire reads consistently. The practical forcing function is
   circe: its derivation maps one CONSTRUCTOR NAME to one fixed discriminator string
   (`Configuration.withTransformConstructorNames`), so an `op` that varies per operator cannot
   also select the case. Overloading one field buys ~16 bytes a slot and costs the derivation —
   and with it the good error, since a bad `kind` then falls into `Value` and reports a
   confusing unknown-op instead of an unknown shape.

   `match` cannot flatten: its payload is a Mapping plus arms that may be Boolean, and forcing
   that into an argument list would mean encoding a table inside a field — a parser, which is
   the thing this tier exists to avoid. Two shapes is the honest floor, and `Simple` is their
   union.

   `Transform.SimpleWire` is that pair as a Scala `enum`, decoded by `ConfiguredDecoder.derived`
   — the ONLY hand-written instances are the two UNION types circe has no generic story for
   (`SlotValue`, and a `params` entry). `SimpleWire.toSimple` then parses it into the runtime
   `Simple` ONCE, applied with `emap` so a `Left` becomes a decoding failure carrying the JSON
   path. The wire form does not reach the renderer, and that is the point:

   - The renderer keeps an **exhaustive match**, which is what stops a new shape quietly
     skipping the parity suite. Matching on a `String` op would let one fall through to a
     default, green and unproven.
   - **No evaluation pays a map lookup** for an argument it could have read off a field.
   - A malformed structure is a **build-time failure naming the dashboard** rather than a slot
     that renders blank forever.

   The authoring safety the flat form appears to cost is not actually lost: the `const function`
   constructors in `core/simple.pkl` are typed, they are the only door (nothing constructs these
   classes directly and nothing amends one), and `toSimple`'s `Left` catches what hand-written
   JSON could still smuggle past them.

   The two union decoders are where the care goes, because neither is derivable. A `SlotValue`
   arm tries BOOLEAN first — `Decoder[String]` fails on a JSON boolean, and the narrower type
   always goes first. A `params` entry is decided on the JSON's OWN shape rather than by an
   ordered `or` at all: circe's numeric decoders accept a JSON string that parses as a number, so
   `Double`-first would silently turn a genuine string argument into a Double.
4. **Each case is DEFINED by its idiomatic CEL spelling** — documented on the Scala case and on
   the Pkl constructor — and `TransformSuite`'s battery evaluates that spelling through the engine,
   asserting **byte-equality with the fast read over the hostile sweep**. The mapping is a test
   suite, not a runtime mechanism.
5. **No engine fallback: the opted-in tier owns its values.** Where the engine would ERROR on a
   mistyped value (`double("on")`, `' ' + 5`), the structure renders its absent-value form
   (`"0 %"`/`"100%"`, the state alone). The numeric domain of percent/fill mirrors `double()` —
   numbers and parseable strings — so plausible values still agree byte-for-byte. Each divergence
   is documented on its case and pinned in the suite's divergence tests.
6. **Naming keys on the structure.** Signal names and the once-cache hash `Transform.Simple.key`
   (`attr:brightness`, `percent:brightness:1.0:255.0`) — injective by construction, independent of
   any spelling. `match` is the one case whose key is LENGTH-PREFIXED: its siblings can join on
   `:` because their arity is fixed, and a variable-arity map joined that way would let a
   separator inside a key forge a different map. A collision is two transforms sharing one
   signal, so injectivity here is not a nicety.
7. **Authoring is a namespace: `core/simple.pkl`** (the `c.tap` module-as-namespace pattern, typed
   facade re-export `c.simple`). `Slot.transform` takes the structures; `labelSlot`/`valueSlot`/
   `secondarySlot` accept a Simple beside `String`/`Expr`. The components opt in: the slider's
   `value`/`percent`/`fill` slots and its default state readout, `control`'s two state matches, and
   `slot.pkl`'s own auto-unit value and guarded secondary read. The slider's `percentExpr`/
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
  `op` with no `toSimple` arm is a decode failure, not a dead slot. Growth is a catalog decision,
  made here and gated by review.
- **Opted-in slots' signal names changed once** (structure keys, not CEL hashes) and are stable
  from here; re-indenting or re-spelling any CEL in the library can no longer move a tier or a
  name.
- `RenderBench.simple` measures the explicit mix the production dispatch walks, and its opted-in
  probes are now the shapes the library ACTUALLY emits, weighted as it emits them — the state
  lookup first (icons, taps, inert guards, the toggle), then the raw state, then the guarded attr
  read, then the slider's range pair. The earlier set led with `attrOrId`, which no component has
  ever called.
