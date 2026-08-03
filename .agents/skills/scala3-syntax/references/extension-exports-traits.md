# Extension Methods, Export Clauses, Traits

## Extension methods

Add methods to an existing type without subclassing:

```scala
extension (x: Int)
  def squared: Int = x * x
  def **(n: Int): Int = math.pow(x, n).toInt
```

Usage: `5.squared`, `2 ** 8`. The receiver `(x: Int)` is bound as `x` in the
body; multiple methods share the same `extension` block.

### Generic extension with context bound

```scala
extension [A: Ord](xs: List[A])
  def sorted: List[A] = xs.sortWith((a, b) => summon[Ord[A]].compare(a, b) < 0)
```

Or name the witness:

```scala
extension [A: Ord as ord](xs: List[A])
  def maxBy: Option[A] = xs.reduceOption((a, b) => if ord.compare(a, b) >= 0 then a else b)
```

### Multi-parameter extension

Each method takes its own parameter list after the receiver:

```scala
extension (s: String)
  def padTo(width: Int, ch: Char): String = s + ch.toString * (width - s.length)
```

### Right-associative operators

Methods ending in `:` are right-associative; the receiver is the right
operand. Define `extension (x: Int) def *: (y: Int): Int = y * x` so
`2 *: 3` parses as `3.*:(2)`. These operators cost readability — use sparingly.

## Export clauses

`export` brings members of an object into the enclosing scope as aliases —
symmetric to `import`, but for *defining* a module's surface (composition over
inheritance).

```scala
object Store:
  def put(k: String, v: Int): Unit = ???
  def get(k: String): Option[Int] = ???

object Service:
  export Store.put        // expose only `put`
  def get(k: String): Option[Int] = Store.get(k).map(_ + 1)
```

Selectors:

- `export Store.*` — all members
- `export Store.{put, get}` — selected
- `export Store.{put as set, get}` — renamed

Exported members get forwarding methods; the original `Store` is not exposed as
a parent. Use `export` to build a facade without leaking implementation.

## Trait parameters

Traits can take constructor parameters (just like classes):

```scala
trait Greeting(name: String):
  def greet: String = s"hi, $name"

class English(name: String) extends Greeting(name)
```

Trait parameters let a trait depend on construction data. They cannot be passed
via `with` in `new T with P`-style mixins the same way class params can — pass
them at the extending class.

## `open` classes

A class must be marked `open` to be extensible outside its defining file /
compilation unit (when `-source:future` is on, the check is enforced):

```scala
open class Animal
class Dog extends Animal          // ok, Animal is open
```

Library authors mark intended extension points; everything else is closed by
default, preventing accidental subclassing.

## `transparent` traits and classes

A `transparent` trait (or class) is **erased from inferred types** — useful for
mixins whose presence shouldn't widen the static type:

```scala
transparent trait Logging

class Service extends Logging:
  def run: Unit = ???

val s = new Service
// s: Service   (not Service & Logging — the transparent trait is hidden)
```

Use for marker traits / implementations whose inheritance is an internal
detail.

## Trait constructor order & `super`

Trait linearization is preserved; traits are initialized in prefix order
(parents left-to-right), then the class body. `super` in a trait refers to the
next trait in the linearization.

## References

- Extension methods: <https://docs.scala-lang.org/scala3/reference/contextual/extension-methods.html>
- Right-associative extension: <https://docs.scala-lang.org/scala3/reference/contextual/right-associative-extension-methods.html>
- Export clauses: <https://docs.scala-lang.org/scala3/reference/other-new-features/export.html>
- Trait parameters: <https://docs.scala-lang.org/scala3/reference/other-new-features/trait-parameters.html>
- Open classes: <https://docs.scala-lang.org/scala3/reference/other-new-features/open-classes.html>
- Transparent traits: <https://docs.scala-lang.org/scala3/reference/other-new-features/transparent-traits.html>
