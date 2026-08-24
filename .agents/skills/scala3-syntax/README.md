# scala3-syntax

Reference for writing valid **modern Scala 3** code under a strict
compiler profile. Covers syntax current as of Scala 3.8.x under
`-source:future`, and flags the deprecated / warned forms
that older training data still emits.

## What it covers

| Topic | Reference |
|-------|-----------|
| Enums & ADTs, `derives`, `Mirror` | [references/adts-and-deriving.md](references/adts-and-deriving.md) |
| `given` instances (SIP-64 new syntax) | [references/givens.md](references/givens.md) |
| `using` clauses, context bounds (`A: C as x`, `A: {C1, C2}`) | [references/context-bounds-and-using.md](references/context-bounds-and-using.md) |
| Union / intersection / match / opaque / type lambdas / poly func types / named tuples | [references/type-system.md](references/type-system.md) |
| Control syntax, indentation, top-level defs, `*` imports, `new`-less, fewer braces | [references/core-syntax.md](references/core-syntax.md) |
| Extension methods, `export`, trait params, `open`, `transparent` | [references/extension-exports-traits.md](references/extension-exports-traits.md) |
| Explicit nulls, flow typing, `unsafeNulls` | [references/explicit-nulls.md](references/explicit-nulls.md) |
| `inline`, `scala.compiletime`, quotes/reflection | [references/metaprogramming.md](references/metaprogramming.md) |
| `into` implicit conversions (SIP-71) | [references/type-system.md](references/type-system.md#into--implicit-conversions-without-a-language-import-sip-71-preview) |
| Clause interleaving (SIP-47) | [references/context-bounds-and-using.md](references/context-bounds-and-using.md#clause-interleaving-sip-47-standard-since-36) |
| The strict flag profile & warning → fix table | [references/compiler-flags.md](references/compiler-flags.md) |

## Strict compiler profile assumed

- Scala **3.8.4**
- `-source:future` — warns on the old given / context-bound syntax from Scala 3.0–3.5; only SIP-64 syntax should be emitted.
- `-preview` — enables officially-released preview features (e.g. `into`).
- `-language:strictEquality` — `==` / `!=` only allowed between types that `derives CanEqual`.
- `-Yexplicit-nulls` — reference types are non-nullable; nullability is `T | Null`.

## Use when

- Writing or reviewing any new Scala 3 source file.
- Defining a typeclass instance or adding a typeclass constraint.
- Modelling a closed set of cases (prefer `enum` over `sealed trait`).
- Hitting an equality / `==` compile error under strict equality.
- Dealing with `null` / NPE / Java interop under explicit nulls.
- Triaging `-Wunused` / `-Wvalue-discard` / `-Wnonunit-statement` warnings.

## Scope

This skill covers the **Scala 3 language itself**, not 3rd-party libraries.

## Files

```
scala3-syntax/
  SKILL.md          # agent-facing instruction doc (loaded on demand)
  README.md         # this file (human overview)
  references/       # 9 topical reference .md files
```

The full instruction content lives in [SKILL.md](SKILL.md); the reference files
are pulled into context by the agent when the relevant topic comes up.
