# Core Syntax: Control, Indentation, Imports

## Quiet control syntax

Drop parentheses and braces; use the trailing keyword to open a block.

```scala
if x < 0 then "neg"
else if x == 0 then "zero"
else "pos"

while cond do step()

for
  x <- xs
  y <- ys
if x < y
yield (x, y)

match expr with           // with optional; usually `expr match case ...`
  case Ok(v) => v
  case Err(e) => 0
```

Keywords: `then` (if), `do` (while / for), `yield` (for comprehensions), `case`
(match — the `match` selector may be followed directly by `case`).

## Optional braces & significant indentation

A `:` (or a control keyword like `then`/`do`) at end of a line starts an
indented region that replaces `{ ... }`. The block ends when indentation
returns to the enclosing level.

```scala
object Server:
  def start(): Unit =
    log("starting")
    bind(8080)
```

Rules of thumb:

- Use `:` to start a class/trait/object/enum/given body, or an `if`/`for` arm.
- One statement per line; no semicolons needed.
- To continue a single expression across lines, indent further or use a binary
  operator at line end.
- You can still use braces when you want a brace-delimited block (e.g. in
  dense one-liners or macros); braces and indentation can interleave.

## Top-level definitions

A `.scala` file may contain top-level `def`, `val`, `class`, `object`, `enum`,
`given`, `extension`, `type`, `import` — no enclosing `object` required:

```scala
// file: src/main/scala/app/Main.scala
def greeting(name: String): String = s"hello, $name"
@main def run(): Unit = println(greeting("world"))
```

## `@main` methods

Annotate a top-level method (or a method in an object) with `@main` to mark it
as an entry point. Parameters become CLI args parsed by type:

```scala
@main def count(n: Int): Unit = println(s"counting to $n")
```

## `*` wildcard import (not `_`)

```scala
import scala.collection.immutable.*
import cats.effect.{IO, Resource}      // selective
import cats.effect.IO.*                // all members of IO
```

`_` is not the wildcard import character — use `*`.

## Creator applications (optional `new`)

`new` is optional when constructing a class that has an `apply` or accessible
constructor:

```scala
case class Point(x: Int, y: Int)
val p = Point(1, 2)          // not `new Point(1, 2)`

class Logger(name: String):
  def info(s: String): Unit = ???
val log = Logger("app")      // new omitted
```

## Non-local returns: `boundary` / `break`

Non-local `return` is deprecated. Use `scala.util.boundary`:

```scala
import scala.util.boundary, boundary.break

val found = boundary[Int]:
  for x <- xs do
    if pred(x) then break(x)
  -1
```

`boundary[T]: <block>` opens a scope returning `T`; `break(v)` exits it with
`v`. Multiple `boundary`s nest; `break` targets the innermost enclosing one of
the right type. Prefer stdlib combinators (`find`/`exists`/`takeWhile`) for
simple early-exit; `boundary` is for cases they don't cover.

## Fewer braces — colon arguments (SIP-44, standard since 3.3)

A `:` at end of a line also opens an indented **function-argument** block, so
you can drop braces around block arguments:

```scala
xs.map: x =>
  x * x

xs.foldLeft(0): (a, b) =>
  a + b

List(1, 2, 3).foldLeft(0):
  (a, b) => a + b
```

A `:` may be followed on the same line by a lambda's parameter part and `=>`.
Multiple colon-args chain by ending the previous argument and starting a new
`.apply:` (the parser does **not** allow `:` after an infix operator or after
another indented argument — use parentheses or `.apply` for those).

### Type-ascription pitfall

Because `:` now also introduces an argument, a multi-line type ascription must
put the `:` (and the type) on the **next line**, not at the end of the value
line:

```scala
val x = y
  : Int        // safe: ascription
// val x = y:
//   Int       // parses as a colon-argument, not an ascription — avoid
```

Single-line `val x = y: Int` is still an ascription (a value is not callable),
but prefer the next-line form for clarity in mixed code.

## Literal niceties

- **Binary integer literals** (SIP-42): `0b1010`, `0b1110_1101_1011_0111`.
- **Trailing commas** (SIP-27) are allowed in any multi-line comma-separated
  list (imports, arguments, parameters, tuple elements):

```scala
val xs = List(
  1,
  2,
  3,           // trailing comma is fine
)
```

## For-comprehensions

Support `if` guards and multiple generators with the quiet syntax (above).
`yield` produces a collection; dropping `yield` runs for side effects. SIP-62
("better fors", **preview** under `-preview`) further relaxes for-comprehension
patterns and guards; the forms shown here work under both standard and preview.

## `match` as an expression

Every `match` is an expression; ensure exhaustiveness (the compiler checks it
for sealed types and enums). For open types, add a `case _ =>` and consider
whether the type should be `enum`/`sealed` instead.

## References

- New control syntax: <https://docs.scala-lang.org/scala3/reference/other-new-features/control-syntax.html>
- Optional braces (indentation): <https://docs.scala-lang.org/scala3/reference/other-new-features/indentation.html>
- Toplevel definitions: <https://docs.scala-lang.org/scala3/reference/other-new-features/toplevel-definitions.html>
- Creator applications: <https://docs.scala-lang.org/scala3/reference/other-new-features/creator-applications.html>
- Better fors (SIP-62): <https://docs.scala-lang.org/sips/62.html>
- Binary integer literals (SIP-42): <https://docs.scala-lang.org/sips/42.html>
- Trailing commas (SIP-27): <https://docs.scala-lang.org/sips/27.html>
- Fewer braces (SIP-44): <https://docs.scala-lang.org/sips/44.html>
- Deprecated nonlocal returns: <https://docs.scala-lang.org/scala3/reference/dropped-features/nonlocal-returns.html>
- `scala.util.boundary`: <https://scala-lang.org/api/3.8.0/scala/util/boundary.html>
