package fh.view.history

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.*

/** The chart, both halves. The JavaScript runs on the polyglot isolate where
  * GraalVM publishes one (linux/amd64, linux/arm64, as the add-on does) and
  * interpreted elsewhere (macOS). The SVG is byte-identical between the two
  * (ADR 0032), so either proves the other, and the engine serving users is the
  * one under test.
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

  private def option(s: Series, style: ChartStyle = ChartStyle()) =
    ChartOption(s, style).hcursor

  test("animation is off, and that is not cosmetic") {
    // SSR renders one frame; with animation it is the start of every
    // transition, so the chart comes out empty or half-drawn.
    assertEquals(option(series(1, 2)).get[Boolean]("animation"), Right(false))
  }

  test("the x axis is time, and points carry their own timestamps") {
    // A category axis spaces points evenly, which is wrong for recorder rows:
    // they are written when a value moves, so the gaps mean something.
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
    // Against a zero-anchored axis an indoor temperature is a flat line at the
    // top: true, and useless.
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
    // A sensor with nothing recorded is normal.
    assertEquals(
      option(Series.empty)
        .downField("series")
        .downArray
        .get[List[Json]]("data"),
      Right(Nil)
    )
  }

  /** `ChartRenderer.resource`, the production entry point, engine choice
    * included. Built once: evaluating ECharts is ~0.3 s on the isolate and ~1 s
    * interpreted.
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
    // A refused option object still produces axes, so `<svg` alone would pass
    // for a chart with no data.
    assert(svg.contains("<path"), clue = svg.take(400))
    assert(svg.contains("""width="400""""), clue = svg.take(200))
  }

  test("a CSS variable reaches the SVG verbatim, so the theme colours it") {
    // Inline SVG inherits the page's custom properties, so
    // `stroke="var(--fh-accent)"` follows the active theme, but only if zrender
    // passes the colour through. A library normalising to `#rrggbb` would pin
    // every chart to one palette.
    val svg = withRenderer(
      _.render(series(1, 5, 2), ChartStyle(line = "var(--fh-accent)"))
    )
    assert(svg.contains("var(--fh-accent)"), clue = svg.take(1200))
  }

  test("no colour in the drawing is a literal, so a theme reaches all of it") {
    // The bytes are shared by every viewer and cached across a light/dark
    // switch, so a baked colour (ECharts' default grey labels and grid) is
    // wrong on the other palette. The one `#000` is the clip path's mask, never
    // painted.
    val svg = withRenderer(
      _.render(series(1, 5, 2), ChartStyle(unit = Some("°C")))
    )
    assertEquals(
      """(fill|stroke)="#(?!000")""".r.findFirstIn(svg),
      None,
      clue = svg.take(1200)
    )
  }

  test("the SVG is self-contained — no script, no external reference") {
    // Morphed in as ordinary bytes, so nothing may load or execute. The two
    // `http://www.w3.org/…` strings are XML namespaces, not fetches.
    val svg = withRenderer(_.render(series(1, 2, 3), ChartStyle()))
    assert(!svg.contains("<script"), clue = svg)
    assert(!svg.contains("<image"), clue = svg)
    // `url(#zr0-c0)` is ECharts' own clip path, a same-document fragment.
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

  test("a drawing that outlives its timeout stops, and the next one draws") {
    // `IO.blocking` ignores cancellation, so without an interrupt a timeout
    // would wait out the drawing, holding the lock every other chart queues
    // behind.
    val big = series((1 to 100000).map(i => (i % 97).toDouble)*)
    val (full, cut, next) = withRenderer { r =>
      for {
        full <- r.render(big, ChartStyle()).timed.map(_._1)
        cut <- r.render(big, ChartStyle()).timeout(full / 10).attempt.timed
        next <- r.render(series(1, 2, 3), ChartStyle())
      } yield (full, cut, next)
    }
    assert(cut._2.isLeft, clue = cut._2)
    assert(cut._1 < full / 2, clue = s"the timeout waited out the draw: $cut")
    assert(next.startsWith("<svg"), clue = next.take(200))
  }

  test(
    "renders are serialised, so concurrent charts do not corrupt each other"
  ) {
    // One context per process: Graal contexts are not safe for concurrent use,
    // and the mutex keeps that from being a race under load.
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
