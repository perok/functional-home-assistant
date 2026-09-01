package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, NodeId, Region}
import fh.view.testkit.DashboardBuilders.{col, component, lit}
import fh.view.testkit.TestIds.given

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

  private val renderer = Renderer.create(
    Dashboard(
      cards,
      col(
        (1 to Leaves).map(i =>
          component("leaf", "v" -> lit(s"value-$i-" + "x" * 40))
        )*
      )
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

  test("streaming and buffering produce the same document and the same trace") {
    val (rec, streamOwn) = streamed()

    val buf = Sink.buffer(renderer.pageBytesHint)
    val bufferOwn = renderer.renderPageInto(buf, noStates)

    assertEquals(rec.text, buf.result)
    assertEquals(streamOwn, bufferOwn)
  }
}
