# Given Instances (SIP-64 New Syntax)

`given` defines a **canonical value** of a type — the value the compiler will
supply to a `using` clause. Scala 3.6 stabilized the SIP-64 syntax (the
`-source:future` dialect *warns* on the older Scala 3.0–3.5 syntax, so use only
the forms below).

A `given` consists of: the keyword `given`, an optional **name and colon**,
zero or more **conditions** (each ending in `=>`), then the implemented type(s)
and an implementation (either `= <expr>` for an **alias**, or a **template
body** for a structural instance).

## Structural instance (a typeclass with methods)

```scala
trait Ord[T]:
  def compare(x: T, y: T): Int

given Ord[Int]:
  def compare(x: Int, y: Int) =
    if x < y then -1 else if x > y then +1 else 0
```

No `with` keyword. The body follows the colon / indentation, just like a class
body.

## Named instance

```scala
given intOrd: Ord[Int]:
  def compare(x: Int, y: Int) = ???
```

Prefer named instances for publicly exposed library code (stable binary
compatibility); anonymous is fine for app-internal instances.

## Parameterized by a type with a context bound

```scala
given [A: Ord] => Ord[List[A]]:
  def compare(xs: List[A], ys: List[A]): Int = (xs, ys) match
    case (Nil, Nil) => 0
    case (x :: xs1, y :: ys1) =>
      val fst = summon[Ord[A]].compare(x, y)
      if fst != 0 then fst else compare(xs1, ys1)
    case _ => ???   // implement remaining cases
```

The `[A: Ord]` is a **context bound** (see
[context-bounds-and-using.md](context-bounds-and-using.md)); it becomes a
context parameter the given needs.

## Parameterized by an explicit `using` clause

```scala
given [A] => (ord: Ord[A]) => Ord[List[A]]:
  def compare(xs: List[A], ys: List[A]): Int = ord.compare(xs.head, ys.head)
```

Here the precondition is a normal value parameter list `(ord: Ord[A])`
terminated with `=>`. Use the **named** form when you want to refer to the
instance by name in the body.

## Named, parameterized instance

```scala
given listOrd: [A: Ord as ord] => Ord[List[A]]:
  def compare(xs: List[A], ys: List[A]): Int = ord.compare(xs.head, ys.head)
```

`[A: Ord as ord]` names the context-bound witness `ord` so the body can use it
without `summon`.

## Alias given (the instance is an expression)

```scala
given ExecutionContext = ForkJoinPool()        // anonymous alias
given global: ExecutionContext = ForkJoinPool() // named alias
```

Prefer `Resource`-based lifecycle management for resources like thread pools;
a bare alias given installs them as global singletons.

Parameterized alias with a context bound:

```scala
given [A: Eq] => Eq[List[A]] = (xs, ys) =>
  xs.corresponds(ys)(summon[Eq[A]].eqv)
```

## By-name given (re-evaluated on each access)

A conditional given with an empty parameter clause `()` is by-name — the
right-hand side runs each time the given is summoned. Note the given's **type
is `Context`**, not `() => Context`: the `()` is a by-name *condition*, not a
function type. Use sparingly — only for genuinely per-call values (e.g. a
request scope); a given that re-runs side effects on each summon is surprising.

```scala
trait Context
def currentContext(): Context = ???
given context: () => Context = currentContext()   // fresh each summon
// summon[Context] re-runs currentContext() on every access
```

## Deferred given (in a trait — replaces abstract givens)

```scala
import scala.compiletime.deferred

trait Sorted:
  type Element
  given Ord[Element] = deferred
```

Any class implementing the trait supplies (or synthesizes) the given. An
explicit override needs `override`:

```scala
class SortedSet[A](using ord: Ord[A]) extends Sorted:
  type Element = A
  override given Ord[Element] = ord
```

If no explicit override is given, the compiler synthesizes one by searching
for a `given Ord[Element]` in the inheriting class's environment. **Abstract
givens** (`given foo: Context` with no body) are deprecated — use
`= deferred`.

## Importing / summoning

`summon[T]` returns the given instance of `T` in scope:

```scala
val ord = summon[Ord[Int]]
```

To bring givens defined in an object into scope, `import` the object — givens
come along. To import **only** givens: `import Foo.given`.

## Given prioritization (under `-source:future`)

Don't have two matching givens in scope — that's ambiguous design regardless
of which the compiler picks. When it's genuinely unavoidable (e.g. a library
default overridden by an app override), Scala 3.7 / `-source:future` prefers
the **most general** matching type (the opposite of Scala 3.4 and earlier,
which preferred the most specific). Write unambiguous givens; if two givens
both match and one is a subtype of the other, the more general one wins.

## Complete syntax

```ebnf
GivenDef    ::= [id ':'] GivenSig
GivenSig    ::= GivenImpl
             | '(' ')' '=>' GivenImpl
             | GivenConditional '=>' GivenSig
GivenImpl   ::= GivenType (['=' Expr] | TemplateBody)
             | ConstrApps TemplateBody
GivenConditional ::= DefTypeParamClause
             | DefTermParamClause
             | '(' FunArgTypes ')'
             | GivenType
```

## References

- Given Instances: <https://docs.scala-lang.org/scala3/reference/contextual/givens.html>
- Other forms of givens: <https://docs.scala-lang.org/scala3/reference/contextual/more-givens.html>
- Deferred givens: <https://docs.scala-lang.org/scala3/reference/contextual/deferred-givens.html>
- Previous (Scala 3.0–3.5) given syntax: <https://docs.scala-lang.org/scala3/reference/contextual/previous-givens.html>
- SIP-64: <https://docs.scala-lang.org/sips/64.html>
- Scala 3.6.2 release (stabilization): <https://www.scala-lang.org/news/3.6.2/>
