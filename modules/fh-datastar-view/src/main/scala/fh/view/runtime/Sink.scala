package fh.view.runtime

/** Where a walk puts its bytes.
  *
  * The document walk and the patch walk are the same walk with different
  * destinations: a patch BUILDS a String, because those bytes are its cache
  * entry and its morph payload; a page WRITES its bytes at the client and keeps
  * none of them. Only one operation actually differs between the two, and it is
  * [[digesting]].
  *
  * A `Writer`, because mustache executes against one: the sink IS the writer,
  * so no adapter object is allocated at every `Templates.run`.
  */
private[runtime] sealed abstract class Sink extends java.io.Writer {

  /** Run `f` and fingerprint exactly the bytes it wrote, returning them too
    * when `keepBytes` — which only a patch walk's ROOT asks for.
    *
    * This is the whole reason the sink is a type rather than a `Writer`. A
    * node's own digest is a digest of a CONTIGUOUS RUN of the walk's output,
    * and how you get one depends on whether that output is still reachable: a
    * buffer still has the run and can bound it by two offsets, while a stream
    * has already let those bytes go and has to catch the run on the way past.
    *
    * Both then COPY the run once — `Digest.ofRange` cuts a String out of the
    * buffer for the same reason `Streaming` builds one, and its own doc records
    * the measurement that says to. So the difference between the two is the
    * scratch buffer, not an extra copy.
    */
  def digesting(keepBytes: Boolean)(f: Sink => Unit): (Digest, String | Null)

  override def flush(): Unit = ()
  override def close(): Unit = ()
}

private[runtime] object Sink {

  /** A sink over a `StringBuilder` — no lock, no second buffer, and no copy on
    * the way out.
    */
  final class Buffer(private val sb: java.lang.StringBuilder) extends Sink {
    override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
      val _ = sb.append(cbuf, off, len)
    }
    override def write(str: String): Unit = { val _ = sb.append(str) }
    // The 3-arg String form is Writer's other default that allocates a copy
    // per call; appending the slice directly keeps bulk writers (the escaping
    // runs, template literals) allocation-free.
    override def write(str: String, off: Int, len: Int): Unit = {
      val _ = sb.append(str, off, off + len)
    }
    override def write(c: Int): Unit = { val _ = sb.append(c.toChar) }
    override def append(csq: CharSequence): this.type = {
      val _ = sb.append(csq); this
    }
    override def append(c: Char): this.type = { val _ = sb.append(c); this }

    def digesting(
        keepBytes: Boolean
    )(f: Sink => Unit): (Digest, String | Null) = {
      val start = sb.length
      f(this)
      if (keepBytes) {
        val bytes = sb.substring(start, sb.length)
        (Digest.of(bytes), bytes)
      } else (Digest.ofRange(sb, start, sb.length), null)
    }

    def result: String = sb.toString

    private[runtime] def reset(): Unit = sb.setLength(0)

    private[runtime] def capacity: Int = sb.capacity

    private[runtime] def trim(): Unit = sb.trimToSize()
  }

  /** A sink that hands its bytes straight to `w` — the page's destination,
    * where `w` encodes them into the response body as they are written.
    *
    * `digesting` is where a page pays for streaming, and it pays ONE NODE at a
    * time: the run is caught in a buffer, fingerprinted, and written through.
    * That bounded buffer is the whole of what streaming trades away — the peak
    * becomes the largest single own-rendering subtree instead of the document
    * and the copies made of it.
    *
    * NOT an incremental digest over the encoded bytes, and the reason is the
    * BUFFERING rather than the encode. `Server.renderPage` owns the whole chain
    * and already puts an `OutputStreamWriter` in it, so a `DigestOutputStream`
    * under that writer would fingerprint bytes being encoded anyway — and runs
    * never nest (`hasOwnRendering` ⟺ not structure ⟺ no regions), so one
    * `MessageDigest` would do. What rules it out is that bounding a run in the
    * BYTE stream needs both writers flushed at each boundary: a flush per leaf,
    * against a `BufferedWriter` worth 729 kB of churn.
    *
    * And there is nothing here to win back. `RenderBench.pageWalkStreamPlain`
    * is this method on every leaf, against `page` which is the buffer's version
    * of the same tree: streaming is 219 kB CHEAPER, the same ratio it saves
    * where `digesting` is never called. The scratch buffer is not a price.
    */
  final class Streaming(w: java.io.Writer) extends Sink {
    override def write(cbuf: Array[Char], off: Int, len: Int): Unit =
      w.write(cbuf, off, len)
    override def write(str: String): Unit = w.write(str)
    override def write(str: String, off: Int, len: Int): Unit =
      w.write(str, off, len)
    override def write(c: Int): Unit = w.write(c)
    override def append(csq: CharSequence): this.type = {
      val _ = w.append(csq); this
    }
    override def append(c: Char): this.type = { val _ = w.append(c); this }
    override def flush(): Unit = w.flush()

    def digesting(
        keepBytes: Boolean
    )(f: Sink => Unit): (Digest, String | Null) = {
      val bytes = Sink.scratched { buf => f(buf); buf.result }
      w.write(bytes)
      (Digest.of(bytes), if (keepBytes) bytes else null)
    }
  }

  def buffer(sizeHint: Int): Sink.Buffer = new Sink.Buffer(
    new java.lang.StringBuilder(sizeHint)
  )

  /** Run `f` against a buffer BORROWED for the call rather than allocated for
    * it, and only for a run whose bytes `f` copies out before returning.
    *
    * A node's patch form is rendered into a throwaway buffer purely to
    * fingerprint it, once per own-rendering node — 2 kB of `char[]` per node
    * (plus its `toString`) for bytes nobody keeps, which async-profiler put at
    * a quarter of a page open. One buffer per THREAD serves the whole walk.
    *
    * Per thread and not per renderer because sessions render concurrently and
    * `renderPageInto` is otherwise pure — the same reason `Digest.digester` is
    * a `ThreadLocal`.
    *
    * '''Nested borrows get their own buffer.''' No current path nests —
    * instrumenting the borrow across the whole suite counts zero — so the guard
    * is not carrying today's walk. It is what keeps "never render into a
    * scratch while holding one" from being an unwritten precondition: sharing
    * one builder would splice two nodes' bytes together, and the result is
    * still well-formed HTML, so nothing downstream would notice.
    */
  def scratched[A](f: Sink.Buffer => A): A = scratch.get().nn.use(f)

  private final class Scratch {
    private val buf = Sink.buffer(Renderer.NodeBytesHint)
    private var busy = false

    def use[A](f: Sink.Buffer => A): A =
      if (busy) f(Sink.buffer(Renderer.NodeBytesHint))
      else {
        busy = true
        try {
          buf.reset()
          f(buf)
        } finally {
          busy = false
          // One outsized node would otherwise pin its buffer on this thread
          // for the life of the process. Emptied BEFORE trimming, because
          // `trimToSize` trims to the current LENGTH — called on a full buffer
          // it is a no-op, which is what the first version of this did.
          if (buf.capacity > MaxScratchChars) { buf.reset(); buf.trim() }
        }
      }
  }

  /** Above this a scratch buffer is released rather than kept — big enough that
    * an ordinary card never trips it, small enough that a pathological one does
    * not sit on the heap per render thread.
    */
  private val MaxScratchChars = 64 * 1024

  private val scratch: ThreadLocal[Scratch] =
    ThreadLocal.withInitial(() => new Scratch)

  def streaming(w: java.io.Writer): Sink = new Sink.Streaming(w)
}
