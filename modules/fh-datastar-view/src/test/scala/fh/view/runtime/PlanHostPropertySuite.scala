package fh.view.runtime

import fh.view.model.Ix
import hedgehog.*
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite

// This suite's own `assertEquals` returns a Result a plain `test` ignores.
import _root_.munit.Assertions.assertEquals as same

/** [[Patches.planHost]]: the plan, run on a page, leaves the page showing what
  * was decided, and moves no more than it must.
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class PlanHostPropertySuite extends HedgehogSuite {

  private def name(k: Ix): String = ('a' + k.toInt).toChar.toString

  private def keys(letters: String): IArray[Ix] =
    IArray.from(letters.toList.filter(_ != ' ').map(c => Ix(c - 'a')))

  private def show(steps: List[HostStep]): List[String] = steps.map {
    case HostStep.Fill(ks) =>
      s"Fill(${ks.toList.map(name).mkString(" ")})"
    case HostStep.Leave(k)         => s"Leave(${name(k)})"
    case HostStep.Place(k, before) =>
      s"Place(${name(k)}, ${before.fold("end")(name)})"
  }

  private def plan(
      held: Option[String],
      decided: String,
      again: String = ""
  ): List[String] =
    show(
      Patches.planHost(
        held.map(keys),
        keys(decided),
        k => again.contains(name(k))
      )
    )

  test("a page that already shows what was decided plans nothing") {
    same(plan(Some("a b c"), "a b c"), Nil)
    same(plan(Some(""), ""), Nil)
  }

  test("a reorder moves the fewest keys, leaving first") {
    same(plan(Some("a b c"), "c a b"), List("Leave(c)", "Place(c, a)"))
  }

  test("a key sent again leaves and is placed whole") {
    same(
      plan(Some("a b c"), "a b c", again = "b"),
      List("Leave(b)", "Place(b, c)")
    )
  }

  test("a page nobody knows is filled, even with nothing") {
    same(plan(None, "a b"), List("Fill(a b)"))
    same(plan(None, ""), List("Fill()"))
  }

  test("keys gone leave one by one") {
    same(plan(Some("a b"), ""), List("Leave(a)", "Leave(b)"))
  }

  test("nothing kept is a fill") {
    same(plan(Some("a"), "b"), List("Fill(b)"))
    same(plan(Some("a"), "a", again = "a"), List("Fill(a)"))
  }

  test("a key arriving between two held ones is placed before the later") {
    same(plan(Some("a b"), "a c b"), List("Place(c, b)"))
    same(plan(Some("a b"), "a b c"), List("Place(c, end)"))
  }

  // Keys are 0 until m, so held and decided overlap often.
  private final case class Case(
      held: Option[List[Int]],
      decided: List[Int],
      again: Set[Int],
      // What the client really shows when `held` is unknown.
      page: List[Int]
  ) {
    def steps: List[HostStep] = Patches.planHost(
      held.map(ks => IArray.from(ks.map(Ix(_)))),
      IArray.from(decided.map(Ix(_))),
      k => again(k.toInt)
    )

    def start: List[Int] = held.getOrElse(page)

    def kept: List[Int] =
      held.fold(List.empty[Int])(h =>
        decided.filter(k => h.contains(k) && !again(k))
      )

    def keptPositions: List[Int] =
      held.fold(List.empty[Int])(h => kept.map(k => h.indexOf(k)))
  }

  private def shuffled(xs: List[Int]): Gen[List[Int]] =
    Gen
      .int(Range.constant(0, 1000))
      .list(Range.singleton(xs.size))
      .map(ks => xs.zip(ks).sortBy(_._2).map(_._1))

  private def distinct(m: Int, in: Int = 3, out: Int = 1): Gen[List[Int]] = {
    val pool = List.range(0, m)
    for {
      picks <- Gen
        .frequency1(in -> Gen.constant(true), out -> Gen.constant(false))
        .list(Range.singleton(m))
      out <- shuffled(pool.zip(picks).collect { case (k, true) => k })
    } yield out
  }

  private val genCase: Gen[Case] = for {
    m <- Gen.int(Range.linear(1, 9))
    held <- Gen.frequency1[Option[List[Int]]](
      1 -> Gen.constant(None),
      8 -> distinct(m).map(ks => Option(ks))
    )
    decided <- Gen.frequency1[List[Int]](
      4 -> distinct(m),
      1 -> Gen.constant(held.getOrElse(Nil)),
      1 -> shuffled(held.getOrElse(Nil))
    )
    again <- Gen.frequency1[Set[Int]](
      3 -> Gen.constant(Set.empty[Int]),
      2 -> distinct(m, in = 1, out = 3).map(_.toSet)
    )
    page <- distinct(m)
  } yield Case(held, decided, again, page)

  private def isFill(step: HostStep): Boolean = step match
    case HostStep.Fill(_) => true
    case _                => false

  /** The page a client is left with, or the first step it could not take. */
  private def run(
      start: List[Int],
      steps: List[HostStep]
  ): Either[String, List[Int]] =
    steps.foldLeft[Either[String, List[Int]]](Right(start)) { (acc, step) =>
      acc.flatMap { page =>
        step match
          case HostStep.Fill(ks) => Right(ks.toList.map(_.toInt))
          case HostStep.Leave(k) =>
            if page.contains(k.toInt) then Right(page.filter(_ != k.toInt))
            else Left(s"Leave(${k.toInt}) of a key not on $page")
          case HostStep.Place(k, _) if page.contains(k.toInt) =>
            Left(s"Place(${k.toInt}) of a key already on $page")
          case HostStep.Place(k, None)    => Right(page :+ k.toInt)
          case HostStep.Place(k, Some(b)) =>
            if page.contains(b.toInt) then {
              val (front, back) = page.span(_ != b.toInt)
              Right(front ++ (k.toInt :: back))
            } else Left(s"Place(${k.toInt}) before ${b.toInt}, not on $page")
      }
    }

  // Brute force over subsets, so it shares nothing with the planner's search.
  private def longestIncreasing(xs: List[Int]): Int =
    (0 until (1 << xs.size)).iterator
      .map(mask =>
        xs.zipWithIndex.collect { case (x, i) if (mask >> i & 1) == 1 => x }
      )
      .filter(sub => sub.zip(sub.drop(1)).forall((a, b) => a < b))
      .map(_.size)
      .max

  private def check(label: String)(f: Case => Result): Unit =
    property(label, _.copy(testLimit = SuccessCount(300))) {
      genCase.forAll.map(f)
    }

  check("the plan, run on the page, shows what was decided") { c =>
    run(c.start, c.steps) ==== Right(c.decided)
  }

  check("a page nobody knows gets exactly one fill of what was decided") { c =>
    if c.held.isDefined then Result.success
    else
      c.steps match
        case List(HostStep.Fill(ks)) => ks.toList.map(_.toInt) ==== c.decided
        case other => Result.failure.log(s"not one Fill: ${show(other)}")
  }

  check("a fill is planned only when nothing could be kept") { c =>
    c.steps.exists(isFill) ====
      (c.held.isEmpty || (c.kept.isEmpty && c.decided.nonEmpty))
  }

  check("the keys moved are the kept ones less a longest increasing run") { c =>
    if c.steps.exists(isFill) then Result.success
    else {
      val moved = c.steps.count {
        case HostStep.Leave(k) => c.kept.contains(k.toInt)
        case _                 => false
      }
      moved ==== c.kept.size - longestIncreasing(c.keptPositions)
    }
  }

  check("a key sent again is never kept") { c =>
    val resent =
      c.decided.filter(k => c.again(k) && c.held.exists(_.contains(k)))
    val left = c.steps.collect { case HostStep.Leave(k) => k.toInt }
    val placed = c.steps.collect { case HostStep.Place(k, _) => k.toInt }
    if c.steps.exists(isFill) then Result.success
    else
      Result
        .assert(resent.forall(k => left.contains(k) && placed.contains(k)))
        .log(s"again: $resent, left: $left, placed: $placed")
  }

  property(
    "a page already showing what was decided plans nothing",
    _.copy(testLimit = SuccessCount(300))
  ) {
    (for {
      m <- Gen.int(Range.linear(0, 9))
      shown <- distinct(m)
      again <- distinct(m, in = 1, out = 1)
    } yield Case(
      Some(shown),
      shown,
      again.filterNot(shown.contains(_)).toSet,
      shown
    )).forAll
      .map(c => show(c.steps) ==== Nil)
  }
}
