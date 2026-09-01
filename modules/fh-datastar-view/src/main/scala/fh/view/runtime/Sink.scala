package fh.view.runtime

/** Where a walk puts its bytes: one buffer, from the document's first byte to
  * its last.
  *
  * A `Writer`, because mustache executes against one — making the sink the
  * writer removes the adapter object the walk used to allocate at every
  * `Templates.run`. A type of its own rather than a bare `StringBuilder`,
  * because of [[digesting]]: a node's own fingerprint is a digest of a
  * CONTIGUOUS RUN of the walk's output, and that rule was open-coded wherever
  * it applied — a `length` read, a render, a second `length` read, and a slice
  * between them.
  */
private[runtime] final class Sink(private val sb: java.lang.StringBuilder)
    extends java.io.Writer {

  override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
    val _ = sb.append(cbuf, off, len)
  }
  override def write(str: String): Unit = { val _ = sb.append(str) }
  // The 3-arg String form is Writer's other default that allocates a copy per
  // call; appending the slice directly keeps bulk writers (the escaping runs,
  // template literals) allocation-free.
  override def write(str: String, off: Int, len: Int): Unit = {
    val _ = sb.append(str, off, off + len)
  }
  override def write(c: Int): Unit = { val _ = sb.append(c.toChar) }
  override def append(csq: CharSequence): this.type = {
    val _ = sb.append(csq); this
  }
  override def append(c: Char): this.type = { val _ = sb.append(c); this }
  override def flush(): Unit = ()
  override def close(): Unit = ()

  /** Run `f` and fingerprint exactly the bytes it wrote, returning them too
    * when `keepBytes` — which only a patch walk's ROOT asks for.
    *
    * Nothing is copied to get the digest: the run is still in the buffer, so
    * this slices what the walk just appended rather than rendering the node a
    * second time into somewhere it can be measured.
    */
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

private[runtime] object Sink {
  def buffer(sizeHint: Int): Sink = new Sink(
    new java.lang.StringBuilder(sizeHint)
  )
}
