package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, NodeId, Region, Theme}
import fh.view.testkit.DashboardBuilders.{col, component, lit}

import java.nio.charset.StandardCharsets.UTF_8

/** That a page open actually STREAMS, and that streaming it changes nothing.
  *
  * Both halves were assumed rather than checked. The benchmarks price the
  * streamed walk but cannot say the bytes left incrementally — a sink that
  * quietly buffered the document and wrote it once at the end would score the
  * same and lose the entire point, which is PEAK. And `Sink.Buffer` and
  * `Sink.Streaming` are two implementations of one contract with only
  * `digesting` differing, so "same bytes, same trace" is the invariant the
  * design rests on; nothing compared them at page scale.
  */
class SinkStreamingSuite extends munit.FunSuite {

  /** Every downstream write, in order — what the response body would see. */
  private final class Recorder extends java.io.OutputStream {
    private val bytes = new java.io.ByteArrayOutputStream()
    val writes = scala.collection.mutable.ArrayBuffer.empty[Int]

    override def write(b: Int): Unit = {
      bytes.write(b)
      val _ = writes += 1
    }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      bytes.write(b, off, len)
      val _ = writes += len
    }
    def text: String = new String(bytes.toByteArray, UTF_8)
  }

  private val cards = Map(
    "leaf" -> CardDef("""<div class="leaf">{{v}}</div>""", slots = List("v")),
    "col" -> CardDef(
      """<div>{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    )
  )

  // Enough leaves to be several chunks wide, or "it arrived in one write" and
  // "it is smaller than one chunk" would be indistinguishable.
  private val Leaves = 400

  // A theme with a REAL stylesheet, because the shell writes it in a single
  // `append` and a fixture without one cannot catch the peak measurement
  // counting that write as if it were a per-connection cost.
  private val theme = Theme(styles = ".x{color:red}\n" * 200)

  private val renderer = Renderer.create(
    Dashboard(
      cards,
      col(
        (1 to Leaves).map(i =>
          component("leaf", "v" -> lit(s"value-$i-" + "x" * 40))
        )*
      ),
      theme = theme
    )
  )

  private val noStates = Map.empty[String, EntityState]

  private def streamed(): (Recorder, Map[NodeId, Painted]) = {
    val rec = new Recorder
    // The shipped chain: `Server.renderPage` puts exactly these two writers in
    // front of the response's OutputStream.
    val w = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(rec, UTF_8),
      Server.PageChunkBytes
    )
    val own = renderer.renderPageInto(Sink.streaming(w), noStates)
    w.flush()
    (rec, own)
  }

  test("the document leaves in chunks, never as one write") {
    val (rec, own) = streamed()

    assert(own.nonEmpty, "the walk painted nothing")
    assert(
      rec.text.length > 4 * Server.PageChunkBytes,
      s"fixture too small to prove anything: ${rec.text.length} bytes"
    )
    assert(
      rec.writes.length > 1,
      s"the whole document arrived in ${rec.writes.length} write(s) — " +
        "something is buffering the page instead of streaming it"
    )
    // The peak claim in one assertion: no single hand-off carries more than a
    // chunk, so what the writer chain holds live is bounded by the chunk size
    // rather than by the document.
    assert(
      rec.writes.forall(_ <= Server.PageChunkBytes),
      s"largest write ${rec.writes.max} exceeds ${Server.PageChunkBytes}"
    )
  }

  /** PEAK, which is the reason the streaming path exists and which no benchmark
    * reports — `-prof gc` measures churn, and the two are different targets.
    *
    * Measured with no production change, because the sink already tells us:
    * `Sink.Streaming.digesting` renders a node into a scratch buffer and hands
    * the finished run down as ONE `write`, so an unbuffered destination sees
    * each transient at its full size. Against `Sink.Buffer`, whose peak is the
    * finished document by construction.
    *
    * '''The assertion is a classification, not a ratio.''' A ratio would be
    * fixture arithmetic — document/node is just the leaf count, so adding
    * leaves would "improve" it. What is actually claimed is that every write is
    * one of two things, and neither grows with the document:
    *
    *   - a node run, bounded by the largest single node's rendering, which is
    *     asked of the renderer rather than assumed; or
    *   - the shell's one-shot `themeStyleTag`, which is a shared `val` written
    *     straight through. It is a big WRITE and not a peak at all: one copy
    *     exists for the life of the renderer and every connection reuses it, so
    *     it never multiplies by open tabs. Counting it as peak was the flaw
    *     this test was rewritten to remove, and the fixture now carries a real
    *     stylesheet so the case is exercised rather than assumed away.
    *
    * This is the sink's high-water mark, not the JVM's. The buffered path also
    * holds the result `String` and its encoded copy, so the gap is a floor.
    */
  test("every streamed write is one node or the shared shell, never the page") {
    val runs = scala.collection.mutable.ArrayBuffer.empty[Int]
    val direct = new java.io.Writer {
      override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
        val _ = runs += len
      }
      override def write(str: String): Unit = { val _ = runs += str.length }
      override def flush(): Unit = ()
      override def close(): Unit = ()
    }
    // No BufferedWriter here on purpose: it would coalesce the runs and hide
    // the very quantity being measured.
    val own = renderer.renderPageInto(Sink.streaming(direct), noStates)
    val document = Sink.buffer(renderer.pageBytesHint)
    val _ = renderer.renderPageInto(document, noStates)

    assert(own.nonEmpty, "the walk painted nothing")
    // What a node's rendering costs, from the renderer — so the bound moves
    // with the fixture instead of being a number copied out of a past run.
    val largestNode =
      own.keys.toList
        .flatMap(renderer.renderNodeById(_, noStates))
        .map(_.length)
        .max
    val shell = renderer.themeStyleTag.length
    assert(
      shell > largestNode,
      s"fixture's stylesheet ($shell B) is too small to test the exclusion"
    )

    val unexplained = runs.filter(r => r > largestNode && r != shell)
    assert(
      unexplained.isEmpty,
      s"writes of ${unexplained.distinct.sorted} B are neither a node " +
        s"(<= $largestNode) nor the shared stylesheet ($shell) — something " +
        "is accumulating across nodes"
    )
    println(
      s"[peak] largest node $largestNode B, shared shell $shell B, " +
        s"document ${document.result.length} B"
    )
  }

  test("streaming and buffering produce the same document and the same trace") {
    val (rec, streamOwn) = streamed()

    val buf = Sink.buffer(renderer.pageBytesHint)
    val bufferOwn = renderer.renderPageInto(buf, noStates)

    assertEquals(rec.text, buf.result)
    assertEquals(streamOwn, bufferOwn)
  }
}
