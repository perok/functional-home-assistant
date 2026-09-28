package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.model.{CardDef, Dashboard, NodeId, Region, Theme}
import fh.view.testkit.DashboardBuilders.{col, component, lit}

import java.nio.charset.StandardCharsets.UTF_8

/** That a page open really streams, and that streaming changes nothing. The
  * benchmarks cannot tell a sink that buffered the document and wrote it once,
  * which loses the point, peak memory. `Sink.Buffer` and `Sink.Streaming`
  * differ only in `digesting`, so "same bytes, same trace" is the invariant.
  */
class SinkStreamingSuite extends munit.FunSuite {

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

  // Several chunks wide, or "one write" and "smaller than a chunk" look alike.
  private val Leaves = 400

  // A real stylesheet: the shell writes it in one `append`, and the peak
  // measurement must not count that as a per-connection cost.
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
    // The two writers `Server.renderPage` puts in front of the response.
    val w = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(rec, UTF_8),
      Server.PageChunkBytes
    )
    val own = renderer.renderPageInto(
      Sink.streaming(w),
      noStates,
      fragments = QuerySnapshot.empty
    )
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
    // No hand-off exceeds a chunk, so the live writer chain is bounded by the
    // chunk size, not the document.
    assert(
      rec.writes.forall(_ <= Server.PageChunkBytes),
      s"largest write ${rec.writes.max} exceeds ${Server.PageChunkBytes}"
    )
  }

  /** Peak, which no benchmark reports (`-prof gc` measures churn).
    * `Sink.Streaming.digesting` hands each finished node run down as one
    * `write`, so an unbuffered destination sees each transient at full size.
    *
    * A classification, not a ratio (document/node is just the leaf count):
    * every write is either a node run, bounded by the largest node's rendering
    * as the renderer reports it, or the shell's one-shot `themeStyleTag`, a
    * shared `val` that never multiplies by open tabs. This is the sink's
    * high-water mark; the buffered path also holds the result `String` and its
    * encoded copy, so the gap is a floor.
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
    // A BufferedWriter would coalesce the runs being measured.
    val own = renderer.renderPageInto(
      Sink.streaming(direct),
      noStates,
      fragments = QuerySnapshot.empty
    )
    val document = Sink.buffer(renderer.pageBytesHint)
    val _ =
      renderer.renderPageInto(
        document,
        noStates,
        fragments = QuerySnapshot.empty
      )

    assert(own.nonEmpty, "the walk painted nothing")
    // From the renderer, so the bound moves with the fixture.
    val largestNode =
      own.keys.toList
        .flatMap(
          renderer.renderNodeById(_, noStates, fragments = QuerySnapshot.empty)
        )
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
    val bufferOwn =
      renderer.renderPageInto(buf, noStates, fragments = QuerySnapshot.empty)

    assertEquals(rec.text, buf.result)
    assertEquals(streamOwn, bufferOwn)
  }
}
