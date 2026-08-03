# Metaprogramming: inline, compiletime, quotes

Scala 3 metaprogramming is split into tiers of increasing power. Reach for the
simplest tier that works.

## `inline` — compile-time reduction

`inline` marks a `val`/`def` for inlining at the use site. An `inline if` /
`inline match` is reduced during typer so unreachable branches disappear.

```scala
inline def logLevel: Int = 3
inline def debug(msg: String): Unit =
  inline if logLevel >= 3 then println(msg) else ()
```

`inline if cond then a else b` requires `cond` to be a known-constant (e.g.
`inline val` or `constValue`). `inline match` scrutinizes a type or value
known at compile time.

## `scala.compiletime` essentials

Import `scala.compiletime.*`. Key operations:

| Operation | Purpose |
|---|---|
| `summonInline[T]` | Like `summon[T]`, but resolves at the call site after inlining. |
| `constValue[T]` | Extract a literal type as a value (errors if not a singleton/constant). |
| `constValueOpt[T]` | Same, returns `Option` instead of erroring. |
| `erasedValue[T]` | Produce a value of `T` usable only in positions erased by inlining (never evaluated at runtime). |
| `summonFrom[T](pf)` | Match on context availability: `summonFrom { case ev: C[T] => ... ; case _ => ... }`. |
| `deferred` | RHS of a deferred given in a trait (see [givens.md](givens.md)). |
| `error(msg)` / `warning(msg)` | Emit compile-time diagnostics. |

Example — name a context-bound witness by type-name lookup:

```scala
import scala.compiletime.*

inline def defaultName[T]: String =
  constValue[T] match
    case s: String => s   // requires a literal singleton type
    case _         => "(anon)"
```

Example — conditional summoning:

```scala
inline def maybeShow[T](x: T): Option[String] =
  summonFrom:
    case ev: Show[T] => Some(ev.show(x))
    case _           => None
```

## Quotes — quasi-quotation

`'{ expr }` builds a `Expr[T]` (a typed code tree) at compile time; `${ x }`
splices a `Expr` back into a quote. This is the macro interface.

```scala
import scala.quoted.*

inline def debugCode[T](inline x: T): T = ${ debugCodeImpl('x) }

def debugCodeImpl[T: Type](x: Expr[T])(using Quotes): Expr[T] =
  import quotes.reflect.*
  x.show.asExpr      // inspect / print the tree
  x                  // return it
```

- `'x` quotes an existing `Expr` into a larger quote.
- `'${...}` and `${...}` are splice forms depending on context.
- `Quotes` is the capability required in macro implementations; `Type[T]`
  carries `T`'s type across stages.

## Reflection API

For tree manipulation beyond quotes, `import quotes.reflect.*` exposes the
full TASTy tree: `Term`, `TypeTree`, `Symbol`, `Statement`, type reification,
and constructors. Prefer the higher-level quotes API; drop to `reflect` only
when you must inspect or synthesize trees quotes cannot express.

## When to use which tier

| Need | Use |
|---|---|
| Constant folding / branch elimination | `inline` + `inline if` / `inline match` |
| Look up a given conditionally | `summonFrom` |
| Derive a typeclass from a `Mirror` | `inline` + `Mirror` + `constValue`/`summonInline` (or the library's `derived` macro) |
| Generate code from a schema / annotation | `inline` + quotes (`'{}` / `${}`) |
| Inspect / rewrite arbitrary trees | `reflect` (rare) |

`scala.compiletime` operations may only appear in `inline` context (or as the
RHS of a deferred given for `deferred`).

## References

- Inline: <https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html>
- Compile-time operations (`scala.compiletime`): <https://docs.scala-lang.org/scala3/reference/metaprogramming/compiletime-ops.html>
- Quotes & macros: <https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html>
- Reflection: <https://docs.scala-lang.org/scala3/reference/metaprogramming/reflection.html>
- Macro tutorial: <https://docs.scala-lang.org/scala3/guides/macros/index.html>
- `scala.compiletime` API: <https://scala-lang.org/api/3.8.0/scala/compiletime.html>
