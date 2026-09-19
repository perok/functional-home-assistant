# Type System Features

## Union types `A | B`

An instance of `A | B` is an instance of `A` **or** `B`. No common supertype
is synthesized. Pattern match to recover the specific type:

```scala
def format(v: Int | String): String = v match
  case i: Int    => i.toString
  case s: String => s
```

Union types are how `Option`, `Either`, and error types compose without
allocating wrappers at the type level. Use `Option[T]` rather than `T | Null`
for genuine optionality — `T | Null` is reserved for the explicit-nulls regime
(see [explicit-nulls.md](explicit-nulls.md)).

## Intersection types `A & B`

An instance of `A & B` is an instance of **both** `A` and `B`. Use to combine
traits without defining a named mixin:

```scala
trait Named: def name: String
trait Aged:  def age: Int
def info(x: Named & Aged): String = s"${x.name}, ${x.age}"
```

Prefer `A & B` over a one-off `trait Both extends A with B` when the combined
type is only used in a signature.

## Match types

A match type performs **type-level** computation by structural matching on a
type:

```scala
type Elem[X] = X match
  case String      => Char
  case Array[t]    => t
  case Option[t]   => t
  case Iterable[t] => t
  case Any         => X

val c: Char   = summon[Elem[String] =:= Char]      // resolves to Char
val i: Int    = summon[Elem[Array[Int]] =:= Int]
```

Match types are evaluated by the compiler and can drive `inline` dispatch
(see [metaprogramming.md](metaprogramming.md)). They replace most Scala 2
type-level implicit encodings.

## Named tuples (SIP-58, standard since 3.7)

Tuple elements can be named — a lightweight alternative to a one-off `case
class`. **No language import is needed** (the old `language.experimental
.namedTuples` import is deprecated since 3.7).

```scala
type Person = (name: String, age: Int)
val bob: Person = (name = "Bob", age = 33)

bob.name      // "Bob"  — field selection by name
bob.age       // 33
```

- Element types use `name: Type`; values use `name = value`. Mixing named and
  unnamed elements in one tuple is illegal; names must be unique.
- A **regular tuple conforms to a named tuple type** (unnamed `<: named):
  `val p: Person = ("Laura", 25)` compiles.
- The other direction needs an explicit `.toTuple` (or is inserted where the
  expected type is a regular tuple, but not inside type constructors).

### Named patterns

Match a subset of case-class fields by name, in any order:

```scala
case class City(zip: Int, name: String, population: Int) derives CanEqual
val c = City(1026, "London", 9000000)
c match
  case City(name = n) => n        // only the field you care about
```

### `NamedTuple.From` — case class → named tuple

`NamedTuple.From[C]` is the named tuple of a case class's first parameter
section, computed at the type level (no macro):

```scala
case class City(zip: Int, name: String, population: Int)
val nt: NamedTuple.From[City] = (zip = 1, name = "x", population = 2)
nt.name   // "x"
```

`Selectable` subtypes can set `type Fields = NamedTuple.From[T]` (or any named
tuple) to expose computed, typed field selections without reflection.

## Opaque type aliases

`opaque type` creates a zero-cost newtype: distinct to the type system,
identical at runtime (no boxing). The alias is visible **only inside the same
scope** (typically the companion object), preserving the abstraction barrier
externally.

```scala
object Meter:
  opaque type Meter = Double
  def apply(d: Double): Meter = d
  extension (m: Meter) def toDouble: Double = m

val m: Meter = Meter(3.0)
m.toDouble          // 3.0 — extension method
// m + 1           // error: Meter is not a Double outside the object
```

Opaque types supersede Scala 2 value classes for newtypes. Put the constructors
and extension methods in the companion.

## Type lambdas

A type-level anonymous function over a higher-kinded type parameter:

```scala
type ListOption[A] = Option[List[A]]            // convenience alias

// A true type lambda usable where a higher-kinded type is expected:
type F = [X] =>> Option[X]                       // F is a type constructor
def mapF[F[_], A](fa: F[A]): F[A] = fa
val r = mapF[[X] =>> Option[X], Int](Some(1))    // inline type lambda
```

`[X] =>> F[X]` is the syntax. No `kind-projector` plugin is needed — write
`[A] =>> F[A]` instead of `Lambda[A => F[A]]`.

## Polymorphic function types

A function type that itself takes **type arguments** (first-class
parametricity):

```scala
type IdFunc = [A] => A => A
val id: IdFunc = [A] => (a: A) => a
def applyTwice(f: [A] => A => A, x: Int): Int = f(f(x))
```

The `[A] =>` prefix is the type-level lambda for the *function* type; the value
is `[A] => (a: A) => a`.

## Dependent function types

When a return type depends on a value parameter, you can name the parameter in
the function type:

```scala
class Entry: type Key; type Value
type Selector = (e: Entry) => e.Key
```

## Wildcard type arguments: `?`, not `_`

Type-level wildcards use `?`:

```scala
def size(c: collection.Seq[?]): Int = c.size
val m: Map[String, ?] = Map.empty
```

`_` is **not** a wildcard type argument (it is reserved / warns). Use `?`. (`_`
still appears in type-lambda parameter syntax `[X] =>> F[X]` and in patterns —
those are unaffected.)

## `into` — implicit conversions without a language import (SIP-71, preview)

Under `-preview`, `into` lifts the `import scala.language.implicitConversions`
requirement for `Conversion`-based implicit conversions, but only at the types
you designate. There are two forms; both ship as preview in Scala 3.8.

### `into[T]` — type constructor on a parameter

Wrap a parameter (or result) type in `into[T]`; arguments are then implicitly
convertible to `T` via a `given Conversion[?, T]`, with **no** language import:

```scala
import scala.Conversion.into        // the opaque type, not a language import

enum Expr derives CanEqual:
  case Const(n: Int)
  case Add(e1: into[Expr], e2: into[Expr])

given Conversion[Int, Expr] = Expr.Const(_)

val tree: Expr = Expr.Add(1, Expr.Const(2))   // 1 converts Int -> Expr, no import
```

`into[T]` is an opaque type `>: T`, so a value already of type `T` is accepted
unchanged; only the *expected type* `into[T]` licenses the conversion. Inside
the method body the `into` wrapper is erased — the parameter is just `T`.

### `into` as a soft modifier on a type declaration

Mark a `trait`, `class`, or `opaque type` with the soft `into` modifier;
conversions *to* that type then need no language import:

```scala
into trait Modifier
given Conversion[String, Modifier] = StringMod(_)
def use(m: Modifier): Modifier = m

use("hi")   // String -> Modifier conversion applied, no import
```

Use `into` to keep implicit conversions narrowly scoped instead of enabling
them project-wide. The `language.experimental.into` import is **deprecated
since 3.8** (the feature is now preview, selected by `-preview`).

## References

- Union types: <https://docs.scala-lang.org/scala3/reference/new-types/union-types.html>
- Named tuples (SIP-58): <https://docs.scala-lang.org/scala3/reference/other-new-features/named-tuples.html>
- Intersection types: <https://docs.scala-lang.org/scala3/reference/new-types/intersection-types.html>
- Match types: <https://docs.scala-lang.org/scala3/reference/new-types/match-types.html>
- Type lambdas: <https://docs.scala-lang.org/scala3/reference/new-types/type-lambdas.html>
- Polymorphic function types: <https://docs.scala-lang.org/scala3/reference/new-types/polymorphic-function-types.html>
- Dependent function types: <https://docs.scala-lang.org/scala3/reference/new-types/dependent-function-types.html>
- Opaque types: <https://docs.scala-lang.org/scala3/reference/other-new-features/opaques.html>
- Wildcard arguments: <https://docs.scala-lang.org/scala3/reference/changed-features/wildcards.html>
- `into` (SIP-71, preview): <https://docs.scala-lang.org/sips/71.html>
