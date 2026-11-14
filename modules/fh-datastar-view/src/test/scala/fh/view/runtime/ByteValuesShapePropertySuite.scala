package fh.view.runtime

import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.history.Windows
import fh.view.model.*
import fh.view.testkit.DashboardBuilders.declared
import fh.view.testkit.TestCard
import fh.view.testkit.TestIds.setId
import hedgehog.*
import hedgehog.core.{Cover, CoverPercentage, LabelName, SuccessCount}
import hedgehog.munit.HedgehogSuite
import io.circe.Json

import scala.collection.immutable.ListMap

/** Equal byte-slot values mean equal patch bytes (ADR 0012), over generated
  * NODE SHAPES rather than one card: every kind of source a slot reads, gated
  * or not, as bytes or a signal, placed as text, an attribute or a section,
  * beside live and at-render cell classes, standing alone or as a set member
  * whose clause picks the card. Anything that reaches a leaf's patch bytes
  * outside its slots shows up as two worlds one input apart that share their
  * values and differ in bytes. The cover keeps that case from going missing.
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class ByteValuesShapePropertySuite extends HedgehogSuite {
  import ByteValuesShapePropertySuite.*

  property(
    "equal byte-slot values mean equal patch bytes, any node shape",
    _.copy(testLimit = SuccessCount(300))
  ) {
    for {
      c <- genSample.forAll
        .cover(
          CoverPercentage(15),
          LabelName("an input moved, the values did not"),
          c => Cover.Boolean2Cover(c.valid && c.w1 != c.w2 && c.v1 == c.v2)
        )
        .cover(
          CoverPercentage(5),
          LabelName("the values moved"),
          c => Cover.Boolean2Cover(c.valid && c.v1 != c.v2)
        )
    } yield
      if (!c.valid)
        Result.failure.log(s"generated an invalid shape: ${c.errors}")
      else
        Result
          .all(
            List(
              Result.assert(c.v1.isDefined).log("a leaf has byte-slot values"),
              Result
                .assert(c.walked(c.w1) == c.v1)
                .log("the walk's are the live path's"),
              Result
                .assert(c.walked(c.w2) == c.v2)
                .log("the walk's are the live path's"),
              Result
                .assert(c.v1 != c.v2 || c.bytes(c.w1) == c.bytes(c.w2))
                .log(
                  s"equal values ${c.v1}, bytes ${c.bytes(c.w1)} / ${c.bytes(c.w2)}"
                )
            )
          )
          .log(s"shape $c")
  }
}

object ByteValuesShapePropertySuite {

  private val history = FetchTemplate("sensor.a", Choice.Fixed(Windows.day))
  private val read =
    SlotRead(FetchRequest.History("sensor.a", Windows.day), Step.Json)
  // Two of one size, so the answer moves while what the slot reads does not.
  private val answers = Vector(
    """{"points":[]}""",
    """{"points":[[0,1.0]]}""",
    """{"points":[[0,2.0]]}""",
    """{"points":[[0,1.0],[60000,3.0]]}"""
  )

  private def is(v: Value, lit: String): ValueExpr =
    ValueExpr(v, List(Step.Compare(Op.Eq, Value.Literal(Lit.Text(lit)))))

  private def isOn(e: Option[String]) = is(Value.Entity(e), "on")

  enum Source derives CanEqual {
    case State(e: String)
    case Attr(e: String, name: String)
    case Cel(e: String)
    case IsOn(e: String)
    case Window
    case WindowIs7d
    case Points
    case Literal(s: String)

    def bool: Boolean = this match
      case IsOn(_) | WindowIs7d => true
      case _                    => false

    def expr: ValueExpr = this match
      case State(e)   => ValueExpr(Value.Entity(Some(e)))
      case Attr(e, n) => ValueExpr(Value.Entity(Some(e), Part.Attr(n)))
      case Cel(e)     =>
        ValueExpr(
          Value.Entity(Some(e), Part.Whole),
          List(
            Step.Cel(
              "value.attr[?'n'].optMap(n, string(n)).orValue('-') + value.state"
            )
          )
        )
      case IsOn(e)    => isOn(Some(e))
      case Window     => ValueExpr(Value.Var("window"))
      case WindowIs7d => is(Value.Var("window"), "7d")
      case Points     =>
        ValueExpr(
          Value.Entity(Some("sensor.a"), Part.Whole),
          List(
            Step.Cel(
              "string(size(past))",
              Map("past" -> ValueExpr(Value.Fetch(history)))
            )
          )
        )
      case Literal(s) => ValueExpr.literal(s)
  }

  enum Place derives CanEqual { case Text, Attr, Section }

  case class SlotSpec(
      source: Source,
      place: Place,
      signal: Boolean,
      gated: Boolean
  ) {
    def slot: TemplateSlot = TemplateSlot(
      source.expr,
      valueWhile = Option.when(gated)(isOn(Some("sensor.b"))),
      signal = Option.when(signal)(SignalBind.Text),
      `type` = if source.bool then SlotType.Bool else SlotType.Text
    )

    def hole(n: String): String =
      if (signal) s"""<b {{{$n.bind}}}>{{$n}}</b>"""
      else if (source.bool) s"{{#$n}}Y{{/$n}}{{^$n}}N{{/$n}}"
      else
        place match
          case Place.Text    => s"{{$n}}"
          case Place.Attr    => s"""<i data-k="{{$n}}"></i>"""
          case Place.Section => s"{{#$n}}[{{$n}}]{{/$n}}"
  }

  case class Shape(
      slots: List[SlotSpec],
      liveClass: Boolean,
      liveStyle: Boolean,
      atRender: Boolean,
      member: Boolean
  ) {
    private def names = slots.indices.map(i => s"s$i")

    def template(tail: String): String =
      names.zip(slots).map((n, s) => s.hole(n)).mkString("<p>", "|", "</p>") +
        tail

    def leaf(card: String): LayoutNode.Component = LayoutNode.Component(
      card,
      templateSlots = names.zip(slots.map(_.slot)).toMap,
      cell = Option.when(liveClass || liveStyle || atRender)(
        Cell(
          classes =
            if liveClass then ListMap("fh-on" -> isOn(Some("sensor.a")))
            else ListMap.empty,
          styles =
            if liveStyle then
              ListMap(
                "--fh-x" -> ValueExpr(
                  Value.Entity(Some("sensor.b"), Part.Attr("x"))
                )
              )
            else ListMap.empty,
          atRender =
            if atRender then
              ListMap("fh-chosen" -> is(Value.Var("window"), "7d"))
            else ListMap.empty
        )
      )
    )

    def dashboard: Dashboard = Dashboard(
      cards = Map(
        "box" -> TestCard(
          "<div>{{{children}}}</div>",
          regions = Map("children" -> Region())
        ),
        "one" -> TestCard(template("A")),
        "two" -> TestCard(template("B"))
      ),
      card =
        if member then
          LayoutNode.SetNode(
            candidates = List("sensor.a", "sensor.b"),
            members = List("sensor.a", "sensor.b").map { e =>
              e -> LayoutNode.SetMember(
                List(
                  LayoutNode.SetClause(Some(isOn(None)), leaf("one")),
                  LayoutNode.SetClause(node = leaf("two"))
                )
              )
            }.toMap
          )
        else
          LayoutNode.Component(
            "box",
            regions = LayoutNode.kids(leaf("one")),
            vars = declared("window" -> "24h"),
            id = Some("panel")
          )
    )

    def target(r: Renderer): NodeId =
      if member then
        NodeId.derived(r.members.memberIdOf(setId("c"), "sensor.a"))
      else
        LayoutNode.childId(
          "",
          NodeId.derived("panel"),
          LayoutNode.Step(LayoutNode.DefaultRegion, 0),
          leaf("one")
        )
  }

  case class World(
      a: String,
      b: String,
      ax: String,
      an: Option[Long],
      az: String,
      bx: String,
      window: Option[String],
      points: Int
  ) derives CanEqual {
    def shared: Shared = Shared.of(
      Map(
        "sensor.a" -> EntityState(
          "sensor.a",
          a,
          Map("x" -> Json.fromString(ax), "z" -> Json.fromString(az)) ++
            an.map(n => "n" -> Json.fromLong(n))
        ),
        "sensor.b" -> EntityState(
          "sensor.b",
          b,
          Map("x" -> Json.fromString(bx))
        )
      )
    )

    def fragments(r: Renderer): FetchSnapshot = FetchSnapshot.of(
      Map(read -> Staged(points.toLong, answers(points))),
      r.vars.env(
        window.fold(Map.empty[VarKey, String])(v =>
          Map(VarKey(NodeId.derived("panel"), "window") -> v)
        )
      )
    )

    def viewing(r: Renderer): Viewing = Viewing(Selections.none, fragments(r))
  }

  final class Sample(val shape: Shape, val w1: World, val w2: World) {
    lazy val errors: List[String] = shape.dashboard.validate()
    def valid: Boolean = errors.isEmpty
    private lazy val r = Renderer.create(shape.dashboard)
    private lazy val id = shape.target(r)

    def values(w: World): Option[Map[String, SlotValue]] =
      r.byteSlotValues(id, w.shared, w.fragments(r))
    def bytes(w: World): Option[String] =
      r.renderNodeById(id, w.shared, w.viewing(r))
    def walked(w: World): Option[Map[String, SlotValue]] =
      r.renderBodyTraced(w.shared, w.viewing(r)).own.get(id).map(_.values)

    lazy val v1: Option[Map[String, SlotValue]] = values(w1)
    lazy val v2: Option[Map[String, SlotValue]] = values(w2)

    override def toString: String = s"$shape\n  $w1\n  $w2"
  }

  private val genText: Gen[String] = Gen.frequency1(
    (
      3,
      Gen.string(
        Gen.element1('a', '1', ' ', '<', '&', '"', 'é'),
        Range.linear(0, 4)
      )
    ),
    (1, Gen.element1("", "on", "{{x}}", "-"))
  )
  private val genState = Gen.element1("on", "off", "1", "", "<&\">")
  private val genN = Gen.element1(None, Some(1L), Some(2L))
  private val genWindow =
    Gen.element1(None, Some("7d"), Some("24h"), Some("<b>"))
  private val genPoints = Gen.int(Range.linear(0, answers.size - 1))

  private val genWorld: Gen[World] = for {
    a <- genState
    b <- genState
    ax <- genText
    an <- genN
    az <- genText
    bx <- genText
    window <- genWindow
    points <- genPoints
  } yield World(a, b, ax, an, az, bx, window, points)

  /** One input moved, so most pairs keep their values. */
  private def genNeighbour(w: World): Gen[World] = Gen.frequency1(
    (1, genState.map(v => w.copy(a = v))),
    (1, genState.map(v => w.copy(b = v))),
    (1, genText.map(v => w.copy(ax = v))),
    (1, genN.map(v => w.copy(an = v))),
    (1, genText.map(v => w.copy(az = v))),
    (1, genText.map(v => w.copy(bx = v))),
    (1, genWindow.map(v => w.copy(window = v))),
    (1, genPoints.map(v => w.copy(points = v))),
    (1, genWorld)
  )

  private def genSource(member: Boolean): Gen[Source] = {
    val e = Gen.element1("sensor.a", "sensor.b")
    val any = List(
      e.map(Source.State(_)),
      for { x <- e; n <- Gen.element1("x", "n", "z") } yield Source.Attr(x, n),
      e.map(Source.Cel(_)),
      e.map(Source.IsOn(_)),
      Gen.constant(Source.Points),
      Gen.element1("k", "<b>").map(Source.Literal(_))
    )
    val vars =
      List(Gen.constant(Source.Window), Gen.constant(Source.WindowIs7d))
    val all = if member then any else any ++ vars
    Gen.choice1(all.head, all.tail*)
  }

  private def signalable(s: Source): Boolean = s match
    case Source.State(_) | Source.Attr(_, _) | Source.Cel(_) => true
    case _                                                   => false

  private def genSlot(member: Boolean): Gen[SlotSpec] = for {
    source <- genSource(member)
    place <- Gen.element1(Place.Text, Place.Attr, Place.Section)
    signal <- if signalable(source) then Gen.boolean else Gen.constant(false)
    gated <- source match
      case Source.Literal(_) => Gen.constant(false)
      case _                 => Gen.boolean
  } yield SlotSpec(source, place, signal, gated)

  private val genShape: Gen[Shape] = for {
    member <- Gen.boolean
    slots <- genSlot(member).list(Range.linear(1, 4))
    liveClass <- Gen.boolean
    liveStyle <- Gen.boolean
    atRender <- if member then Gen.constant(false) else Gen.boolean
  } yield Shape(slots, liveClass, liveStyle, atRender, member)

  val genSample: Gen[Sample] = for {
    shape <- genShape
    w1 <- genWorld
    w2 <- genNeighbour(w1)
  } yield Sample(shape, w1, w2)
}
