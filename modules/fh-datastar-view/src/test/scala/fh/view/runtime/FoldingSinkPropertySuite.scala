package fh.view.runtime

import hedgehog.*
import hedgehog.munit.HedgehogSuite

/** A repaint's fragment is folded as the walk writes it ([[Sink.Folding]]), a
  * per-node patch's by `Datastar.collapse` over the whole string; the two must
  * agree on every text, however the walk splits it into writes. And every frame
  * [[Datastar]] writes must be the bytes http4s's `ServerSentEvent` renders.
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

  // Surrogates paired and lone, for the frame's own UTF-8 encoder.
  private val genFrameChar: Gen[Char] =
    Gen.choice1(genChar, Gen.element1('\uD83D', '\uDE00', '߿', '￿'))

  // Past one chunk too: the frame is its buffers as they filled.
  property("every frame is the event http4s renders") {
    for {
      text <- Gen.string(genFrameChar, Range.linear(0, 40)).forAll
      more <- Gen.string(genFrameChar, Range.linear(0, 40)).forAll
      times <- Gen.element1(1, 2000).forAll
    } yield {
      val html = text * times
      val (written, ()) = Datastar.patchWritten("#root")(_.write(html))
      Result.all(
        List(
          written ==== rendered(
            Elements,
            "selector #root",
            "elements " + Datastar.collapse(html)
          ),
          Datastar.patch(html, PatchMode.Inner, Some("#h")) ==== rendered(
            Elements,
            "selector #h",
            "mode inner",
            "elements " + Datastar.collapse(html)
          ),
          Datastar.patchElements(List(html, more)) ==== rendered(
            Elements,
            "elements " + Datastar.collapse(html + more)
          ),
          Datastar.remove(s"#$text") ==== rendered(
            Elements,
            "mode remove",
            s"selector #$text"
          ),
          Datastar.patchSignals(html) ==== rendered(
            "datastar-patch-signals",
            s"signals $html"
          )
        )
      )
    }
  }

  private val Elements = "datastar-patch-elements"

  private def rendered(eventType: String, lines: String*): SseFrame =
    SseFrame(
      fs2.Chunk.array(
        org.http4s
          .ServerSentEvent(
            data = Some(lines.mkString("\ndata: ")),
            eventType = Some(eventType)
          )
          .renderString
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    )
}
