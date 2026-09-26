package fh.view.runtime

import fh.view.model.SignalId

import io.circe.Json

/** The signal nesting against the implementation it replaced, kept here as the
  * oracle. It sorts once by whole path and walks by index, half a page open's
  * allocation (async-profiler over `RenderBench.pageSignals`); the server
  * suites' byte assertions cover one shape each, so order and escaping are
  * pinned here.
  */
class DatastarNestSuite extends munit.FunSuite {

  /** Verbatim. */
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

  /** Plus the shapes where sorting by dotted string diverges from by segment:
    * not `a.b` vs `ab` (`.` sorts below every alphanumeric), but a segment
    * holding a character below `.` (0x2E), `a-b.c` against `a.b`.
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

  /** The JS escape runs before the HTML one, so a backslash beside an ampersand
    * is where a one-pass rewrite would diverge.
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

  /** Crossed with each other: a sliding window over [[shapes]] held `a-b.c` and
    * `a.b` six apart, so a dotted-string comparator passed the whole suite.
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
    val cases = for {
      size <- 1 to 4
      window <- shapes.sliding(size).toList ++ orderingSets
      offset <- values.indices
    } yield (window, values.drop(offset) ++ values.take(offset))

    cases.foreach { case (paths, vs) =>
      val distinct = paths.distinct
      val signals = signalsOf(distinct, vs)
      // A duplicate name is one signal.
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
    // `&#39;` decodes back to a bare quote, so HTML-escaping alone would end
    // the literal early.
    val attr = Datastar.signalsAttr(Map(SignalId.derived("a.b") -> "it's"))
    assert(!attr.contains("&#39;"), clue = attr)
    assert(attr.contains("""\'"""), clue = attr)
  }

  test("a precomputed seed renders what signalsAttr would") {
    // The renderer builds the seed from the names once and a paint fills
    // values; it must match building the whole attribute from the map.
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
    // A seed's shape is fixed, so the wrong names would nest the wrong paths in
    // a well-formed attribute.
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
