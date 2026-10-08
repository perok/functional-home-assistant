package fh.view.build

import cats.effect.{IO, Resource}
import fh.view.FHError
import fh.view.runtime.JsIsolate
import fh.view.telemetry.Logging
import fh.view.testkit.PklFixture
import io.circe.Json
import org.graalvm.polyglot.Engine

class MinifierSuite extends munit.CatsEffectSuite {

  override val munitIOTimeout = scala.concurrent.duration.Duration(3, "min")

  private val log = Logging.console.getLoggerFromName("MinifierSuite")

  /** The shipped theme, a slider (the one card with a script) and a popup. */
  private lazy val wire: Json = PklFixture
    .eval(
      "minify",
      """amends "@fh-dashboard/entry.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-home/dump.pkl" as dump
        |card = (c.column) {
        |  children {
        |    c.entitySlider(dump.lights.first)
        |    c.button("Open", c.tap.openPopupInline(c.title("x")))
        |  }
        |}
        |""".stripMargin
    )
    .value

  private def isolated(store: os.Path) =
    Minifier.onEngine(store, JsIsolate.engineOrInHeap(_ => IO.unit), log)

  private val noEngine: Resource[IO, Engine] =
    Resource.raiseError[IO, Engine, Throwable](
      new AssertionError("an engine was started")
    )

  test("every inline CSS and JS piece is minified") {
    isolated(os.temp.dir()).flatMap(_.dashboard(wire)).map { out =>
      val before = Minifier.piecesOf(wire)
      val after = Minifier.piecesOf(out).map(_._2).toSet
      assert(before.exists(_._1 == "card 'slider' script"), before.map(_._1))
      // A one-line rule with no comment may already be as small as it gets.
      before
        .filter((_, p) => p.source.contains("\n") || p.source.contains("/*"))
        .foreach { case (where, p) =>
          assert(!after.contains(p), clue = s"$where left as written")
        }
      val css = Minifier.piecesOf(out).collect {
        case (_, p) if p.lang == Minifier.Lang.Css => p.source
      }
      assert(css.forall(!_.contains("/*")), clue = css)
      val script = Minifier.piecesOf(out).collectFirst {
        case ("card 'slider' script", p) => p.source
      }
      assert(script.exists(_.contains("pointerdown")), clue = script)
      def bytes(j: Json) = Minifier.piecesOf(j).map(_._2.source.length).sum
      assert(
        bytes(out) < bytes(wire) * 85 / 100,
        clue = (bytes(out), bytes(wire))
      )
    }
  }

  test("content the store has seen starts no engine") {
    val store = os.temp.dir()
    for {
      first <- isolated(store).flatMap(_.dashboard(wire))
      again <- Minifier
        .onEngine(store, noEngine, log)
        .flatMap(_.dashboard(wire))
    } yield assertEquals(again, first)
  }

  test("a script that does not parse fails the dashboard, naming the card") {
    val broken = wire.hcursor
      .downField("cards")
      .downField("slider")
      .downField("script")
      .withFocus(_ => Json.fromString("function ( {"))
      .top
      .getOrElse(fail("no slider script"))
    isolated(os.temp.dir())
      .flatMap(_.dashboard(broken))
      .attempt
      .map {
        case Left(e: FHError) =>
          assert(e.getMessage.contains("card 'slider' script"), e.getMessage)
        case other => fail(s"expected an FHError, got $other")
      }
  }

  test(
    "a site serves minified CSS, and a broken script fails only its dashboard"
  ) {
    val broken = wire.hcursor
      .downField("cards")
      .downField("slider")
      .downField("script")
      .withFocus(_ => Json.fromString("function ( {"))
      .top
      .getOrElse(fail("no slider script"))
    val site = Json.obj(
      "dashboards" -> Json.obj("good" -> wire, "bad" -> broken)
    )
    isolated(os.temp.dir())
      .flatMap(m => Site.decode(site, minifier = m))
      .map { decoded =>
        val bySlug = decoded.dashboards.toMap
        assert(
          bySlug("bad").left.exists(_.contains("card 'slider' script")),
          bySlug("bad")
        )
        val good = bySlug("good").fold(e => fail(e), _.dashboard)
        assert(!good.css.contains("/*"), good.css.take(200))
        assert(!good.css.contains("\n"), good.css.take(200))
      }
  }

  test("a site is minified in one batch, so its dashboards start no engine") {
    val store = os.temp.dir()
    for {
      m <- isolated(store)
      _ <- m.prepare(List(wire))
      out <- Minifier.onEngine(store, noEngine, log).flatMap(_.dashboard(wire))
    } yield assertNotEquals(out, wire)
  }
}
