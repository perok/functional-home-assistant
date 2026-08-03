# Compiler Flags: The Strict Profile

This profile is the set of Scala 3 compiler flags assumed throughout the
skill. It is a deliberately strict, modern configuration that surfaces most
real bugs as warnings or errors. Under `-source:future` the compiler **warns
on deprecated syntax**, so writing modern Scala 3 (see the other reference
files) is what silences the warnings.

## The flag profile

```scala
scalacOptions ++= Seq(
  "-source:future",            // newest dialect; warns on old given/context-bound syntax
  "-preview",                  // enable officially-released preview features
  "-language:strictEquality",  // == and != require CanEqual
  "-Yexplicit-nulls",          // reference types non-nullable; null is T | Null
  "-deprecation",              // warn on deprecated APIs
  "-unchecked",                // pattern/exhaustivity/soundness warnings
  "-explain",                  // prose explanations appended to errors
  "-explain-types",            // type-inference trace on type mismatch
  "-Wnonunit-statement",       // statement returning non-Unit is flagged
  "-Wvalue-discard",           // discarding a pure expression result
  "-Wsafe-init",               // detect reads of fields before constructor finishes
  "-Wunused:imports",          // unused imports
  "-Wunused:locals",           // unused locals
  "-Wunused:params",           // unused parameters
  "-Wunused:privates",         // unused private members
  "-Wconf:any:verbose",        // show category + location for all warnings
  "-encoding", "utf-8"
)
```

A matching scalafmt configuration uses the `Scala3Future` dialect.

> **Note:** these flags emit warnings, not hard errors — there is no
> `-Xfatal-warnings` here. Treat every warning as a real issue to fix, but a
> stray warning will not by itself block compilation.

## Flag → pitfall → fix

### `-source:future`

- **Symptom**: warning "old given syntax" / "old context bound syntax".
- **Cause**: `given T with`, `given foo[A: Ord]: T[A] with`, `[X : A : B]`.
- **Fix**: use SIP-64 syntax — see
  [givens.md](givens.md) and
  [context-bounds-and-using.md](context-bounds-and-using.md).
- **Also**: context-bound witnesses now desugar to `given`/`using` params;
  passing them explicitly requires `using` (e.g.
  `Array.empty(using ClassTag.Int)`).

### `-language:strictEquality`

- **Symptom**: `Values of types X and Y cannot be compared with == or !=`.
- **Cause**: no `CanEqual[X, Y]` in scope.
- **Fix**: add `derives CanEqual` to the type, or declare
  `given CanEqual[X, Y] = CanEqual.derived`. Do **not** reach for
  `import scala.language.strictEqualEquality` (it disables the check globally).
  See [adts-and-deriving.md](adts-and-deriving.md#strict-equality-canequal).

### `-Yexplicit-nulls`

- **Symptom**: `null` does not conform to `String` (or other `AnyRef` type).
- **Cause**: reference types are non-nullable under this flag.
- **Fix**: model nullability as `T | Null`, narrow via `Option(x)` (preferred
  under `-language:strictEquality` — a bare `x == null` / `case null` needs a
  `CanEqual` instance), or — at Java/legacy boundaries only —
  `import scala.language.unsafeNulls`. See
  [explicit-nulls.md](explicit-nulls.md).

### `-Wvalue-discard`

- **Symptom**: "discarded non-Unit value".
- **Cause**: an expression's result is thrown away, often a `IO` or `Either`
  you meant to sequence.
- **Fix**: bind it (`_ <- effect` in a for-comprehension), return it, or
  explicitly discard with `val _ = expr` only as a last resort (prefer
  fixing the logic).

### `-Wnonunit-statement`

- **Symptom**: "non-Unit statement".
- **Cause**: a statement whose result is not `Unit` is used for its side
  effect, often signaling a forgotten `flatMap`/`<‑`.
- **Fix**: sequence it into the effectful result (e.g. `_ <- ...`), or
  annotate the intent by assigning to `_`.

### `-Wunused:*` (imports / locals / params / privates)

- **Symptom**: "unused import" etc.
- **Cause**: dead imports or never-read locals — typically stale after a
  refactor.
- **Fix**: delete the import / member. If the import is required for a side
  effect (e.g. a `given` import), use selective import (`import Foo.given`)
  rather than wildcard. For params that exist for binary/source-compat,
  prefix with `_` (e.g. `def f(_x: Int)`).

### `-Wsafe-init`

- **Symptom**: "access to non-initialized field" / safe-init warning.
- **Cause**: a constructor calls a method (or reads a field) before that
  field is initialized — common with `val` referencing a later `val`, or with
  virtual calls from a base constructor.
- **Fix**: reorder initializers so dependencies come first, make the depending
  member a `lazy val` or `def`, or pass the value in as a constructor
  parameter rather than reading it from `this`.

### `-deprecation` / `-unchecked`

- **Symptom**: deprecation warnings; exhaustivity/soundness warnings.
- **Fix**: migrate to the modern API/syntax. For exhaustivity on an open type,
  prefer making it `enum`/`sealed`; otherwise add a deliberate `case _` and
  reconsider the design.

### `-explain` / `-explain-types`

Not warnings — these append human-readable context to error messages. Read the
full error output (often multi-line) before iterating; the added prose
frequently points straight at the missing `given`, the un-narrowed nullable,
or the mismatched type.

## Practical guidance

- Treat **warnings as errors** in your head: every warning is a real issue.
  Don't suppress with `@nowarn` unless you've confirmed the warning is a
  false positive and documented why.
- Run `sbt clean; sbt compile` after structural changes — `-Wsafe-init` and
  strict equality sometimes surface only on a full rebuild.
- The scalafmt `Scala3Future` dialect will reformat old given syntax on save;
  let it, then read the diff to learn the new shapes.

## References

- Scala 3 compiler options: <https://docs.scala-lang.org/scala3/reference/internals/compiler-options.html>
- `-source` versions: <https://docs.scala-lang.org/scala3/reference/language-versions/source-compatibility.html>
- Multiversal equality: <https://docs.scala-lang.org/scala3/reference/contextual/multiversal-equality.html>
- Explicit nulls: <https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html>
- Safe initialization: <https://docs.scala-lang.org/scala3/reference/other-new-features/safe-initialization.html>
- Scala 3.8 release notes: <https://www.scala-lang.org/news/3.8/>
