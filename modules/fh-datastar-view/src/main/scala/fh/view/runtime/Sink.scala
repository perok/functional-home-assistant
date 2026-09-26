package fh.view.runtime

/** Where a walk puts its bytes: a patch builds a String (its cache entry), a
  * page writes to the client. A `Writer`, so mustache runs against it with no
  * adapter per `Templates.run`.
  */
private[runtime] sealed abstract class Sink extends java.io.Writer {

  /** Digest exactly the bytes `f` wrote, and return them when `keepBytes` (only
    * a patch walk's root). A buffer bounds the run by offsets; a stream catches
    * it on the way past. Both copy the run once.
    */
  def digesting(keepBytes: Boolean)(f: Sink => Unit): (Digest, String | Null)

  override def flush(): Unit = ()
  override def close(): Unit = ()
}

private[runtime] object Sink {

  final class Buffer(private val sb: java.lang.StringBuilder) extends Sink {
    override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
      val _ = sb.append(cbuf, off, len)
    }
    override def write(str: String): Unit = { val _ = sb.append(str) }
    // Writer's default copies the slice per call.
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

  /** The page's sink: `digesting` buffers one node at a time, so the peak is
    * the largest own-rendering subtree, not the document. Not a
    * `DigestOutputStream`: bounding a run in the byte stream needs a flush per
    * leaf, against a `BufferedWriter` worth 729 kB. Still 219 kB cheaper than
    * the buffer (`RenderBench.pageWalkStreamPlain` vs `page`).
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

  /** A per-thread buffer borrowed for a run whose bytes `f` copies out: a fresh
    * one per patch form was a quarter of a page open. Safe under fibers because
    * the walk never suspends.
    *
    * A nested borrow gets its own buffer. None happens today, but sharing one
    * would splice two nodes' bytes into still-valid HTML, silently.
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
          // Emptied before trimming: `trimToSize` trims to the length, so on a
          // full buffer it is a no-op.
          if (buf.capacity > MaxScratchChars) { buf.reset(); buf.trim() }
        }
      }
  }

  // So one outsized node does not pin its buffer per thread for good.
  private val MaxScratchChars = 64 * 1024

  private val scratch: ThreadLocal[Scratch] =
    ThreadLocal.withInitial(() => new Scratch)

  def streaming(w: java.io.Writer): Sink = new Sink.Streaming(w)
}
