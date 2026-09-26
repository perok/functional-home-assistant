package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.model.*
import fh.view.testkit.TestIds.setId
import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** A page's trace and the live path must agree on each node's digest: `holds`
  * is seeded from [[Renderer.Traced.own]], and `Patches.morph` compares against
  * [[Renderer.renderNodeById]]'s bytes. Two code paths, so the agreement is
  * pinned over generated shapes from an awkward-character pool (the escape set,
  * mustache tags, newlines, unicode, empties).
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
      // The trace records digests, so the fingerprint the walk wrote must be
      // the one the live path's bytes hash to.
      val recorded = r
        .renderBodyTraced(at(v), fragments = QuerySnapshot.empty)
        .own
        .values
        .head
        .digest
      val rendered =
        Digest.of(
          r.renderNodeById(
            NodeId.derived("c"),
            at(v),
            fragments = QuerySnapshot.empty
          ).get
        )
      assert(recorded == rendered)
    }
  }

  property("a member's recorded digest matches what the live path renders") {
    for {
      v <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(setDashboard(signal))
      val mid = NodeId.derived(r.members.memberIdOf(setId("c"), "alpha"))
      val recorded = r
        .renderBodyTraced(at(v), fragments = QuerySnapshot.empty)
        .own
        .get(mid)
        .get
        .digest
      val rendered =
        Digest.of(
          r.renderNodeById(mid, at(v), fragments = QuerySnapshot.empty).get
        )
      assert(recorded == rendered)
    }
  }
}
