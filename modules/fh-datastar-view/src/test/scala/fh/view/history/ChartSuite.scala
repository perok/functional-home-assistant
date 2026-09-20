package fh.view.history

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.*

/** The chart, both halves.
  *
  * The JavaScript runs on whatever engine this machine ships — the polyglot
  * ISOLATE where GraalVM publishes one (linux/amd64, linux/arm64), which is
  * also what the add-on runs, and interpreted where it does not (macOS). The
  * SVG is byte-identical between the two (measured,
  * `docs/plan-history-view.md`), so either proves the other; what running the
  * isolate adds is that the engine serving users is the one under test, instead
  * of a stand-in exercised nowhere but the `image` CI job.
  */
class ChartSuite extends munit.FunSuite {

  private val t0 = Instant.parse("2026-09-19T12:00:00Z")
  private def at(s: Long) = t0.plusSeconds(s)

  private def series(values: Double*): Series =
    Series(
      values.zipWithIndex.map { case (v, i) =>
        Series.Point(at(i.toLong * 60), v)
      }.toVector,
      0
    )

  // --- The option object ---------------------------------------------------

  private def option(s: Series, style: ChartStyle = ChartStyle()) =
    ChartOption(s, style).hcursor

  test("animation is off, and that is not cosmetic") {
    // SSR renders ONE frame. With animation on that frame is the start of
    // every transition, so the chart comes out empty or half-drawn.
    assertEquals(option(series(1, 2)).get[Boolean]("animation"), Right(false))
  }

  test("the x axis is time, and points carry their own timestamps") {
    // A category axis spaces points evenly whatever their timestamps, which is
    // exactly wrong for recorder rows: they are written when a value MOVES, so
    // the gaps mean something.
    val c = option(series(1, 2, 3))
    assertEquals(c.downField("xAxis").get[String]("type"), Right("time"))
    assertEquals(
      c.downField("series")
        .downArray
        .get[List[List[Json]]]("data")
        .map(_.map(_.head.asNumber.flatMap(_.toLong))),
      Right(
        List(
          Some(at(0).toEpochMilli),
          Some(at(60).toEpochMilli),
          Some(at(120).toEpochMilli)
        )
      )
    )
  }

  test("the y axis scales rather than anchoring at zero") {
    // An indoor temperature against a zero-anchored axis is a flat line at the
    // top of the box: true, and useless.
    assertEquals(
      option(series(21.4, 21.6)).downField("yAxis").get[Boolean]("scale"),
      Right(true)
    )
  }

  test("a unit is drawn and widens the gutter that has to hold it") {
    val without = option(series(1)).downField("grid").get[Int]("left")
    val with_ = option(series(1), ChartStyle(unit = Some("°C")))
    assertEquals(with_.downField("yAxis").get[String]("name"), Right("°C"))
    assert(
      with_
        .downField("grid")
        .get[Int]("left")
        .exists(l => without.exists(_ < l))
    )
  }

  test("a non-finite value becomes null rather than invalid JSON") {
    val data = ChartOption(
      Series(Vector(Series.Point(at(0), Double.NaN)), 0),
      ChartStyle()
    ).hcursor.downField("series").downArray.get[List[List[Json]]]("data")
    assertEquals(data.map(_.head.last), Right(Json.Null))
  }

  test("an empty series is still a valid option object") {
    // A sensor with nothing recorded is normal, and must render an empty chart
    // rather than fail the page.
    assertEquals(
      option(Series.empty)
        .downField("series")
        .downArray
        .get[List[Json]]("data"),
      Right(Nil)
    )
  }

  // --- The renderer --------------------------------------------------------

  /** One renderer for the suite, on whatever engine this machine ships —
    * normally the ISOLATE, which is what the add-on runs.
    *
    * `ChartRenderer.resource` and not a hand-built engine, so what these tests
    * exercise is the entry point production uses, engine choice included. It
    * falls back interpreted where GraalVM publishes no isolate (macOS), which
    * is sound because the SVG is byte-identical between the two (ADR 0032) —
    * but it is a FALLBACK now rather than what every run did, so a break that
    * only the isolate shows is visible here instead of only in the image job.
    *
    * Built once: evaluating ECharts is ~0.3 s on the isolate and ~1 s
    * interpreted, and nothing here needs a fresh one.
    */
  private def withRenderer[A](f: ChartRenderer => IO[A]): A =
    ChartRenderer
      .resource()
      .use(f)
      .timeout(120.seconds)
      .unsafeRunSync()

  test("a series renders to an SVG carrying a path") {
    val svg = withRenderer(_.render(series(1, 5, 2, 8, 3), ChartStyle()))
    assert(svg.startsWith("<svg"), clue = svg.take(200))
    assert(svg.contains("</svg>"), clue = svg.takeRight(200))
    // The line itself, not just a frame: an option object that ECharts refused
    // still produces axes, so asserting on `<svg` alone would pass for a chart
    // with no data on it.
    assert(svg.contains("<path"), clue = svg.take(400))
    assert(svg.contains("""width="600""""), clue = svg.take(200))
  }

  test("a CSS variable reaches the SVG verbatim, so the theme colours it") {
    // The whole theming story, and it only works if zrender passes the colour
    // through instead of parsing it: inline SVG inherits the page's custom
    // properties, so `stroke="var(--fh-accent)"` follows whichever theme is
    // active with the server knowing nothing about it. Asserted rather than
    // assumed, because a library that normalised colours to `#rrggbb` would
    // silently pin every chart to one palette.
    val svg = withRenderer(
      _.render(series(1, 5, 2), ChartStyle(line = "var(--fh-accent)"))
    )
    assert(svg.contains("var(--fh-accent)"), clue = svg.take(1200))
  }

  test("the SVG is self-contained — no script, no external reference") {
    // It is morphed into the page as ordinary bytes. Anything it reached out
    // for would be blocked, and anything it executed would be a surprise.
    //
    // The two `http://www.w3.org/…` strings ECharts emits are XML NAMESPACE
    // declarations, not fetches — nothing resolves them — so the check is for
    // things that actually load: a referenced image, or a url() in a style.
    val svg = withRenderer(_.render(series(1, 2, 3), ChartStyle()))
    assert(!svg.contains("<script"), clue = svg)
    assert(!svg.contains("<image"), clue = svg)
    // `url(#zr0-c0)` is ECharts' own clip path — a same-document fragment, so
    // it resolves inside the bytes. Only a url() naming something ELSE loads.
    assertEquals(
      "url\\((?!#)".r.findFirstIn(svg),
      None,
      clue = svg.take(400)
    )
    assertEquals(
      "https?://(?!www\\.w3\\.org)".r.findFirstIn(svg),
      None,
      clue = svg.take(400)
    )
  }

  test("an empty series renders rather than throwing") {
    val svg = withRenderer(_.render(Series.empty, ChartStyle()))
    assert(svg.startsWith("<svg"), clue = svg.take(200))
  }

  test("size is honoured, because the card decides it") {
    val svg = withRenderer(
      _.render(series(1, 2), ChartStyle(width = 320, height = 90))
    )
    assert(svg.contains("""width="320""""), clue = svg.take(200))
    assert(svg.contains("""height="90""""), clue = svg.take(200))
  }

  test(
    "renders are serialised, so concurrent charts do not corrupt each other"
  ) {
    // One context for the process; Graal contexts are not safe for concurrent
    // use, and the mutex is what makes that a non-issue rather than a race
    // that only shows up under load.
    val svgs = withRenderer { r =>
      List(3, 10, 40, 100)
        .parTraverse(n =>
          r.render(series((1 to n).map(_.toDouble)*), ChartStyle())
        )
    }
    assertEquals(svgs.length, 4)
    svgs.foreach(s =>
      assert(s.startsWith("<svg") && s.contains("</svg>"), clue = s.take(120))
    )
  }
}
