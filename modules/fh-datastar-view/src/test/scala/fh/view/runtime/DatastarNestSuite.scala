package fh.view.runtime

import fh.view.model.SignalId

import io.circe.Json

/** The signal nesting, against the implementation it replaced.
  *
  * `nest`/`nestJs` grouped and sorted at every level; they now sort once by
  * whole path and walk it by index, which is half a page open's allocation
  * (async-profiler over `RenderBench.pageSignals`). Nothing asserted their
  * output directly — the coverage was byte assertions in the server suites,
  * which exercise one shape each — so the ORDER and the ESCAPING are pinned
  * here against a reference that is the old code, kept in the test.
  */
class DatastarNestSuite extends munit.FunSuite {

  /** The pre-rewrite implementation, verbatim, as the oracle. */
  private object Reference {
    def nest(entries: List[(List[String], Json)]): Json =
      Json.obj(
        entries
          .groupBy(_._1.head)
          .toList
          .sortBy(_._1)
          .map { case (segment, rows) =>
            val deeper =
              rows.collect { case (_ :: rest, v) if rest.nonEmpty => rest -> v }
            segment -> (if (deeper.isEmpty) rows.head._2 else nest(deeper))
          }*
      )

    def nestJs(entries: List[(List[String], String)]): String =
      entries
        .groupBy(_._1.head)
        .toList
        .sortBy(_._1)
        .map { case (segment, rows) =>
          val deeper = rows.collect {
            case (_ :: rest, v) if rest.nonEmpty => rest -> v
          }
          val value =
            if (deeper.isEmpty) s"'${escapeJs(rows.head._2)}'"
            else nestJs(deeper)
          s"$segment: $value"
        }
        .mkString("{", ", ", "}")

    private def escapeJs(s: String): String =
      escapeHtmlAttr(s.replace("\\", "\\\\").replace("'", "\\'"))

    private def escapeHtmlAttr(s: String): String =
      s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
  }

  private def rows(names: String*): List[List[String]] =
    names.toList.map(_.split('.').toList)

  /** Signal names as `Renderer.signalName` mints them, plus the shapes where
    * sorting by dotted STRING would diverge from sorting by segment.
    *
    * `a.b` vs `ab` is NOT one of them — `.` sorts below every alphanumeric, so
    * the two agree there, which is what makes the string sort look safe. They
    * part only on a segment holding a character below `.` (0x2E): `a-b.c`
    * against `a.b`, where `-` beats the separator.
    */
  private val shapes: List[List[String]] = rows(
    "_e.sensor.a.state",
    "_e.sensor.b.state",
    "_e.light.taklys.state",
    "_e.light.taklys.brightness",
    "_e.light.a.fill",
    "a.b",
    "ab",
    "z",
    "m",
    "_c_0__value",
    "x.y",
    "x.z",
    "x.a.deep",
    "w"
  )

  /** Values that exercise every branch of the escaping, and their combinations
    * — the JS escape runs BEFORE the HTML one, so a backslash next to an
    * ampersand is where a one-pass rewrite would diverge.
    */
  private val values = List(
    "warm",
    "",
    "39.37%",
    "#ffb46b",
    "it's",
    "back\\slash",
    "a&b",
    "<tag>",
    "\"quoted\"",
    "\\&'\"<",
    "&amp;",
    "'; alert(1); '"
  )

  /** Name sets that must be crossed with EACH OTHER, not merely appear in the
    * fixture. A sliding window over [[shapes]] cannot do it: the first version
    * of this suite listed `a-b.c` and `a.b` six apart, so no window held both
    * and a comparator sorting by dotted string passed the whole suite.
    */
  private val orderingSets: List[List[List[String]]] = List(
    rows("a-b.c", "a.b"),
    rows("a.b", "a-b.c"),
    rows("a-b.c", "a.b", "ab"),
    rows("a.b", "ab", "a-b.c", "a-b.a"),
    rows("_e.a-b.state", "_e.a.state"),
    rows("x-y", "x.y", "xy")
  )

  private def signalsOf(paths: List[List[String]], vs: List[String]) =
    paths.zipWithIndex.map { case (p, i) =>
      SignalId.derived(p.mkString(".")) -> vs(i % vs.length)
    }.toMap

  test("the JS seed matches the grouping implementation it replaced") {
    // Every path shape against every value offset, so escaping and ordering
    // are crossed rather than tested one at a time.
    val cases = for {
      size <- 1 to 4
      window <- shapes.sliding(size).toList ++ orderingSets
      offset <- values.indices
    } yield (window, values.drop(offset) ++ values.take(offset))

    cases.foreach { case (paths, vs) =>
      val distinct = paths.distinct
      val signals = signalsOf(distinct, vs)
      // Only shapes that survive as a Map — a duplicate name is one signal.
      if (signals.size == distinct.size) {
        val expected = Reference.nestJs(
          signals.toList.map((k, v) => (k: String).split('.').toList -> v)
        )
        val actual = Datastar
          .signalsAttr(signals)
          .stripPrefix(" data-signals=\"")
          .stripSuffix("\"")
        assertEquals(actual, expected, clue = distinct)
      }
    }
  }

  test("the frame JSON matches the grouping implementation it replaced") {
    val cases = for {
      size <- 1 to 4
      window <- shapes.sliding(size).toList ++ orderingSets
    } yield window

    cases.foreach { paths =>
      val distinct = paths.distinct
      val signals = distinct.zipWithIndex.map { case (p, i) =>
        SignalId.derived(p.mkString(".")) -> Json.fromString(s"v$i")
      }.toMap
      if (signals.size == distinct.size) {
        val expected = Reference
          .nest(
            signals.toList.map((k, v) => (k: String).split('.').toList -> v)
          )
          .noSpaces
        assertEquals(Datastar.signalsJson(signals), expected, clue = distinct)
      }
    }
  }

  test("no signals is an empty object, and no attribute at all") {
    assertEquals(Datastar.signalsAttr(Map.empty), "")
    assertEquals(Datastar.signalsJson(Map.empty), "{}")
  }

  test("a seeded value cannot close its own JS literal") {
    // The trap the escaping exists for: `&#39;` decodes back to a bare quote,
    // so HTML-escaping alone would end the literal early.
    val attr = Datastar.signalsAttr(Map(SignalId.derived("a.b") -> "it's"))
    assert(!attr.contains("&#39;"), clue = attr)
    assert(attr.contains("""\'"""), clue = attr)
  }

  test("a precomputed seed renders what signalsAttr would") {
    // The fast path the renderer takes: the seed is built from the NAMES once
    // and a paint only fills values. It must be byte-identical to building the
    // whole attribute from the map, for every shape and every value.
    val cases = for {
      size <- 1 to 4
      window <- shapes.sliding(size).toList ++ orderingSets
      offset <- values.indices
    } yield (window, values.drop(offset) ++ values.take(offset))

    cases.foreach { case (paths, vs) =>
      val distinct = paths.distinct
      val signals = signalsOf(distinct, vs)
      if (signals.size == distinct.size) {
        val seed = Datastar.seedFor(signals.keys)
        val sb = new java.lang.StringBuilder
        Datastar.seedAttrInto(sb, seed, signals)
        assertEquals(
          sb.toString,
          Datastar.signalsAttr(signals),
          clue = distinct
        )
      }
    }
  }

  test("a seed handed the wrong signals falls back instead of lying") {
    // The guard: a seed's SHAPE is fixed, so filling it with names it was not
    // built for would emit a well-formed attribute nesting the wrong paths.
    val built = signalsOf(rows("_e.a.b.c", "_e.a.b.d"), List("1", "2"))
    val other = signalsOf(rows("x.y", "z"), List("3", "4"))
    val seed = Datastar.seedFor(built.keys)

    val sameSize = new java.lang.StringBuilder
    Datastar.seedAttrInto(sameSize, seed, other)
    assertEquals(sameSize.toString, Datastar.signalsAttr(other))

    val fewer = new java.lang.StringBuilder
    Datastar.seedAttrInto(fewer, seed, built.take(1))
    assertEquals(fewer.toString, Datastar.signalsAttr(built.take(1)))

    val none = new java.lang.StringBuilder
    Datastar.seedAttrInto(none, seed, Map.empty)
    assertEquals(none.toString, "")
  }
}
