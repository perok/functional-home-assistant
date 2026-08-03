---
name: scala-weaver-test
author: sanssushi
description: Use when writing or modifying Scala 3 tests with weaver-cats, weaver-discipline, or weaver-scalacheck; covers suites, effects, expectations, resources, and laws
tags: ["scala", "testing"]
license: MIT
---

# Weaver for Scala 3

Inspect the Weaver version, build, and nearby tests first. Follow project
traits and helpers. Write Scala 3 only: use `*` imports, indentation syntax,
and current Scala 3 lambdas and control syntax.

This guide targets Weaver 0.13: `FunSuiteIO` became `FunSuite`,
`MutableIOSuite` became `IOSuite`, and `SimpleMutableIOSuite` became
`SimpleIOSuite`. For older pinned Weaver versions, preserve names already used
by the project; do not perform an unrelated migration.

## Pick a Suite

| Need | Suite |
|---|---|
| Pure `Expectations` only | `FunSuite` |
| Independent `IO[Expectations]` tests | `SimpleIOSuite` |
| One managed `Resource[IO, Res]` shared by tests | `IOSuite` |
| Discipline laws | `FunSuite with Discipline` |

Ordinary discovered suites are singleton objects. Classes are only for the
`GlobalRead` cross-suite resource-sharing pattern; see the
[global resources doc](https://typelevel.org/weaver-test/features/global_resources.html).

### Pure

```scala
import weaver.*

object SlugSuite extends FunSuite:
  def slug(value: String) = value.trim.toLowerCase.replace(' ', '-')

  test("slug normalizes spaces and case"):
    expect.eql("hello-world", slug(" Hello World "))
```

Do not add `IO` to a wholly pure suite. `FunSuite` tests run sequentially.

### Independent Effects

```scala
import cats.effect.{IO, Ref}
import weaver.*

object CounterSuite extends SimpleIOSuite:
  test("update changes the value"):
    for
      counter <- Ref.of[IO, Int](0)
      _       <- counter.update(_ + 1)
      value   <- counter.get
    yield expect.eql(1, value)

  pureTest("reverse preserves length"):
    expect.eql(3, List(1, 2, 3).reverse.length)
```

Use `SimpleIOSuite` for Cats Effect operations including fibers, cancellation,
`Ref`, `Deferred`, `IO.sleep`, and `TestControl`. Reserve `pureTest` for
occasional pure cases in an otherwise effectful suite.

### Shared Resource

Weaver uses `Resource[IO, Res]` for all lifecycle management. There are no
separate `beforeAll`/`afterAll`/`around` hooks — model setup and teardown
as `Resource` acquire/release semantics instead:

```scala
import cats.effect.{IO, Ref, Resource}
import weaver.*

object SharedCounterSuite extends IOSuite:
  override type Res = Ref[IO, Int]

  // acquired once before all tests, released after all tests finish
  override def sharedResource: Resource[IO, Res] =
    Resource.eval(Ref.of[IO, Int](0))   // use Resource.eval, not deprecated Resource.liftF

  test("shared counter can be read"): counter =>
    counter.get.map(value => expect.eql(0, value))

  test("shared counter can be logged"): (counter, log) =>
    // (Res, Log[IO]) => IO[Expectations] — only on IOSuite
    for
      value <- counter.get
      _     <- log.info(s"counter value: $value")
    yield expect.eql(0, value)

  pureTest("pure test in IOSuite"):
    // IOSuite.pureTest exists; it does NOT receive Res
    expect(1 + 1 == 2)
```

The resource is acquired once and released after the suite. Shared-resource
tests may run concurrently, so never rely on test order or mutable shared state
without explicit coordination.

## Body Types

| Method | Suite | Body |
|---|---|---|
| `test` | `FunSuite` | `Expectations` |
| `pureTest` | `SimpleIOSuite`, `IOSuite` | `Expectations` (no `Res`) |
| `test` | `SimpleIOSuite` | `IO[Expectations]` |
| `test` | `IOSuite` | `IO[Expectations]`, `Res => IO[Expectations]`, or `(Res, Log[IO]) => IO[Expectations]` |
| `loggedTest` | `SimpleIOSuite` | `Log[IO] => IO[Expectations]` |

Suite methods register tests and return `Unit`; each body must ultimately
produce one `Expectations`. Sequence an existing effect directly, never wrap
it in another `IO`:

```scala
val loadValue: IO[String] = IO.pure("ready")

// Wrong: IO[IO[String]]; the load is not sequenced.
IO(loadValue)

// Correct.
loadValue.map(value => expect(value.nonEmpty))
```

Do not return `IO.unit` from a test body — it compiles but silently discards
the expectation. Enable `-Wvalue-discard` to catch this at compile time.

Use `loggedTest` instead of `println`; logs are attached to failures:

```scala
loggedTest("load returns a value"): log =>
  for
    value <- IO.pure("ready")
    _     <- log.info(s"loaded value: $value")
  yield expect(value.nonEmpty)
```

## Expectations

- `expect(condition)` checks a predicate; wrap diagnostic values in `clue`.
- Prefer `expect.eql(expected, found)` for typed equality and useful diffs.
- Use `expect.same(expected, found)` only when relaxed universal equality is
  intentional; prefer `expect.eql` by default.
- Use `success` and `failure("reason")` for explicit branches.
- Use `matches`, `forEach`, and `exists` for patterns and collections.

```scala
val values = List(2, 4, 6)
expect(clue(values).forall(_ % 2 == 0))

val parsed: Either[String, String] = Right("ready")
matches(parsed):
  case Right(value) => expect.eql("ready", value)

forEach(List("alpha", "beta")): value =>
  expect(clue(value).nonEmpty)
```

Compose every expectation. A block returns only its last expression, so
separate expectations discard earlier failures:

```scala
val result = "ready"

// Wrong: the first result is discarded.
expect(result.nonEmpty)
expect(result.length <= 20)

// Correct.
expect(result.nonEmpty) && expect(result.length <= 20)

expect.all(
  result.nonEmpty,
  result.length <= 20,
  result.forall(_.isLower)
)
```

Compose with `and`/`&&`, `or`/`||`, or `xor`; these do not short-circuit.
Weaver has no `&` combinator on `Expectations`; use `&&` / `and`.
Use `.failFast[IO]` only when later effects must not run after a failed
intermediate expectation:

```scala
// Signature: def failFast[F[_]: Sync]: F[Unit]
for
  value <- loadValue
  _     <- expect(value.nonEmpty).failFast[IO]   // raises if expectation fails
  _     <- expensiveEffect(value)
yield expect.eql("ready", value)
```

### Exception Expectations

Use `.attempt` with `matches` to assert that an effect raises a specific
exception:

```scala
test("fails with IllegalArgumentException"):
  for
    result <- IO.raiseError[String](IllegalArgumentException("bad input")).attempt
  yield matches(result):
    case Left(e: IllegalArgumentException) => expect(e.getMessage.contains("bad"))
    case Left(other)                        => failure(s"unexpected exception: $other")
    case Right(v)                           => failure(s"expected failure, got: $v")
```

Do not use ScalaTest's `assert`/`assertResult` inside Weaver suites.

## Filtering Tests

Use `.only` to run only specific tests during development. Use `.ignore` to
skip a test. These are string method calls on the test name:

```scala
test("run this one".only):
  expect.eql(1, 1)

test("skip this one".ignore):
  expect.eql(1, 2)
```

For runtime-conditional ignoring:

```scala
test("only on CI"):
  for
    onCI <- IO(sys.env.get("CI").isDefined)
    _    <- IO.whenA(!onCI)(IO.raiseError(new Exception("skip outside CI")))
  yield success
```

Never commit `.only` — it silently skips all other tests.

## Parallelism

`IOSuite` tests run concurrently by default. To force sequential execution,
override `maxParallelism`:

```scala
object SequentialSuite extends IOSuite:
  override def maxParallelism: Int = 1
  override type Res = Unit
  override def sharedResource: Resource[IO, Unit] = Resource.unit
  ...
```

Only do this when tests have unavoidable ordering dependencies (e.g., a
stateful external system). Prefer isolated resources or `Ref` for coordination.

## Discipline and ScalaCheck

```scala
import cats.kernel.laws.discipline.EqTests
import weaver.*
import weaver.discipline.*

object EqualityLawsSuite extends FunSuite with Discipline:
  checkAll("Int.Eq", EqTests[Int].eqv)
```

```scala
import weaver.*
import weaver.scalacheck.*

object ListPropertiesSuite extends SimpleIOSuite with Checkers:
  test("reversing twice preserves a list"):
    forall: (values: List[Int]) =>
      expect.eql(values, values.reverse.reverse)
```

Preserve project generators and `CheckConfig` conventions.

## Checklist

- Confirm Weaver version and existing test conventions.
- Select `FunSuite`, `SimpleIOSuite`, or `IOSuite` by effect/resource needs.
- Model `beforeAll`/`afterAll` as `Resource` acquire/release in `sharedResource`.
- Declare normal suites as `object`.
- Return one explicitly composed `Expectations` from every body.
- Prefer `expect.eql`; add `clue` where observed values are unclear.
- Never place an existing `IO` inside `IO(...)`.
- Never return `IO.unit`; it silently discards the expectation.
- Do not use ScalaTest assertions inside Weaver suites.
- Never commit `.only`.
- Respect compiler flags, especially `-Wvalue-discard`.

## References

- [Suite types](https://typelevel.org/weaver-test/overview/installation.html#other-suites)
- [Expectations](https://typelevel.org/weaver-test/features/expectations.html)
- [Resources / lifecycle](https://typelevel.org/weaver-test/features/resources.html)
- [Global resources](https://typelevel.org/weaver-test/features/global_resources.html)
- [Filtering tests](https://typelevel.org/weaver-test/features/filtering.html)
- [Parallelism](https://typelevel.org/weaver-test/features/parallelism.html)
- [ScalaCheck](https://typelevel.org/weaver-test/features/scalacheck.html)
- [Discipline](https://typelevel.org/weaver-test/features/discipline.html)
