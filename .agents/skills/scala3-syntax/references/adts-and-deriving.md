# Enums, ADTs, and Deriving

## Enumerate a closed set of cases: use `enum`

`enum` is the standard way to define an algebraic data type (ADT) in Scala 3.
It subsumes `sealed trait` + `case class`/`case object` hierarchies.

### Simple enumeration

```scala
enum Color:
  case Red, Green, Blue
```

`Color.Red`, `Color.Green`, `Color.Blue` are singleton instances of type
`Color`. They are `Comparable`, ordered by declaration, and have
`ordinal: Int`, `toString`, and a `values: Array[Color]` accessor.

### Sum of cases with fields (a "data" ADT)

```scala
enum Result[+E, +A]:
  case Failure(error: E)
  case Success(value: A)
```

Each case becomes a (case-)class nested in the enum's companion. `Failure` and
`Success` are constructed without `new`:

```scala
val ok: Result[String, Int] = Result.Success(42)
```

### Cases can have bodies, methods, and extend parents

```scala
enum HttpStatus(code: Int):
  case Ok extends HttpStatus(200)
  case NotFound extends HttpStatus(404)
  case ServerError extends HttpStatus(500)

  def isClientError: Boolean = code >= 400 && code < 500
```

Parameterized enums carry constructor parameters on the `enum` header; cases
pass them via `extends`. Add **computed members** directly in the enum body
(above: `isClientError`).

### Variance and modifiers

`enum Option[+A]` is covariant; `[+E, +A]` as above. You can add `private`,
`protected`, and annotations. Cases support `private` too.

### Accessing and pattern matching

```scala
def label(r: Result[String, Int]): String = r match
  case Result.Success(v) => s"ok: $v"
  case Result.Failure(e) => s"err: $e"
```

The match is exhaustive — the compiler knows every case. Prefer this over
`if`/`isInstanceOf`.

### Single-case enum vs class

A single-case enum is allowed but a plain `final case class` is clearer when
there is exactly one constructor. Use `enum` when there are two or more cases.

## `derives` — typeclass derivation

A `derives` clause on a class, enum, or trait triggers a typeclass's `derived`
hook to synthesize an instance.

```scala
enum Tree derives CanEqual, Eq, Show, Ordering:
  case Leaf(value: Int)
  case Node(left: Tree, right: Tree)
```

Common derivable typeclasses:

| Typeclass | Source | What it gives |
|---|---|---|
| `CanEqual` | `scala.language.strictEqualEquality` (built-in) | lawful `==` / `!=` (see below) |
| `Mirror` | `scala.deriving.Mirror` (built-in) | product/sum structure for macros |
| `Eq`, `Show`, `Hash`, `Order` | Cats (`cats.Eq`, etc.) | equality, rendering, hashing, ordering |
| `Semigroup`, `Monoid` | Cats | combine / empty |
| `Codec`, `Json` | libraries (e.g. circe) | (de)serialization |

`derives` desugars to `given Tree: Eq[Tree] = Eq.derived`. A typeclass supports
derivation by providing a `derived` method (typically via `Mirror` and macros /
quotes — see [metaprogramming.md](metaprogramming.md)).

### `Mirror` for ad-hoc generic programming

Every `enum`, `case class`, and `sealed trait` that has regular shape
auto-derives a `Mirror`:

```scala
import scala.deriving.Mirror

case class Person(name: String, age: Int) derives Mirror.Of[Person]
// Mirror.Of is usually derived automatically; the explicit derives is rarely needed.
val m = summon[Mirror.Of[Person]]
val labels = m.MirroredElemLabels   // ("name", "age")
val types  = m.MirroredElemTypes
```

Use `Mirror.Product` / `Mirror.Sum` to walk fields or cases generically.

## Strict equality (`CanEqual`)

With `-language:strictEquality`, `==` and `!=` require a `CanEqual[A, B]`
instance in scope. This is **multiversal equality** — equality is opt-in per
pair of types, preventing accidental `x == y` across unrelated types.

### Make a type comparable

```scala
enum Suit derives CanEqual:
  case Hearts, Spades, Diamonds, Clubs
```

`derives CanEqual` generates `given CanEqual[Suit, Suit]` so
`Suit.Hearts == Suit.Spades` typechecks.

### Cross-type equality

For comparing two *different* types, declare the pair explicitly:

```scala
given CanEqual[Int, MyId] = CanEqual.derived
given CanEqual[MyId, Int] = CanEqual.derived
```

Reserve this for genuine cross-type interop. If `MyId` is a newtype over
`Int`, prefer `myId.value == n` over declaring cross-type equality — it keeps
the abstraction barrier intact.

### Built-in `CanEqual` instances

`Int`, `Long`, `Double`, `String`, `Boolean`, and most stdlib types already
have `CanEqual` instances. Case classes do **not** auto-derive `CanEqual`;
add `derives CanEqual` (or derive `Eq` from Cats and use that).

### Escape hatch (last resort)

`import scala.language.strictEqualEquality` disables strict equality for the
file — equality becomes universal (`==` allowed between any two types). Avoid in
new code; it defeats the purpose of the flag.

## References

- Enums: <https://docs.scala-lang.org/scala3/reference/enums/enums.html>
- ADTs with enums: <https://docs.scala-lang.org/scala3/reference/enums/adts.html>
- Type class derivation: <https://docs.scala-lang.org/scala3/reference/contextual/derivation.html>
- Multiversal equality / CanEqual: <https://docs.scala-lang.org/scala3/reference/contextual/multiversal-equality.html>
- `scala.deriving.Mirror`: <https://scala-lang.org/api/3.8.0/scala/deriving/Mirror.html>
