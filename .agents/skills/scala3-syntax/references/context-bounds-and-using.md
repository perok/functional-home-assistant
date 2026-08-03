# Using Clauses and Context Bounds

## `using` clauses

A `using` clause declares **context parameters** — arguments the compiler fills
in by searching for a `given` of the right type.

### By type only (anonymous — preferred when the name is unused)

```scala
def maximum[T](xs: List[T])(using Ordering[T]): T = xs.max
```

### Named (when you refer to it in the body)

```scala
def maximum[T](xs: List[T])(using ord: Ordering[T]): T =
  xs.reduce((x, y) => if ord.compare(x, y) >= 0 then x else y)
```

### Passing arguments explicitly

Callers may supply the context explicitly with `using`:

```scala
val m = maximum(xs)(using myOrdering)
```

Under `-source:future` (Scala 3.8) context-bound witnesses desugar to **given**
parameters, so explicit passing always needs the `using` keyword — a plain
application like `maximum(xs)(myOrd)` will not compile. This also affects stdlib
APIs that use context bounds:

```scala
Array.empty(using reflect.ClassTag.Int)   // correct
Array.empty(reflect.ClassTag.Int)         // error under -source:future
```

### Multiple context parameters in one clause

```scala
def render(using ExecutionContext, Logger): Unit = ???
def render(using ec: ExecutionContext, log: Logger): Unit = ???
```

## Context bounds

A **context bound** is shorthand for a context parameter that depends on a
type parameter. It is the idiomatic way to express typeclass constraints.

```scala
def maximum[T: Ord](xs: List[T]): T = ???
// expands to:
// def maximum[T](xs: List[T])(using Ord[T]): T
```

### Named context bound (`as`)

Name the witness so the body can use it without `summon`:

```scala
def reduce[A: Monoid as m](xs: List[A]): A =
  xs.foldLeft(m.unit)(_ `combine` _)
// expands to: def reduce[A](xs: List[A])(using m: Monoid[A]): A
```

### Aggregate context bounds (multiple bounds in `{ }`)

```scala
def showMax[X: {Ord, Show}](x: X, y: X): String
def showMax[X: {Ord as ordering, Show as show}](x: X, y: X): String =
  show.asString(ordering.max(x, y))
```

The chained form `[X : Ord : Show]` still compiles but is **warned under
`-source:future`** and slated for removal — use the `{ }` form.

### Placement of generated witnesses

The generated `using` clause is placed:

1. Immediately **before** the first parameter clause that refers to a witness by
   name (so the name is in scope in those types):
   ```scala
   def run[P: Parser as p](in: p.Input): p.Result
   // expands to:
   // def run[P](using p: Parser[P])(in: p.Input): p.Result
   ```
2. Merged into the final `using` clause if the method already ends in one.
3. Otherwise appended as a new final `using` clause (the common case).

### Context bounds on type members

Type **members** (not just type parameters) can carry context bounds. They
desugar to **deferred givens** (see [givens.md](givens.md#deferred-given)) and
must therefore live in a `trait` (or a class that subclasses will extend) — a
deferred given is synthesized by the implementing class, so a concrete class
with an abstract member and no implementor will not compile:

```scala
trait Collection:
  type Element: Ord
// expands to:
// trait Collection:
//   type Element
//   given Ord[Element] = compiletime.deferred

class IntCollection(using ord: Ord[Int]) extends Collection:
  type Element = Int
  // the compiler synthesizes: override given Ord[Element] = ord
```

### Context bounds on polymorphic function types / literals

From Scala 3.6, context bounds work in polymorphic function types and lambdas:

```scala
type Comparer = [X: Ord] => (x: X, y: X) => Boolean
val less: Comparer = [X: Ord as ord] => (x: X, y: X) =>
  ord.compare(x, y) < 0
```

These expand by inserting a **context function type** (`?=>`) rather than a
`using` clause.

## Clause interleaving (SIP-47, standard since 3.6)

Method signatures may interleave **multiple** type-parameter clauses with
term-parameter and `using` clauses (two type clauses may not be adjacent).
This lets a later type clause depend on an earlier term parameter — impossible
before Scala 3.6:

```scala
def pair[A](a: A)[B](b: B): (A, B) = (a, b)
pair(1)[String]("x")

trait Key:
  type Value
def getOrElse(k: Key)[V >: k.Value](default: => V): V = default
```

Classes are still restricted to a single leading type clause; only `def`s
(including `extension` right-hand sides) benefit from interleaving.

## `summon` and `summonInline`

`summon[T]` returns the given `T` in scope. `summonInline[T]` (from
`scala.compiletime`) does the same but is usable inside `inline` methods (it
performs the search at the *call site* after inlining — see
[metaprogramming.md](metaprogramming.md)).

## References

- Using clauses: <https://docs.scala-lang.org/scala3/reference/contextual/using-clauses.html>
- Context bounds: <https://docs.scala-lang.org/scala3/reference/contextual/context-bounds.html>
- Context functions: <https://docs.scala-lang.org/scala3/reference/contextual/context-functions.html>
- Clause interleaving (SIP-47): <https://docs.scala-lang.org/sips/47.html>
- Scala 3.8 context-bound desugaring change: <https://www.scala-lang.org/news/3.8/>
