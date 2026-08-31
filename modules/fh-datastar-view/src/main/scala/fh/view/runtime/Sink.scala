package fh.view.runtime

/** Where a walk puts its bytes.
  *
  * The document walk and the patch walk are the same walk with different
  * destinations: a patch BUILDS a String, because those bytes are its cache
  * entry and its morph payload; a page WRITES its bytes at the client and keeps
  * none of them. Only one operation actually differs between the two, and it is
  * [[digesting]].
  *
  * A `Writer`, because mustache executes against one — making the sink the
  * writer removes the adapter object the walk used to allocate at every
  * `Templates.run`.
  */
private[runtime] sealed abstract class Sink extends java.io.Writer {

  /** Run `f` and fingerprint exactly the bytes it wrote, returning them too
    * when `keepBytes` — which only a patch walk's ROOT asks for.
    *
    * This is the whole reason the sink is a type rather than a `Writer`. A
    * node's own digest is a digest of a CONTIGUOUS RUN of the walk's output,
    * and how you get one depends on whether that output is still reachable: a
    * buffer slices the run it just appended and copies nothing, while a stream
    * has already let those bytes go and has to catch the run on the way past.
    */
  def digesting(keepBytes: Boolean)(f: Sink => Unit): (Digest, String | Null)

  override def flush(): Unit = ()
  override def close(): Unit = ()
}

private[runtime] object Sink {

  /** A sink over a `StringBuilder` — no lock, no second buffer, no copy on the
    * way out, and `digesting` is a slice of bytes already in hand.
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
    * Deliberately NOT an incremental digest over the encoded bytes. Those bytes
    * are downstream of here, so reaching them means encoding a second time, and
    * a hand-rolled encode measured +83 kB and +17% against `String.getBytes`,
    * which is intrinsified. One buffered node is cheaper than one extra encode
    * of the page.
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
      val buf = Sink.buffer(Renderer.NodeBytesHint)
      f(buf)
      val bytes = buf.result
      w.write(bytes)
      (Digest.of(bytes), if (keepBytes) bytes else null)
    }
  }

  def buffer(sizeHint: Int): Sink.Buffer = new Sink.Buffer(
    new java.lang.StringBuilder(sizeHint)
  )

  def streaming(w: java.io.Writer): Sink = new Sink.Streaming(w)
}
