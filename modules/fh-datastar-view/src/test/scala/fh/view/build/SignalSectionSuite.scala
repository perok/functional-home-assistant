package fh.view.build

import fh.view.runtime.Templates
import fh.view.testkit.PklWorkspace

/** A shipped card may not put a slot's signal binding inside a Mustache section
  * keyed on that slot's value. A signal slot is blank in the patch form (ADR
  * 0017), and blank is a false section, so the first patch touching the node
  * deletes the element and the binding that would refill it, silently, until a
  * reload. It shipped twice: it bites only when something else about the node
  * changes, usually the `busy` flag a tap sets. "It disappears when I click
  * it."
  *
  * The build refuses it for every card a dashboard uses
  * (`Templates.Reads.boundInOwnSection`; `TemplateReadsSuite` holds the
  * shapes). This holds every card the library ships to it, used by a test
  * dashboard or not, read off `components.pkl`'s `cards` index.
  */
class SignalSectionSuite extends munit.FunSuite {

  private lazy val shipped: Map[String, Templates.Card] = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp)
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "@fh-dashboard/components.pkl" as c
        |cards = c.cards
        |""".stripMargin
    )
    val cards = SourceEval
      .eval(tmp, "probe.pkl")
      .fold(e => fail(e), identity)
      .value
      .hcursor
      .downField("cards")
    cards.keys.toList.flatten.map { name =>
      val card = cards.downField(name)
      val template =
        card.get[String]("template").fold(e => fail(s"$name: $e"), identity)
      val regions = card.downField("regions").keys.toList.flatten.toSet
      name -> Templates
        .card(name, template, regions)
        .fold(e => fail(s"$name: $e"), identity)
    }.toMap
  }

  test("no shipped card binds a slot inside its own section") {
    val offenders = shipped.toList.flatMap { case (name, card) =>
      card.reads.boundInOwnSection.toList.map(slot => s"$name: $slot")
    }
    assertEquals(offenders.sorted, Nil)
  }

  test("the index reaches the shipped cards and their bindings") {
    // The check passes trivially on an empty index or a card with no binding.
    assert(shipped.sizeIs > 20, clue = shipped.keySet)
    val bound = shipped.values
      .flatMap(_.reads.views.collect { case (s, Templates.View.Bind) => s })
      .toSet
    assert(bound.contains("value"), clue = bound)
    assert(bound.sizeIs > 5, clue = bound)
  }
}
