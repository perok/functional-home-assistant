package fh.view.runtime

/** Where a walk puts its bytes: a patch builds a String (its cache entry), a
  * page writes to the client. A `Writer`, so mustache runs against it with no
  * adapter per `Templates.run`.
  */
private[runtime] sealed abstract class Sink extends java.io.Writer {

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

    def result: String = sb.toString

    private[runtime] def reset(): Unit = sb.setLength(0)

    private[runtime] def capacity: Int = sb.capacity

    private[runtime] def trim(): Unit = sb.trimToSize()
  }

  /** The page's sink: straight through to the response's writer, so the page is
    * never held whole (`RenderBench.pageWalkStream` vs `page`).
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
  }

  /** `Datastar.collapse` as the walk writes, so a fragment reaches one SSE line
    * without being held as a `String`: a whitespace run holding a newline is
    * one space, and the ends are trimmed as `String.trim` does. A blank run is
    * held until the next character says whether it was trailing;
    * [[Folding.finish]] drops the last one.
    */
  final class Folding(w: java.io.Writer) extends Sink {
    private val blanks = new java.lang.StringBuilder(64)
    private var started = false

    override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
      val end = off + len
      var from = off
      var i = off
      while (i < end) {
        if (cbuf(i) <= ' ') {
          if (i > from) w.write(cbuf, from, i - from)
          blanks.append(cbuf(i))
          from = i + 1
        } else if (blanks.length > 0) releaseBlanks()
        else started = true
        i += 1
      }
      if (end > from) w.write(cbuf, from, end - from)
    }

    override def write(str: String, off: Int, len: Int): Unit = {
      val end = off + len
      var from = off
      var i = off
      while (i < end) {
        val c = str.charAt(i)
        if (c <= ' ') {
          if (i > from) w.write(str, from, i - from)
          blanks.append(c)
          from = i + 1
        } else if (blanks.length > 0) releaseBlanks()
        else started = true
        i += 1
      }
      if (end > from) w.write(str, from, end - from)
    }

    override def write(str: String): Unit = write(str, 0, str.length)
    override def write(c: Int): Unit = {
      val one = c.toChar
      if (one <= ' ') { val _ = blanks.append(one) }
      else {
        if (blanks.length > 0) releaseBlanks() else started = true
        w.write(c)
      }
    }
    override def append(csq: CharSequence): this.type = {
      write(String.valueOf(csq)); this
    }
    override def append(c: Char): this.type = { write(c.toInt); this }

    /** A held run is interior once a character follows it: each of its `\s`
      * runs holding a newline is one space, which is `collapse`'s regex.
      */
    private def releaseBlanks(): Unit = {
      if (started) {
        val n = blanks.length
        var i = 0
        while (i < n) {
          val c = blanks.charAt(i)
          if (Folding.isSpace(c)) {
            var j = i
            var newline = false
            while (j < n && Folding.isSpace(blanks.charAt(j))) {
              newline ||= blanks.charAt(j) == '\n'
              j += 1
            }
            if (newline) w.write(' ')
            else while (i < j) { w.write(blanks.charAt(i)); i += 1 }
            i = j
          } else {
            w.write(c)
            i += 1
          }
        }
      }
      started = true
      blanks.setLength(0)
    }

    /** The trailing run, dropped: the fragment ends here. */
    def finish(): Unit = blanks.setLength(0)
  }

  private object Folding {
    // Java regex's `\s` without UNICODE_CHARACTER_CLASS.
    def isSpace(c: Char): Boolean =
      c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' ||
        c == '\r'
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
