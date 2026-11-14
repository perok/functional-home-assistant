package fh.view.runtime

import fh.view.testkit.TestCard
import fh.view.fetch.FetchSnapshot
import fh.view.model.*
import fh.view.testkit.TestIds.setId
import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** A node's byte-slot values are its render key and what a session compares
  * (ADR 0012): `holds` is seeded from [[Renderer.Traced.own]], and
  * `Patches.morph` compares against [[Renderer.byteSlotValues]]. So the two
  * must agree, and equal values must mean equal bytes; pinned over generated
  * shapes from an awkward-character pool (the escape set, mustache tags,
  * newlines, unicode, empties).
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class ByteValuesPropertySuite extends HedgehogSuite {

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

  private def at(v: String): Shared =
    Shared.of(
      Map(
        "alpha" -> EntityState("alpha", v, Map.empty),
        "beta" -> EntityState("beta", v, Map.empty)
      )
    )

  private def valueSlot(signal: Boolean): TemplateSlot =
    TemplateSlot(
      ValueExpr(Value.Entity(None)),
      signal = if signal then Some(SignalBind.Text) else None
    )

  // A signal slot is placed by its bind; without one the value never updates.
  private def card(signal: Boolean): CardDef =
    TestCard(
      if signal then """<b {{{v.bind}}} data-t="{{v}}">{{v}}</b>"""
      else """<b data-t="{{v}}">{{v}}</b>"""
    )

  private def dashboard(signal: Boolean): Dashboard =
    Dashboard(
      Map("card" -> card(signal)),
      LayoutNode.Component(
        "card",
        Map(
          "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.a")),
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
                "entity_id" -> TemplateSlot(ValueExpr.literal(e)),
                "v" -> valueSlot(signal)
              )
            )
          )
        )
      )
    }.toMap
    Dashboard(
      Map("card" -> card(signal)),
      LayoutNode.SetNode(candidates = List("alpha", "beta"), members = members)
    )
  }

  property(
    "a leaf's recorded values are the live path's, and decide its bytes"
  ) {
    // `Patches.morph` skips a node whose held values equal the live ones, so
    // the walk's values must be the live path's, and equal values equal bytes.
    for {
      v <- genValue.forAll
      w <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(dashboard(signal))
      val id = NodeId.derived("c")
      val viewing = Viewing(Selections.none, FetchSnapshot.empty)
      val recorded = r.renderBodyTraced(at(v), viewing).own.values.head.values
      def values(s: String) = r.byteSlotValues(id, at(s), FetchSnapshot.empty)
      def bytes(s: String) = r.renderNodeById(id, at(s), viewing)
      Result.all(
        List(
          Result.assert(values(v).contains(recorded)),
          Result.assert(values(v) != values(w) || bytes(v) == bytes(w))
        )
      )
    }
  }

  property(
    "a member's recorded values are the live path's, and decide its bytes"
  ) {
    for {
      v <- genValue.forAll
      w <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(setDashboard(signal))
      val mid = NodeId.derived(r.members.memberIdOf(setId("c"), "alpha"))
      val viewing = Viewing(Selections.none, FetchSnapshot.empty)
      val recorded = r.renderBodyTraced(at(v), viewing).own.get(mid)
      def values(s: String) = r.byteSlotValues(mid, at(s), FetchSnapshot.empty)
      def bytes(s: String) = r.renderNodeById(mid, at(s), viewing)
      Result.all(
        List(
          Result.assert(recorded.map(_.values) == values(v)),
          Result.assert(values(v).isDefined),
          Result.assert(values(v) != values(w) || bytes(v) == bytes(w))
        )
      )
    }
  }
}
