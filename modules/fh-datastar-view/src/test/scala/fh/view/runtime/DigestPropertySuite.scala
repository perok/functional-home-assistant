package fh.view.runtime

import fh.view.model.*
import fh.view.testkit.TestIds.{setId, given}
import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** What a page's trace records and what the live path renders must be the same
  * digest, node for node: `holds` is seeded from [[Renderer.Traced.own]], and
  * `Patches.morph` compares against [[Renderer.renderNodeById]]'s bytes — two
  * code paths that used to share one string by construction. Since the walk
  * threads one buffer (#253) and members render inline (#254), they are two
  * code paths agreeing, and these properties pin the agreement over GENERATED
  * shapes (values from an interesting-character pool: the escape set, mustache
  * tags, newlines, unicode, empties) rather than over the fixed fixtures the
  * example suites use.
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class DigestPropertySuite extends HedgehogSuite {

  private val genChar: Gen[Char] =
    Gen.element1('a', 'z', '0', '9', ' ', '<', '>', '&', '"', '\'', '\n', 'é')
  private val genValue: Gen[String] =
    Gen.frequency1(
      (3, Gen.string(genChar, Range.linear(0, 10))),
      (1, Gen.constant("")),
      (
        1,
        Gen.element1(
          "{{not_a_var}} {{{also_not}}}",
          "<b>&\"'</b>",
          "100%",
          "héllo — l1\nl2"
        )
      )
    )
  private val genSignal: Gen[Boolean] =
    Gen.boolean

  private def at(v: String): Map[String, EntityState] =
    Map(
      "alpha" -> EntityState("alpha", v, Map.empty),
      "beta" -> EntityState("beta", v, Map.empty)
    )

  private def valueSlot(signal: Boolean): SlotSource =
    SlotSource(signal = if signal then Some(SignalBind.Text) else None)

  private val cardTemplate = """<b data-t="{{v}}">{{v}}</b>"""

  private def dashboard(signal: Boolean): Dashboard =
    Dashboard(
      Map("card" -> CardDef(cardTemplate, slots = List("v"))),
      LayoutNode.Component(
        "card",
        Map(
          "entity_id" -> SlotSource(literal = Some("sensor.a")),
          "v" -> valueSlot(signal)
        )
      )
    )

  private def setDashboard(signal: Boolean): Dashboard = {
    val members = List("alpha", "beta").map { e =>
      e -> LayoutNode.SetMember(
        List(
          LayoutNode.SetClause(
            node = LayoutNode.Component(
              "card",
              Map(
                "entity_id" -> SlotSource(literal = Some(e)),
                "v" -> valueSlot(signal)
              )
            )
          )
        )
      )
    }.toMap
    Dashboard(
      Map("card" -> CardDef(cardTemplate, slots = List("v"))),
      LayoutNode.SetNode(candidates = List("alpha", "beta"), members = members)
    )
  }

  property("a leaf's recorded digest matches what the live path renders") {
    for {
      v <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(dashboard(signal))
      val recorded = r.renderBodyTraced(at(v)).own.values.head.html
      val rendered = r.renderNodeById(NodeId.derived("c"), at(v)).get
      recorded ==== rendered
    }
  }

  property("a member's recorded digest matches what the live path renders") {
    for {
      v <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(setDashboard(signal))
      val mid = NodeId.derived(r.members.memberIdOf(setId("c"), "alpha"))
      val recorded = r.renderBodyTraced(at(v)).own.get(mid).get.html
      val rendered = r.renderNodeById(mid, at(v)).get
      recorded ==== rendered
    }
  }
}
