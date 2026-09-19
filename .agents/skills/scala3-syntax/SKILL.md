---
name: scala3-syntax
author: sanssushi
description: >
  Reference for writing valid Scala 3 code under a strict language
  profile (Scala 3.8, -source:future, -preview, -language:strictEquality,
  -Yexplicit-nulls). Use whenever writing or reviewing new Scala 3 source.
  Use ONLY for the Scala 3 *language itself*, NOT for 3rd party libraries.
  Consult this skill before emitting Scala 3 code — older training data routinely produces syntax that is
  deprecated or warned off under -source:future.
tags: ["scala"]
license: MIT
---

# Scala 3 Syntax Skill (3.8.x, strict profile)

Covers **modern Scala 3 syntax**, assuming the strict compiler profile
documented in
[references/compiler-flags.md](references/compiler-flags.md):

- Scala **3.8.4**
- `-source:future` — selects the newest source dialect; **warns on the old given
  / context-bound syntax** from Scala 3.0–3.5, so the SIP-64 syntax below is the
  *only* syntax to emit.
- `-preview` — enables officially-released preview features (e.g. `into`).
- `-language:strictEquality` — `==` / `!=` are only allowed between types that
  `derives CanEqual` (or via a `given CanEqual`).
- `-Yexplicit-nulls` — reference types are non-nullable; nullability is
  `T | Null`.
- A battery of `-W*` warnings surfaced by default (warnings, not hard errors —
  no `-Xfatal-warnings` — but treat each as a real issue to fix).

**Frame of reference:** Scala 3 as it is today — not a "Scala 2 → Scala 3"
diff guide. Familiar older forms — `sealed trait` + `case` ADTs,
`given T with`, `implicit def`, `_` wildcard types, `import x._` — no longer
hold.

## Quick reference

| Topic | Section |
|-------|---------|
| Enums & ADTs, `derives`, `Mirror` | [references/adts-and-deriving.md](references/adts-and-deriving.md) |
| `given` instances (SIP-64 new syntax) | [references/givens.md](references/givens.md) |
| `using` clauses, context bounds (`A: C as x`, `A: {C1, C2}`) | [references/context-bounds-and-using.md](references/context-bounds-and-using.md) |
| Union / intersection / match / opaque / type lambdas / poly func types / **named tuples** | [references/type-system.md](references/type-system.md) |
| Control syntax, indentation, top-level defs, `*` imports, `new`-less, **fewer braces** | [references/core-syntax.md](references/core-syntax.md) |
| Extension methods, `export`, trait params, `open`, `transparent` | [references/extension-exports-traits.md](references/extension-exports-traits.md) |
| Explicit nulls, flow typing, `unsafeNulls` | [references/explicit-nulls.md](references/explicit-nulls.md) |
| `inline`, `scala.compiletime`, quotes/reflection | [references/metaprogramming.md](references/metaprogramming.md) |
| `into` implicit conversions (SIP-71) | [references/type-system.md](references/type-system.md#into--implicit-conversions-without-a-language-import-sip-71-preview) |
| **Clause interleaving** (SIP-47) | [references/context-bounds-and-using.md](references/context-bounds-and-using.md#clause-interleaving-sip-47-standard-since-36) |
| The strict flag profile & warning → fix table | [references/compiler-flags.md](references/compiler-flags.md) |

## Common mistakes

| Wrong (deprecated / warned / won't compile) | Right (Scala 3.8, `-source:future`) |
|---|---|
| `sealed trait Color; case object Red extends Color` | `enum Color: case Red, Green, Blue` — see [adts-and-deriving.md](references/adts-and-deriving.md) |
| `given Ord[Int] with def compare ...` | `given Ord[Int]: def compare ...` — no `with` |
| `given listOrd[A: Ord]: Ord[List[A]] with ...` | `given [A: Ord] => Ord[List[A]]: ...` |
| `given foo: Context` (abstract given) | `given foo: Context = compiletime.deferred` (in a trait) |
| `def f[A: Monoid](xs: List[A]) = summon[Monoid[A]].unit` | `def f[A: Monoid as m](xs: List[A]) = m.unit` |
| `[T : Ord : Show]` (chained `:`) | `[T: {Ord, Show}]` |
| `def compare(a: T, b: T): Boolean = a == b` (under strict equality) | add `derives CanEqual` to the type, or `import scala.language.strictEqualEquality` only as a last resort |
| `s == null` / `case null` where `s: String \| Null` | don't write `s == null` in new code — lift to `Option(s)` at the boundary; see [explicit-nulls.md](references/explicit-nulls.md) |
| `val x = y:\n  Int` (ascription after fewer-braces) | put the `:` on the next line: `val x = y\n  : Int` |
| `xs.map { x => ... }` (braces around a block argument) | `xs.map: x => ...` — colon-argument (fewer braces, SIP-44) |
| `def f[A](a: A, b: B)` where `B` depends on `a` | clause interleaving: `def f[A](a: A)[B](b: B)` (SIP-47) |
| `import scala.language.implicitConversions` to use a `Conversion` | mark the target with `into[T]` / `into trait` (SIP-71, preview) |
| `List[_]` (wildcard type argument) | `List[?]` |
| `import scala.collection.immutable._` | `import scala.collection.immutable.*` |
| `val x: String = null` (under `-Yexplicit-nulls`) | `val x: String | Null = null` |
| `def find(...): String = { ... ; return "x" }` (non-local return) | `boundary: { ... ; break("x") }` via `scala.util.boundary` |
| `new Point(1, 2)` when `Point` has an apply | `Point(1, 2)` (creator application) |

## When to read which file

- **Writing any new `.scala`/`.sc` file** → skim
  [core-syntax.md](references/core-syntax.md) and
  [compiler-flags.md](references/compiler-flags.md) first.
- **Defining a typeclass instance** →
  [givens.md](references/givens.md) (all SIP-64 forms).
- **Adding a typeclass constraint on a type parameter** →
  [context-bounds-and-using.md](references/context-bounds-and-using.md).
- **Modelling a closed set of cases** →
  [adts-and-deriving.md](references/adts-and-deriving.md); prefer `enum` over
  `sealed trait` for new code.
- **An equality / `==` compile error** → strict equality; see
  [adts-and-deriving.md](references/adts-and-deriving.md#strict-equality-canequal)
  and [compiler-flags.md](references/compiler-flags.md).
- **A `null` / NPE / Java interop issue** →
  [explicit-nulls.md](references/explicit-nulls.md).
- **A `-Wunused` / `-Wvalue-discard` / `-Wnonunit-statement` warning** →
  [compiler-flags.md](references/compiler-flags.md).

## References

- Scala 3 Language Reference: <https://docs.scala-lang.org/scala3/reference/>
- All SIPs (status index): <https://docs.scala-lang.org/sips/all.html>
- Contextual Abstractions index: <https://docs.scala-lang.org/scala3/reference/contextual/>
- SIP-64 (Context Bounds & Givens): <https://docs.scala-lang.org/sips/64.html>
- `scala.compiletime` API: <https://scala-lang.org/api/3.8.0/scala/compiletime.html>
- Scala 3.8 release notes: <https://www.scala-lang.org/news/3.8/>
- Scala 3.6.2 release notes (SIP-64 stabilization): <https://www.scala-lang.org/news/3.6.2/>
- Scala 3 Syntax Summary: <https://docs.scala-lang.org/scala3/reference/syntax.html>

## Also shipped (niche; see the SIP index above for details)

Features that ship with Scala 3 but are rarely needed day-to-day. Reach for
them only when the use case clearly fits; the main reference files cover what
you need most of the time.

| SIP | What it adds | Doc |
|---|---|---|
| 23 | Literal singleton types (`1`, `"x"` as types) | <https://docs.scala-lang.org/scala3/reference/singleton-types.html> |
| 31 | By-name implicit arguments (context params) | <https://docs.scala-lang.org/sips/31.html> |
| 33 | Priority-based infix type precedence | <https://docs.scala-lang.org/sips/33.html> |
| 37 | Quote escapes in string interpolations | <https://docs.scala-lang.org/sips/37.html> |
| 38 | Converters among optional functions / `PartialFunction` / extractors | <https://docs.scala-lang.org/sips/38.html> |
| 53 | Explicit type variables in quote patterns | <https://docs.scala-lang.org/sips/53.html> |
| 57 | Replacing nonsensical `@unchecked` annotations | <https://docs.scala-lang.org/sips/57.html> |
| 62 | For-comprehension improvements (**preview**) | <https://docs.scala-lang.org/sips/62.html> |
