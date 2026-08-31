---
name: scala-hedgehog
description: Hedgehog property-based testing for Scala with munit — artifact, idioms, shrinking, seed replay, and pitfalls verified in this repo (DigestPropertySuite). Use when writing or reviewing property-based tests here, or whenever property testing with hedgehog comes up (context7 only indexes the HASKELL hedgehog).
---

## Why this skill exists

The Scala hedgehog (`hedgehogqa/scala-hedgehog`) is NOT in context7 — context7's
"hedgehog" is the Haskell original, whose API is genuinely different. The Scala
docs live at https://hedgehogqa.github.io/scala-hedgehog/ (read `docs`,
`integration-munit` and the tutorial before writing a suite from memory — this
was learned the hard way: the first suite here was written from the jar's
signatures and had to be rewritten once the docs were read).

## Artifact and wiring

ONE dependency, `% Test`:

```scala
"qa.hedgehog" %% "hedgehog-munit" % "0.14.0"
```

`hedgehog-core` and `hedgehog-runner` arrive transitively; do not add them and
do not wire a second test framework — `hedgehog-munit` provides
`hedgehog.munit.HedgehogSuite`, which extends munit's `FunSuite`, so hedgehog
properties run inside the existing munit suite with no runner config:

```scala
class MySuite extends HedgehogSuite {
  property("...") { ... }
}
```

## Idioms (the documented shape)

```scala
property("a leaf's recorded digest matches what the live path renders") {
  for {
    v      <- genValue.forAll        // <- .forAll INSIDE the for-comprehension
    signal <- Gen.boolean.forAll
  } yield {
    // plain Scala; munit's assertEquals returns a Result, and ==== is
    // hedgehog's equality Result with a shrunk diff on failure
    recorded ==== rendered
  }
}
```

- `for { x <- gen.forAll } yield result` is the whole pattern. Do NOT reach for
  `Property.fromGen` or `forAll(fn)` — the comprehension form is what the docs
  and integration page use.
- `Result.all(List(prop1, prop2, ...))` — all must hold; each contribution
  reports independently on failure.
- `prop.log("diagnosis text")` — attach a message that prints ONLY on failure,
  alongside the shrunk counterexample. This replaces println probing.
- `prop.classify("label", predicate)` — coverage tracking over the generated
  cases; a generator whose interesting cases never actually generate is the
  classic property-suite failure mode.
- Failing runs print a seed; `HEDGEHOG_SEED=<seed>` replays that exact run
  deterministically. Name this in the suite doc comment so a CI-only failure
  is reproducible locally.

## Generators

- `Gen.element1(a, b, c, ...)` — pick one (pos-arg, non-empty).
- `Gen.string(genChar, Range.linear(min, max))` — build strings from a char
  generator; `Range.linear` sizes.
- `Gen.frequency1((weight, gen), ...)` — weighted mix, e.g. mostly random
  strings plus a fixed pool of nasty literals (escape set, mustache tags,
  newlines, unicode, empty).
- Shrinking is INTEGRATED and automatic — never hand-write a shrink. What you
  owe instead: properties that fail WELL — assert the invariant, not the
  instance (`equal digest <=> equal bytes` shrinks to a minimal counterexample;
  a big structural comparison does not).

## Pitfalls (all hit in this repo once)

1. **Model only shapes the code supports.** A property whose fixture generates
   an unsupported shape (here: a member whose ROOT is a set node —
   `MemberGraph` only addresses members, not their roots) fails with a crash
   inside the code, which reads as a bug in the code. Model the supported
   shape (nested set as an authored child region) or scope the generator.
2. **Name which FORM you compare.** This renderer has a document form and a
   patch form (ADR 0017); the walk's trace is PATCH-form bytes, while
   `renderMemberById` renders DOCUMENT. A property that compares across forms
   falsifies on every signal-bearing case. Pin the form in the test name.
3. **Do not read `.values.head` of a multi-entry map.** Iteration order is an
   implementation detail (and changed when the trace became a
   `java.util.HashMap` accumulator). Look the key up.
4. **A passing property does not make the design right.** The ADR-0029
   (digest render inputs) properties all passed — and the bench still
   rejected the idea by +12-18% allocation. Properties verify CORRECTNESS;
   the bench verifies the point.
5. **Gen.string with a fixed pool is not a string generator.** Use
   `Gen.string(genChar, range)` over an interesting char set with
   `Gen.frequency1` for the literals — otherwise shrinking produces noise.

## The reference in this repo

`modules/fh-datastar-view/src/test/scala/fh/view/runtime/DigestPropertySuite.scala`
— two properties pinning walk-trace vs live-path byte agreement over generated
shapes (interesting-character pool, both render forms, named seeds). Read it
before writing a new property suite here.
