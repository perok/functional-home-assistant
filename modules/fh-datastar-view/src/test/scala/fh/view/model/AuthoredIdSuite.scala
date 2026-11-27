package fh.view.model

import fh.view.testkit.PklFixture

/** What `validate` holds an authored `id` to, on shapes the library builds. */
class AuthoredIdSuite extends munit.FunSuite {

  private def idErrors(card: String): List[String] =
    PklFixture.snippet(s"card = $card").validate().filter(_.contains("node id"))

  test("an authored id used twice is refused") {
    assertEquals(
      idErrors(
        """(c.column) { children {
          |  (t.label("a")) { id = "x" }
          |  (t.label("b")) { id = "x" }
          |} }""".stripMargin
      ),
      List("node id 'x' is used more than once")
    )
  }

  test("an authored id in a set's clause is refused") {
    assertEquals(
      idErrors(
        """(c.column) { children {
          |  q.from(List(dump.entities.light_kitchen))
          |    .render((e) -> (t.label("a")) { id = "x" }).build()
          |} }""".stripMargin
      ).map(_.take(45)),
      List("node id 'x' is inside a candidate set's claus")
    )
  }

  test("a set's host may carry an authored id: its members are named apart") {
    assertEquals(
      idErrors(
        """(c.column) { children {
          |  (q.from(List(dump.entities.light_kitchen))
          |    .render((e) -> t.label("a")).build()) { id = "lights" }
          |} }""".stripMargin
      ),
      Nil
    )
  }
}
