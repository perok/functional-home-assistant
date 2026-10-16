package fh.view.runtime

import cats.effect.IO
import fh.view.model.{CardDef, Dashboard, LayoutNode}
import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** A node variable with a start (`Node.starts`): a session opens it where an
  * entity's state says, once, and a choice or a link still wins.
  */
class VarStartSuite extends CatsEffectSuite {

  private def n(i: Int) = Json.fromInt(i)

  private def lamp(mode: String) = FixtureEntity(
    "light.lamp",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Lamp"),
      "supported_color_modes" -> Json.arr(
        Json.fromString("color_temp"),
        Json.fromString("hs")
      ),
      "min_color_temp_kelvin" -> n(2000),
      "max_color_temp_kelvin" -> n(6500),
      "color_mode" -> Json.fromString(mode),
      "color_temp_kelvin" -> n(3000),
      "brightness" -> n(180),
      "hs_color" -> Json.arr(n(30), n(80))
    )
  )

  // What `opensOn` will write: the head the light's mode is on.
  private val byMode =
    """attr[?'color_mode'].orValue('') == 'hs' ? 'colour' : (attr[?'color_mode'].orValue('') == 'color_temp' ? 'colourTemp' : 'nonsense')"""

  private def entry(start: String) =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/core/variable.pkl" as varMod
       |import "@fh-home/dump.pkl" as dump
       |
       |local l = dump.entities.${lamp("hs").dumpKey}
       |
       |card = (c.column) {
       |  children {
       |    (c.entitySlider(l)) {
       |      starts = (new varMod.Variable { name = "control" }).start(l, #"$start"#)
       |    }
       |  }
       |}
       |""".stripMargin

  private val declarerOf =
    """data-fh-url:control="\['v\.([A-Za-z0-9_]+)\.control'""".r

  private def served[A](mode: String, start: String = byMode)(
      f: TestServer => IO[A]
  ): IO[A] =
    TestServer
      .fromWorkspace("lamp", entry(start), List(lamp(mode)))
      .use(f)
      .timeout(60.seconds)

  private def declarer(html: String): String =
    declarerOf
      .findFirstMatchIn(html)
      .fold(fail(s"no control mirror on the page: $html"))(_.group(1))

  private def startOn(
      vars: Map[String, String],
      start: Dashboard.Start
  ): Dashboard =
    Dashboard(
      cards = Map("plain" -> CardDef("<span></span>")),
      card = LayoutNode.Component(
        "plain",
        vars = vars,
        starts = Map("control" -> start)
      )
    )

  test(
    "a start for an undeclared variable, or one that does not compile, fails the build"
  ) {
    val ok = Dashboard.Start("light.lamp", "'colour'")
    assertEquals(startOn(Map("control" -> "brightness"), ok).validate(), Nil)
    assert(
      startOn(Map.empty, ok)
        .validate()
        .exists(
          _.contains("a start for 'control', which this node does not declare")
        )
    )
    assert(
      startOn(Map("control" -> "brightness"), ok.copy(transform = "attr["))
        .validate()
        .exists(_.contains("the start for 'control' does not compile"))
    )
  }

  test("a start's entity is watched, since it decides what the page opens on") {
    val d = startOn(
      Map("control" -> "brightness"),
      Dashboard.Start("light.other", "'colour'")
    )
    assert(d.watchedEntities.contains("light.other"), d.watchedEntities)
  }

  test("a fresh page opens on the head its start names") {
    served("hs") { ts =>
      ts.page().map { html =>
        val d = declarer(html)
        assert(html.contains("max=\"360\""), html)
        assert(!html.contains("max=\"255\""), html)
        assert(html.contains(s"_var_${d}__control: 'colour'"), html)
        // The mirror leaves the start off the URL, as it does a declared value.
        assert(html.contains(s"_var_${d}__control__start: 'colour'"), html)
        assert(
          html.contains(
            s"""data-fh-url:control="['v.$d.control', $$_var_${d}__control, $$_var_${d}__control__start]""""
          ),
          html
        )
      }
    }
  }

  test("a link's choice wins over the start") {
    served("hs") { ts =>
      for {
        plain <- ts.page()
        linked <- ts.page(s"?v.${declarer(plain)}.control=brightness")
      } yield {
        assert(linked.contains("max=\"255\""), linked)
        assert(!linked.contains("max=\"360\""), linked)
      }
    }
  }

  test("a start no reader takes keeps the declared value") {
    served("xy") { ts =>
      ts.page().map { html =>
        val d = declarer(html)
        assert(html.contains("max=\"255\""), html)
        assert(html.contains(s"_var_${d}__control: 'brightness'"), html)
        assert(html.contains(s"_var_${d}__control__start: 'brightness'"), html)
      }
    }
  }

  test("an open page keeps its head when the light changes mode") {
    served("hs") { ts =>
      for {
        v <- ts.viewer()
        sent <- v.step(lamp("color_temp")).map(_.flatMap(_.data).mkString)
        state <- v.session.state
        // The store did move: a page opening now starts on temperature.
        fresh <- ts.page()
      } yield {
        assert(!sent.contains("max=\"6500\""), sent)
        assert(fresh.contains("max=\"6500\""), fresh)
        assertEquals(state.vars, Map.empty)
        assertEquals(state.starts.values.toList, List("colour"))
      }
    }
  }
}
