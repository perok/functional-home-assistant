package fh.view.runtime

import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** A repaint's fragment is folded as the walk writes it ([[Sink.Folding]]), a
  * per-node patch's by `Datastar.collapse` over the whole string; the two must
  * agree on every text, however the walk splits it into writes.
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class FoldingSinkPropertySuite extends HedgehogSuite {

  // Every blank `String.trim` drops, the `\s` set split in two by one that is
  // not in it, and text either side.
  private val genChar: Gen[Char] =
    Gen.element1(
      'a', '<', 'é', '€', ' ', ' ', '\n', '\r', '\t', '\u000B', '\f', '\u0001',
      '\u0000'
    )

  private enum Write {
    case Str(n: Int)
    case Chars(n: Int)
    case One
    case Seq(n: Int)
  }

  private val genWrite: Gen[Write] =
    Gen.choice1(
      Gen.int(Range.linear(0, 8)).map(Write.Str(_)),
      Gen.int(Range.linear(0, 8)).map(Write.Chars(_)),
      Gen.constant(Write.One),
      Gen.int(Range.linear(0, 8)).map(Write.Seq(_))
    )

  /** `text` through `Folding` in the writes `plan` names, the rest in one. */
  private def folded(text: String, plan: List[Write]): String = {
    val out = new java.io.StringWriter
    val sink = new Sink.Folding(out)
    var at = 0
    def take(n: Int): String = {
      val s = text.substring(at, math.min(text.length, at + n))
      at += s.length
      s
    }
    plan.foreach {
      case Write.Str(n) =>
        // An offset into a wider string, as mustache writes its chunks.
        val s = take(n)
        sink.write("xx" + s + "yy", 2, s.length)
      case Write.Chars(n) =>
        val s = take(n)
        sink.write(("#" + s).toCharArray, 1, s.length)
      case Write.One if at < text.length => sink.write(take(1).charAt(0).toInt)
      case Write.One                     => ()
      case Write.Seq(n)                  =>
        val _ = sink.append(new java.lang.StringBuilder(take(n)))
    }
    sink.write(text.substring(at))
    sink.finish()
    out.toString
  }

  property("folding as it is written is collapse") {
    for {
      text <- Gen.string(genChar, Range.linear(0, 40)).forAll
      plan <- Gen.list(genWrite, Range.linear(0, 10)).forAll
    } yield folded(text, plan) ==== Datastar.collapse(text)
  }

  // Past one chunk too: the frame is the encoder's flushes.
  property("a written morph is the frame patch builds") {
    for {
      text <- Gen.string(genChar, Range.linear(0, 40)).forAll
      times <- Gen.element1(1, 2000).forAll
    } yield {
      val html = text * times
      val (written, ()) = Datastar.patchWritten("#root")(_.write(html))
      written ==== Datastar.patch(html, selector = Some("#root"))
    }
  }
}
