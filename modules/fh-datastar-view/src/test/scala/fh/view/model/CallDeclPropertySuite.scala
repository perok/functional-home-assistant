package fh.view.model

import fh.view.history.Windows
import fh.view.testkit.{TestCard, asService}
import hedgehog.*
import hedgehog.munit.HedgehogSuite

import scala.collection.immutable.ListMap

/** A declared call's id, and which of its fields a node sees: exactly those of
  * what it does that read live state, whatever the node paints, never one that
  * reads a fetch or a posted value. Run through `validated`, so the node's
  * subject, its child and the build's own slots are all on the path.
  */
class CallDeclPropertySuite extends HedgehogSuite {
  import CallDeclPropertySuite.*

  property("equal declarations share an id, and different ones never do") {
    for {
      a <- genDecl.forAll
      b <- genDecl.forAll
    } yield Result.assert((a == b) == (a.id == b.id))
  }

  property("what a call excludes is not what it targets, for its id") {
    for d <- genDecl.forAll
    yield {
      val moved = d.copy(target = d.exclude, exclude = d.target)
      Result.assert((moved == d) == (moved.id == d.id))
    }
  }

  property("a declaration's data keys in another order keep its id") {
    for d <- genDecl.forAll
    yield d.copy(data = ListMap.from(d.data.toList.reverse)).id ==== d.id
  }

  property(
    "a node sees every field reading live state, never a fetch's or a press's"
  ) {
    for n <- genNode.forAll
    yield {
      val expected = n.doing.collect {
        case (field, r) if !r.fetchy && !r.posted && r.reads.nonEmpty => field
      }.toSet
      n.dashboard.validated() match
        case Left(errors) => Result.failure.log(errors.mkString("\n"))
        case Right(v)     =>
          v.ready.card match
            case c: Ready.Component =>
              c.calls("tap").seen.map(_._1).toSet ==== expected
            case _ => Result.failure.log("the root is not a component")
    }
  }
}

/** What `validate` refuses of a call: what the press could not run once for
  * every viewer, and a posted value outside one.
  */
class CallDeclValidateSuite extends _root_.munit.FunSuite {

  private def refusals(
      decl: CallDecl,
      slots: Map[String, TemplateSlot] = Map.empty
  ): List[String] =
    Dashboard(
      cards = Map(
        "t" -> TestCard(
          slots.keys.map(s => s"{{$s}}").mkString +
            """<b data-call="{{{tap.callId}}}"></b>"""
        )
      ),
      card = LayoutNode.Component(
        "t",
        templateSlots = slots,
        calls = Map("tap" -> decl)
      )
    ).validate()

  private def on(
      service: ValueExpr,
      data: (String, ValueExpr)*
  ): CallDecl.Service =
    CallDecl.Service(
      service,
      ListMap(IdKind.Entity -> List(ValueExpr.literal("light.a"))),
      ListMap.from(data)
    )

  test("a call is refused what the press could not run once for everyone") {
    val lit = ValueExpr.literal("light/toggle")
    val cases = List(
      on(
        ValueExpr(Value.Entity(None))
      ) -> "service reads an entity it does not name",
      on(ValueExpr(Value.Var("v"))) -> "reads the node variable 'v'",
      on(lit, "entity_id" -> lit) -> "named like a target key",
      on(lit, "exclude" -> lit) -> "named like a target key",
      on(ValueExpr(Value.Entity(Some("light.a"), Part.Whole))) ->
        "service gives a whole entity"
    )
    val missed =
      cases.filterNot((d, why) => refusals(d).exists(_.contains(why)))
    if (missed.nonEmpty) fail(missed.map((d, _) => refusals(d)).mkString("\n"))
    val fine = refusals(on(lit, "transition" -> lit))
    if (fine.nonEmpty) fail(fine.mkString("\n"))
  }

  test("a set of areas goes under area_id, and only there") {
    val discoOn = ValueExpr(
      Value.Entity(Some("light.disco")),
      List(Step.Compare(Op.Eq, Value.Literal(Lit.Text("on"))))
    )
    val rooms = ValueExpr(
      Value.Present(List("sofie"), Map("sofie" -> discoOn), IdKind.Area)
    )
    def under(k: IdKind) =
      on(ValueExpr.literal("light/turn_off"))
        .copy(exclude = ListMap(k -> List(rooms)))
    assertEquals(refusals(under(IdKind.Area)), Nil)
    val wrong = refusals(under(IdKind.Entity))
    assert(
      wrong.exists(_.contains("exclude.entity_id holds a set of area ids")),
      clue = wrong
    )
  }

  test("a posted value is read only by a call") {
    val posted = ValueExpr(Value.Posted("v", ValueType.Int))
    val asked = ValueExpr(Value.Posted("v", ValueType.Bool))
    val call = on(ValueExpr.literal("light/turn_on"), "brightness" -> posted)
    def tapped(slots: Map[String, TemplateSlot]) = LayoutNode.Component(
      "t",
      templateSlots = slots,
      calls = Map("tap" -> call)
    )
    def built(card: LayoutNode, slots: Map[String, TemplateSlot] = Map.empty) =
      Dashboard(
        cards = Map(
          "t" -> TestCard(
            slots.keys.map(s => s"{{$s}}").mkString +
              """<b data-call="{{{tap.callId}}}"></b>"""
          )
        ),
        card = card
      ).validated().left.getOrElse(Nil)
    val refused = "the posted 'v' is read outside a call"
    assertEquals(built(tapped(Map.empty)), Nil)
    val inSlots = List(
      "a slot" -> TemplateSlot(posted),
      "a slot's valueWhile" ->
        TemplateSlot(ValueExpr.literal("x"), valueWhile = Some(asked)),
      "a set guard" -> TemplateSlot(
        ValueExpr(
          Value.Present(List("light.a"), Map("light.a" -> asked)),
          List(Step.Count)
        )
      )
    )
    inSlots.foreach { (where, slot) =>
      val slots = Map("shown" -> slot)
      val errors = built(tapped(slots), slots)
      assert(errors.exists(_.contains(refused)), clue = (where, errors))
    }
    val clause = LayoutNode.SetNode(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(LayoutNode.SetClause(Some(asked), tapped(Map.empty)))
        )
      )
    )
    val errors = built(clause)
    assert(errors.exists(_.contains(refused)), clue = ("a clause", errors))
  }
}

object CallDeclPropertySuite {

  /** An expression and, by construction, what it reads. */
  final case class Read(
      expr: ValueExpr,
      reads: Set[(String, Part)],
      fetchy: Boolean = false,
      posted: Boolean = false
  )

  private def state(e: String) =
    Read(ValueExpr(Value.Entity(Some(e))), Set(e -> Part.State))

  private val brightness = Part.Attr("brightness")

  private val painting: List[Read] = List(
    state("light.a"),
    state("light.b"),
    Read(
      ValueExpr(Value.Entity(Some("light.a"), brightness)),
      Set("light.a" -> brightness)
    ),
    Read(
      ValueExpr(
        Value.Entity(Some("light.a")),
        List(
          Step.Match(
            Map("on" -> Lit.Text("light/turn_off")),
            Value.Literal(Lit.Text("light/turn_on"))
          )
        )
      ),
      Set("light.a" -> Part.State)
    )
  )

  /** Reads the state the first of [[painting]] does, and a fetch beside it. */
  private val fetchy = Read(
    ValueExpr(
      Value.Cel(
        "size(past) > 0 ? s : s",
        Map(
          "s" -> state("light.a").expr,
          "past" -> ValueExpr(
            Value.Fetch(FetchTemplate("light.a", Choice.Fixed(Windows.day)))
          )
        )
      )
    ),
    Set("light.a" -> Part.State),
    fetchy = true
  )

  // Kept under a live attribute, which it reads and still cannot be seen by.
  private val posted = Read(
    ValueExpr(
      Value.Posted("p", ValueType.Int),
      List(
        Step.Check(
          List(Step.Compare(Op.Lte, Value.Entity(Some("light.a"), brightness)))
        )
      )
    ),
    Set("light.a" -> brightness),
    posted = true
  )

  private val calling: List[Read] =
    Read(ValueExpr.literal("light/toggle"), Set.empty) :: fetchy :: posted ::
      painting

  enum Kind derives CanEqual:
    case Bytes, Text, Handler

  final case class Node(
      own: List[(Read, Kind)],
      child: List[(Read, Kind)],
      doing: List[(CallField, Read)]
  ):
    private def decl = CallDecl.Service(
      doing.collectFirst { case (CallField.Service, r) => r.expr }.get,
      ListMap(IdKind.Entity -> List(ValueExpr.literal("light.a"))),
      ListMap.from(doing.collect { case (CallField.Data(k), r) => k -> r.expr })
    )

    private def slots(prefix: String, rs: List[(Read, Kind)]) =
      rs.zipWithIndex.map { case ((r, kind), i) =>
        s"$prefix$i" -> TemplateSlot(
          r.expr,
          signal = kind match
            case Kind.Bytes   => None
            case Kind.Text    => Some(SignalBind.Text)
            case Kind.Handler => Some(SignalBind.Handler)
        )
      }.toMap

    private def placed(prefix: String, rs: List[(Read, Kind)]) =
      rs.zipWithIndex.map { case ((_, kind), i) =>
        val s = s"$prefix$i"
        kind match
          case Kind.Bytes   => s"<i>{{$s}}</i>"
          case Kind.Text    => s"<i {{{$s.bind}}}>{{$s}}</i>"
          case Kind.Handler => s"""<i x="{{{$s.jsValue}}}"></i>"""
      }.mkString

    def dashboard: Dashboard = Dashboard(
      cards = Map(
        "parent" -> TestCard(
          placed("s", own) +
            """<b data-call="{{{tap.callId}}}"></b>{{{children}}}""",
          regions = Map(LayoutNode.DefaultRegion -> Region())
        ),
        "child" -> TestCard(placed("c", child))
      ),
      card = LayoutNode.Component(
        "parent",
        templateSlots = slots("s", own),
        regions = Map(
          LayoutNode.DefaultRegion -> List(
            LayoutNode.Component("child", templateSlots = slots("c", child))
          )
        ),
        calls = Map("tap" -> decl)
      )
    )

  private def genSlots(kinds: List[Kind], max: Int): Gen[List[(Read, Kind)]] =
    (for {
      a <- Gen.elementUnsafe(painting); b <- Gen.elementUnsafe(kinds)
    } yield (a, b))
      .list(Range.linear(0, max))

  val genDecl: Gen[CallDecl.Service] =
    genNode.map(_.dashboard.card).map {
      case c: LayoutNode.Component => c.calls("tap").asService
      case _                       => sys.error("a component")
    }

  // A node with regions is structure, which may not hold a live slot as bytes.
  lazy val genNode: Gen[Node] =
    for {
      own <- genSlots(List(Kind.Text, Kind.Handler), 3)
      child <- genSlots(List(Kind.Bytes, Kind.Text, Kind.Handler), 2)
      service <- Gen.elementUnsafe(calling)
      data <- (for {
        k <- Gen.elementUnsafe(List("x", "y", "z"))
        r <- Gen.elementUnsafe(calling)
      } yield (k, r)).list(Range.linear(0, 3))
    } yield Node(
      own,
      child,
      (CallField.Service -> service) ::
        data.toMap.toList.sortBy(_._1).map((k, r) => CallField.Data(k) -> r)
    )
}
