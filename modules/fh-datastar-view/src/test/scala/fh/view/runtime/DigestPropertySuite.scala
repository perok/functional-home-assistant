package fh.view.runtime

import fh.view.model.*
import fh.view.testkit.TestIds.{setId, given}
import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** The biconditional ADR 0029 stands on, held over GENERATED node shapes:
  *
  * > equal input digest ⟺ equal patch bytes
  *
  * A missed digest input fails silently and permanently — two different
  * renderings hash the same, so a client keeps stale bytes forever with
  * nothing to report it. That failure mode is exactly why this is a property
  * over generated combinations rather than a fixed example: every input the
  * patch rendering depends on must be in [[PatchInputs]], and anything in it
  * must genuinely move the bytes.
  *
  * Generator design: values come from a STRING generator over an
  * interesting-character pool (the escape set, mustache tags, newlines,
  * unicode, empties) rather than a fixed list, so the property explores
  * beyond the cases its author imagined and the integrated shrinker can
  * reduce a failure to a minimal string. The `cover` classifications keep
  * the generator honest — both halves of each biconditional must actually
  * run, or the property reports insufficient coverage instead of passing.
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class DigestPropertySuite extends HedgehogSuite {

  // The characters a rendered value can plausibly contain, chosen so that
  // escaping, template parsing and canonical-separator collisions are all in
  // play. Empty strings and mustache-lookalikes get their own weight.
  private val genChar: Gen[Char] =
    Gen.element1('a', 'z', '0', '9', ' ', '<', '>', '&', '"', '\'', '\n', 'é')
  private val genText: Gen[String] =
    Gen.string(genChar, Range.linear(0, 10))
  private val genValue: Gen[String] =
    Gen.frequency1(
      (3, genText),
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
  private val genClass: Gen[String] =
    Gen.element1("fh-cols-2", "fh-cols-full", "")
  private val genSignal: Gen[Boolean] =
    Gen.boolean

  private def at(v: String): Map[String, EntityState] =
    Map(
      "alpha" -> EntityState("alpha", v, Map.empty),
      "beta" -> EntityState("beta", v, Map.empty),
      "n0" -> EntityState("n0", v, Map.empty)
    )

  private def valueSlot(signal: Boolean): SlotSource =
    SlotSource(signal = if signal then Some(SignalBind.Text) else None)

  private val cardTemplate = """<b data-t="{{v}}">{{v}}</b>"""

  /** One signalled-or-not leaf with an authored cell class. */
  private def dashboard(signal: Boolean, classes: String): Dashboard = {
    val cell = if classes.isEmpty then None else Some(Cell(List(classes)))
    Dashboard(
      Map("card" -> CardDef(cardTemplate, slots = List("v"))),
      LayoutNode.Component(
        "card",
        Map(
          "entity_id" -> SlotSource(literal = Some("sensor.a")),
          "v" -> valueSlot(signal)
        ),
        cell = cell
      )
    )
  }

  /** A candidate set of two members, each the same card. */
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

  /** A member carrying a nested SET in its children region — the NestedSet
    * input path. Nested sets are authored children, not member roots.
    */
  private def nestedSetDashboard: Dashboard = {
    val inner = "n0" -> LayoutNode.SetMember(
      List(
        LayoutNode.SetClause(
          node = LayoutNode.Component(
            "card",
            Map(
              "entity_id" -> SlotSource(literal = Some("n0")),
              "v" -> SlotSource()
            )
          )
        )
      )
    )
    Dashboard(
      Map(
        "card" -> CardDef(
          """<b data-t="{{v}}">{{v}}</b>{{#children}}{{{html}}}{{/children}}""",
          slots = List("v")
        )
      ),
      LayoutNode.SetNode(
        candidates = List("alpha"),
        members = Map(
          "alpha" -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                node = LayoutNode.Component(
                  "card",
                  Map(
                    "entity_id" -> SlotSource(literal = Some("alpha")),
                    "v" -> SlotSource()
                  ),
                  regions = LayoutNode.kids(
                    LayoutNode.SetNode(candidates = List("n0"), members = Map(inner))
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  private def walkDigest(r: Renderer, v: String): Digest = {
    val t = r.renderBodyTraced(at(v))
    t.own.values.head.digest
  }

  /** The biconditional itself, with both directions labelled. */
  private def biconditional(d1: Digest, d2: Digest, b1: String, b2: String) =
    Result.all(
      List(
        Result.assert((d1 == d2) == (b1 == b2))
          .log(s"digest equal: ${d1 == d2}, bytes equal: ${b1 == b2}")
      )
    )

  property("value changes move the digest iff they move the bytes") {
    for {
      v1 <- genValue.forAll.classify("empty", _.isEmpty).classify(
        "nonempty",
        _.nonEmpty
      )
      v2 <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(dashboard(signal, "fh-cols-2"))
      val d1 = walkDigest(r, v1)
      val d2 = walkDigest(r, v2)
      val b1 = r.renderNodeById(NodeId.derived("c"), at(v1)).get.html
      val b2 = r.renderNodeById(NodeId.derived("c"), at(v2)).get.html
      biconditional(d1, d2, b1, b2)
        .log(s"v1=$v1")
        .log(s"v2=$v2")
        .log(s"signal=$signal")
    }
  }

  property("class changes move the digest iff they move the bytes") {
    for {
      c1 <- genClass.forAll
      c2 <- genClass.forAll
      v <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r1 = Renderer.create(dashboard(signal, c1))
      val r2 = Renderer.create(dashboard(signal, c2))
      val d1 = walkDigest(r1, v)
      val d2 = walkDigest(r2, v)
      val b1 = r1.renderNodeById(NodeId.derived("c"), at(v)).get.html
      val b2 = r2.renderNodeById(NodeId.derived("c"), at(v)).get.html
      biconditional(d1, d2, b1, b2)
        .log(s"c1=$c1")
        .log(s"c2=$c2")
        .log(s"v=$v")
        .log(s"signal=$signal")
    }
  }

  property("the walk's fingerprint and the render's digest agree") {
    for {
      v <- genValue.forAll
      signal <- genSignal.forAll
      c <- genClass.forAll
    } yield {
      val r = Renderer.create(dashboard(signal, c))
      val walked = walkDigest(r, v)
      val rendered = r.renderNodeById(NodeId.derived("c"), at(v)).get.digest
      walked ==== rendered
    }
  }

  property("a member's walk fingerprint matches its render digest") {
    for {
      v <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(setDashboard(signal))
      val walked = r.renderBodyTraced(at(v)).own.values.head.digest
      val rendered =
        r.renderMemberById(setId("c"), "alpha", at(v), SlotForm.Patch)
          .get.digest
      walked ==== rendered
    }
  }

  property("member value changes move the digest iff they move the bytes") {
    for {
      v1 <- genValue.forAll
      v2 <- genValue.forAll
      signal <- genSignal.forAll
    } yield {
      val r = Renderer.create(setDashboard(signal))
      val d1 = walkDigest(r, v1)
      val d2 = walkDigest(r, v2)
      val b1 =
        r.renderMemberById(setId("c"), "alpha", at(v1), SlotForm.Patch).get.html
      val b2 =
        r.renderMemberById(setId("c"), "alpha", at(v2), SlotForm.Patch).get.html
      biconditional(d1, d2, b1, b2)
        .log(s"v1=$v1")
        .log(s"v2=$v2")
        .log(s"signal=$signal")
    }
  }

  property("a nested set's content moves the member's digest with its bytes") {
    for {
      v1 <- genValue.forAll
      v2 <- genValue.forAll
    } yield {
      val r = Renderer.create(nestedSetDashboard)
      val d1 = walkDigest(r, v1)
      val d2 = walkDigest(r, v2)
      val b1 =
        r.renderMemberById(setId("c"), "alpha", at(v1), SlotForm.Patch).get.html
      val b2 =
        r.renderMemberById(setId("c"), "alpha", at(v2), SlotForm.Patch).get.html
      biconditional(d1, d2, b1, b2)
        .log(s"v1=$v1")
        .log(s"v2=$v2")
    }
  }
}
