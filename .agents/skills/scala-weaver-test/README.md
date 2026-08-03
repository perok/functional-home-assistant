# scala-weaver-test

Guidance for writing and modifying **Scala 3 tests with [Weaver](https://typelevel.org/weaver-test/)** —
`weaver-cats`, `weaver-discipline`, and `weaver-scalacheck`. Covers suite
selection, effects, expectations, shared resources, and discipline laws.

## Weaver version

This guide targets **Weaver 0.13**, where:

- `FunSuiteIO` → `FunSuite`
- `MutableIOSuite` → `IOSuite`
- `SimpleMutableIOSuite` → `SimpleIOSuite`

For older pinned Weaver versions, preserve the names already used by the
project; do not perform an unrelated migration.

## Pick a suite

| Need | Suite |
|---|---|
| Pure `Expectations` only | `FunSuite` |
| Independent `IO[Expectations]` tests | `SimpleIOSuite` |
| One managed `Resource[IO, Res]` shared by tests | `IOSuite` |
| Discipline laws | `FunSuite with Discipline` |

Ordinary discovered suites are singleton objects. Classes are only for the
`GlobalRead` cross-suite resource-sharing pattern; see the
[global resources doc](https://typelevel.org/weaver-test/features/global_resources.html).

## Use when

- Writing or modifying Scala 3 tests with Weaver.
- Choosing a Weaver suite type by effect / resource needs.
- Modeling setup/teardown as `Resource` acquire/release (no `beforeAll`/`afterAll` hooks).
- Working with Weaver expectations, `Discipline`, or `ScalaCheck`.

## Files

```
scala-weaver-test/
  SKILL.md     # agent-facing instruction doc (loaded on demand)
  README.md    # this file (human overview)
```

The full instruction content — including suite templates, expectation
patterns, resource sharing, and discipline integration — lives in
[SKILL.md](SKILL.md).
