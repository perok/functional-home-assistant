# Explicit Nulls (`-Yexplicit-nulls`)

With `-Yexplicit-nulls`, **reference types are non-nullable**. `Null` is
removed from the bottom of the `AnyRef` hierarchy, so `val x: String = null`
does not compile. Nullability is expressed explicitly as a union with `Null`.

> **Guiding rule.** `T | Null` is a *boundary* type: use it only at Java/legacy
> edges or where an API genuinely returns `null`. For modeling absence in new
> code, prefer `Option[T]` — it composes, is non-nullable itself, and avoids
> the strict-equality friction below. Convert at the boundary:
> `Option(nullableValue)` lifts `T | Null` to `Option[T]`.

## Nullable types

```scala
val name: String | Null = null     // ok
val nick: String = "ace"           // ok — non-null
// val bad: String = null          // error: Null is not a subtype of String
```

`String`, `Array`, `List`, and every `AnyRef` subclass is non-nullable by
default. To accept or produce `null`, write `T | Null`.

## Narrowing nullable values

The idiomatic narrowing under both `-Yexplicit-nulls` **and**
`-language:strictEquality` is to lift to `Option` at the boundary, then work
with a non-nullable value everywhere downstream:

```scala
def length(s: String | Null): Int =
  Option(s).map(_.length).getOrElse(0)
```

`Option(s)` accepts `T | Null` and yields `Some(s)` (narrowed to `T`) or
`None`.

### Why not `s == null`?

A bare `if s == null` or `case null` *can* be made to compile under strict
equality by hand-writing `given CanEqual[Null, T | Null]` (and, for `case
null`, the reverse direction). **Don't do this in new code** — sprinkling
`CanEqual[Null, ...]` instances is a code smell that signals the nullable
type is leaking too far. If you find yourself reaching for `s == null`, lift
to `Option` earlier instead.

Note: `case _: Null` does **not** compile (`class Null cannot be used in
runtime type tests`). Flow narrowing after a null check applies only to simple
variable reads — not across method calls or aliased state.

## Java interop: nullable by default, `NotNull` annotations

Java methods are seen as returning `T | Null` unless annotated. Common
annotations recognized: `@NotNull`, `@NonNull`, `@Nonnull`, `@Nullable`
(those flip back to nullable). `@throws` and exception types are unaffected.

```scala
// java: String getenv(String name);           // unannotated
val v: String | Null = System.getenv("HOME")   // nullable
```

## `unsafeNulls` — escape hatch for legacy code

`import scala.language.unsafeNulls` re-enables implicit `T ↔ T | Null`
conversion for the importing scope (so old code that treats `T` as nullable
still compiles). It is intended **for migration only**:

```scala
import scala.language.unsafeNulls   // this file treats T as nullable

val x: String = maybeNullString     // compiles; unsafe
```

Global escape hatch: `-language:unsafeNulls` (with `-Yexplicit-nulls`) makes
the whole project behave as if nulls were universal — useful for a first-pass
port, then drop it file-by-file and model nullability explicitly.

Recommended adoption: enable `-Yexplicit-nulls -language:unsafeNulls` first;
fix errors; then remove `-language:unsafeNulls` and add the import only at true
Java/legacy boundaries.

## Common patterns

```scala
// Non-nullable by construction; null never stored
final case class Config(name: String, retries: Int)

// Nullable external input → narrow at the boundary
def parse(raw: String | Null): Option[String] =
  Option(raw)                       // Option(...) lifts T | Null to Option[T]
```

`Option(x)` where `x: T | Null` yields `Some(x)` narrowed, or `None`.

## References

- Explicit Nulls: <https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html>
- Flow-typing and Java interop (same page): see "Track the null" and "Java interop" sections
- `scala.language.unsafeNulls`: <https://scala-lang.org/api/3.8.0/scala/language$.html>
