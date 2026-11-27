package fh.view.runtime

import cats.effect.IO
import fh.view.model.NodeId
import fh.view.testkit.HouseFixture

import scala.concurrent.duration.*

/** A variable declared with no value (`v.startingEmpty()`): a match over it
  * shows nothing until a viewer chooses, a clear returns to nothing, and
  * neither the commit nor the URL names a value.
  */
class NoValueSuite extends munit.CatsEffectSuite {

  override def munitIOTimeout: Duration = 90.seconds

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/core/variable.pkl" as varMod
      |import "@fh-dashboard/core/group.pkl" as groupMod
      |import "@fh-dashboard/core/node.pkl" as nodeMod
      |
      |const local pick: varMod.Variable = new { name = "pick" }
      |
      |card = (c.column) {
      |  vars { pick.startingEmpty() }
      |  children {
      |    c.button("A", pick.choose("a"))
      |    c.button("Clear", pick.choose(null))
      |    groupMod.group("div", "picked", nodeMod.match(pick.value, new Mapping { ["a"] = c.title("shown a") }))
      |  }
      |}
      |""".stripMargin

  private val key = VarKey(NodeId.derived("c"), "pick")

  private def served[A](f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("novalue", entry, List(HouseFixture.kitchenLight))
      .use(f)

  test("it holds no value until a viewer chooses, and a clear returns to it") {
    served(ts =>
      for {
        v <- ts.viewer()
        first = v.document.html
        _ <- ts.choose(v.document.conn, key, Some("a"))
        chosen <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        _ <- ts.choose(v.document.conn, key, None)
        cleared <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        state <- v.session.state
      } yield {
        assert(!first.contains("shown a"), clue = first)
        assert(first.contains(s"${key.committedSignal}: ''"), clue = first)
        assert(
          first.contains(
            s"""['${key.urlParam}', $$${key.committedSignal}, '']"""
          ),
          clue = first
        )
        assert(chosen.contains("shown a"), clue = chosen)
        assert(chosen.contains(s""""${key.committedSignal}":"a""""), chosen)
        assert(!cleared.contains("shown a"), clue = cleared)
        assert(cleared.contains(s""""${key.committedSignal}":"""""), cleared)
        assertEquals(state.vars.get(key), None)
      }
    )
  }

  test("a link naming a value opens on it") {
    served(ts =>
      ts.load(s"?${key.urlParam}=a")
        .map(doc => assert(doc.html.contains("shown a"), clue = doc.html))
    )
  }
}
