package fh.view.runtime

import fh.view.model.{SignalBind, SignalId, SlotValue, ThemeClasses}
import io.circe.Json
import org.http4s.{EntityEncoder, MediaType}
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets.UTF_8

enum PatchMode(val wire: String):
  case Outer extends PatchMode("outer")
  case Inner extends PatchMode("inner")
  case Replace extends PatchMode("replace")
  case Prepend extends PatchMode("prepend")
  case Append extends PatchMode("append")
  case Before extends PatchMode("before")
  case After extends PatchMode("after")
  case Remove extends PatchMode("remove")

/** One SSE event, encoded to bytes at construction. That shares only the
  * constant frames (keep-alive, recover marker, reload); live frames are still
  * encoded per session, since each is built by [[Patches.resume]]. Byte- and
  * header-identical to http4s's own encoder (`FoldingSinkPropertySuite`). A
  * chunk, not an array: a repaint's frame is the pieces its walk was encoded in
  * ([[Datastar.patchWritten]]), which joining would copy the page again. Equal
  * by content.
  */
private[view] final case class SseFrame(bytes: fs2.Chunk[Byte]):

  def render: String = new String(bytes.toArray, UTF_8)

  // For tests and diagnostics; the hot path touches only `bytes`.
  private lazy val parsed: (Option[String], Option[String], Option[String]) = {
    var lines = List.empty[String]
    var ev = Option.empty[String]
    var com = Option.empty[String]
    val cursor = render.linesIterator
    cursor.foreach { l =>
      if (l.startsWith("data: ")) lines = l.drop("data: ".length) :: lines
      else if (l.startsWith("event: ")) ev = Some(l.drop("event: ".length))
      else if (l.startsWith(": ")) com = Some(l.drop(2))
    }
    (
      Option.unless(lines.isEmpty)(lines.reverse.mkString("\n")),
      ev,
      com
    )
  }

  def data: Option[String] = parsed._1
  def eventType: Option[String] = parsed._2
  def comment: Option[String] = parsed._3

private[view] object SseFrame:

  def comment(text: String): SseFrame =
    SseFrame(fs2.Chunk.array(s": $text\n\n".getBytes(UTF_8)))

  given frameStreamEncoder[F[_]]: EntityEncoder[F, fs2.Stream[F, SseFrame]] =
    EntityEncoder
      .entityBodyEncoder[F]
      .contramap[fs2.Stream[F, SseFrame]](
        _.flatMap(f => fs2.Stream.chunk(f.bytes))
      )
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))

/** Datastar SSE framing, for the pinned v1.0.4 bundle. */
object Datastar {

  def patchElements(fragment: String): SseFrame =
    patch(fragment)

  /** Adjacent morphs as one event. */
  def patchElements(fragments: List[String]): SseFrame =
    fragments match
      case one :: Nil => patch(one)
      case _          => patch(fragments.mkString)

  /** A fragment in hand is collapsed and encoded whole, its bytes a piece of
    * the frame: folded and encoded a character at a time, as a walk is, it
    * doubled `RenderBench.wire`'s time against the intrinsics.
    */
  def patch(
      fragment: String,
      mode: PatchMode = PatchMode.Outer,
      selector: Option[String] = None
  ): SseFrame = {
    val body = collapse(fragment).getBytes(UTF_8)
    val w = elementsHead(selector, Option.when(mode != PatchMode.Outer)(mode))
    w.piece(body)
    w.write('\n')
    w.end(PatchElementsEvent)
  }

  /** [[patch]] over `selector` whose fragment `fill` writes, encoded as it is
    * written ([[Sink.Folding]]): a whole page held as a `String` was copied
    * five times on its way to bytes (`HouseBench.repaint`).
    */
  def patchWritten[A](selector: String)(fill: Sink => A): (SseFrame, A) = {
    val w = elementsHead(Some(selector), None)
    val folding = new Sink.Folding(w)
    val a = fill(folding)
    folding.finish()
    w.write('\n')
    (w.end(PatchElementsEvent), a)
  }

  private def elementsHead(
      selector: Option[String],
      mode: Option[PatchMode]
  ): FrameWriter = {
    val w = new FrameWriter(selector.fold(0)(_.length) + 96)
    selector.foreach(s => w.line("selector ", s))
    mode.foreach(m => w.line("mode ", m.wire))
    w.write("data: elements ")
    w
  }

  private val PatchElementsEvent = "datastar-patch-elements"

  /** Collapses whitespace runs containing a newline, so the fragment fits one
    * `elements` line; [[Sink.Folding]] is the same as a walk writes, pinned
    * against this by `FoldingSinkPropertySuite`. A value's newlines survive as
    * `&#10;` (`Templates`); a line break written into a template's own `<pre>`
    * does not.
    */
  private val LineRun = java.util.regex.Pattern.compile("\\s*\\r?\\n\\s*")

  private[runtime] def collapse(html: String): String =
    if (html.indexOf('\n') < 0) html.trim
    else LineRun.matcher(html).replaceAll(" ").trim

  /** Removing an absent id is a no-op, which set member removal relies on. */
  def remove(selector: String): SseFrame = {
    val w = new FrameWriter(selector.length + 80)
    w.line("mode ", "remove")
    w.line("selector ", selector)
    w.end(PatchElementsEvent)
  }

  def patchSignals(signalsJson: String): SseFrame =
    signals(0)(_.piece(signalsJson.getBytes(UTF_8)))

  /** [[signalsJson]]'s object, written into the frame. */
  def patchSignals(values: Map[SignalId, Json]): SseFrame =
    signals(values.size * 48)(nestInto(_, values))

  private def signals(sizeHint: Int)(json: FrameWriter => Unit): SseFrame = {
    val w = new FrameWriter(sizeHint + 64)
    w.write("data: signals ")
    json(w)
    w.write('\n')
    w.end("datastar-patch-signals")
  }

  /** Sorted, so a frame's bytes are a function of its contents.
    *
    * '''Never a `Json.Null`''': null deletes the signal in the pinned bundle,
    * orphaning every binding on it, silently (`DatastarMorphContractSuite`). A
    * rule, not a type, because the cursor needs `Json` values.
    */
  def signalsJson(values: Map[SignalId, Json]): String = {
    val sb = new java.lang.StringBuilder(values.size * 48 + 2)
    nestInto(sb, values)
    sb.toString
  }

  private def nestInto(out: Appendable, values: Map[SignalId, Json]): Unit =
    if (values.isEmpty) { val _ = out.append("{}") }
    else nest(out, pathsOf(values), 0, values.size, 0)

  /** Dotted paths are nested: `mergePatch` would store a flat
    * `"_e.light.x.state"` as one literal key, and a partial patch stays safe
    * because it assigns only at leaves. One walk over rows pre-sorted by path,
    * printing as it goes: grouping per level was half a page open's allocation
    * (`RenderBench.pageSignals`), and a `Json` built to be printed once was
    * most of a signals frame's.
    */
  private def nest(
      out: Appendable,
      paths: Array[(Array[String], Json)],
      from: Int,
      until: Int,
      depth: Int
  ): Unit = {
    out.append('{')
    var i = from
    while (i < until) {
      val segment = paths(i)._1(depth)
      var j = i + 1
      while (j < until && paths(j)._1(depth) == segment) j += 1
      if (i > from) { val _ = out.append(',') }
      // circe's escaping, so the bytes are `Json.noSpaces`'s.
      io.circe.Printer.noSpaces
        .unsafePrintToAppendable(Json.fromString(segment), out)
      out.append(':')
      // No path is both leaf and prefix: `signalName` mints one depth.
      if (paths(i)._1.length == depth + 1)
        io.circe.Printer.noSpaces.unsafePrintToAppendable(paths(i)._2, out)
      else nest(out, paths, i, j, depth + 1)
      i = j
    }
    val _ = out.append('}')
  }

  /** One SSE event written as UTF-8 straight into its chunk, the bytes http4s's
    * `ServerSentEvent.renderString` gives: each `data:` line, then the event
    * line and the blank line. A full buffer becomes a piece of the chunk as it
    * is, so a page is never joined, grown or copied out.
    *
    * Lone surrogates encode as `?`, as `String.getBytes(UTF_8)` does; a high
    * one is held until the next character, and every frame ends in ASCII.
    */
  private final class FrameWriter(sizeHint: Int) extends java.io.Writer {
    private var buf = new Array[Byte](sizeHint)
    // `buf` from `start` is not yet a piece; before it, it is one, and stays.
    private var start = 0
    private var len = 0
    private var pieces: fs2.Chunk[Byte] = fs2.Chunk.empty
    private var high: Char = 0

    /** `bytes`, already encoded, as a piece of its own: never copied. */
    def piece(bytes: Array[Byte]): Unit = {
      seal()
      pieces = pieces ++ fs2.Chunk.array(bytes)
    }

    private def seal(): Unit =
      if (len > start) {
        pieces = pieces ++ fs2.Chunk.array(buf, start, len - start)
        start = len
      }

    /** A `data:` line; neither part may hold a newline. */
    def line(key: String, value: String): Unit = {
      write("data: ")
      write(key)
      write(value)
      write('\n')
    }

    def end(eventType: String): SseFrame = {
      write("event: ")
      write(eventType)
      write("\n\n")
      seal()
      SseFrame(pieces)
    }

    private def room(n: Int): Unit =
      if (len + n > buf.length) {
        seal()
        buf = new Array[Byte](Server.PageChunkBytes)
        start = 0
        len = 0
      }

    private def put(b: Byte): Unit = {
      room(1)
      buf(len) = b
      len += 1
    }

    private def char(c: Char): Unit =
      if (high != 0) {
        val h = high
        high = 0
        if (Character.isLowSurrogate(c)) {
          val cp = Character.toCodePoint(h, c)
          room(4)
          buf(len) = (0xf0 | (cp >> 18)).toByte
          buf(len + 1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
          buf(len + 2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
          buf(len + 3) = (0x80 | (cp & 0x3f)).toByte
          len += 4
        } else {
          put('?'.toByte)
          char(c)
        }
      } else if (c < 0x80) put(c.toByte)
      else if (c < 0x800) {
        room(2)
        buf(len) = (0xc0 | (c >> 6)).toByte
        buf(len + 1) = (0x80 | (c & 0x3f)).toByte
        len += 2
      } else if (Character.isHighSurrogate(c)) high = c
      else if (Character.isLowSurrogate(c)) put('?'.toByte)
      else {
        room(3)
        buf(len) = (0xe0 | (c >> 12)).toByte
        buf(len + 1) = (0x80 | ((c >> 6) & 0x3f)).toByte
        buf(len + 2) = (0x80 | (c & 0x3f)).toByte
        len += 3
      }

    override def write(cbuf: Array[Char], off: Int, n: Int): Unit = {
      val end = off + n
      var i = off
      while (i < end) {
        if (high == 0) {
          val b = buf
          val stop = math.min(end, i + b.length - len)
          var l = len
          while (i < stop && cbuf(i) < 0x80) {
            b(l) = cbuf(i).toByte
            l += 1
            i += 1
          }
          len = l
        }
        if (i < end) { char(cbuf(i)); i += 1 }
      }
    }
    override def write(str: String, off: Int, n: Int): Unit = {
      val end = off + n
      var i = off
      while (i < end) {
        if (high == 0) {
          val b = buf
          val stop = math.min(end, i + b.length - len)
          var l = len
          while (i < stop && str.charAt(i) < 0x80) {
            b(l) = str.charAt(i).toByte
            l += 1
            i += 1
          }
          len = l
        }
        if (i < end) { char(str.charAt(i)); i += 1 }
      }
    }
    override def write(str: String): Unit = write(str, 0, str.length)
    override def write(c: Int): Unit =
      if (c < 0x80 && high == 0 && len < buf.length) {
        buf(len) = c.toByte
        len += 1
      } else char(c.toChar)
    override def append(csq: CharSequence): this.type = {
      val s = String.valueOf(csq)
      write(s, 0, s.length)
      this
    }
    override def append(c: Char): this.type = { char(c); this }
    override def flush(): Unit = ()
    override def close(): Unit = ()
  }

  private def pathsOf[A](
      values: Map[SignalId, A]
  ): Array[(Array[String], A)] = {
    val out = new Array[(Array[String], A)](values.size)
    var i = 0
    values.foreach { case (k, v) =>
      out(i) = (k.segments, v)
      i += 1
    }
    // One comparator for every `A`: it reads only `._1`.
    val order =
      pathOrder.asInstanceOf[PathOrder[A]] // scalafix:ok DisableSyntax
    java.util.Arrays.sort(out, order)
    out
  }

  /** By segments, not the dotted string: they diverge when a segment holds a
    * character below `.` (`a-b.c` vs `a.b`). Pinned in `DatastarNestSuite`.
    */
  private type PathOrder[A] = java.util.Comparator[(Array[String], A)]

  private val pathOrder: PathOrder[Any] =
    (x, y) => {
      val a = x._1
      val b = y._1
      var i = 0
      var r = 0
      while (r == 0 && i < a.length && i < b.length) {
        r = a(i).compareTo(b(i))
        i += 1
      }
      if (r != 0) r else a.length - b.length
    }

  /** The inline seed (ADR 0017). `data-signals` is compiled as a JS expression,
    * not JSON, so values are single-quoted JS literals escaped for both
    * contexts ([[escapeJsInto]]).
    */
  def signalsAttr(values: Map[SignalId, SlotValue]): String =
    if (values.isEmpty) ""
    else
      // Leading space: `id="c"data-signals=…` is a parse error.
      val sb = new java.lang.StringBuilder(32 + values.size * 48)
      sb.append(" data-signals=\"")
      nestJsInto(sb, pathsOf(values), 0, values.size, 0)
      sb.append('"')
      sb.toString

  /** A seed with its values cut out, built at plan time since only values move;
    * `chunks` is one longer than `order`.
    */
  final class SignalSeed private[runtime] (
      val chunks: Array[String],
      val order: Array[SignalId]
  )

  private val emptySeed = new SignalSeed(Array(""), Array.empty)

  def seedFor(names: Iterable[SignalId]): SignalSeed =
    if (names.isEmpty) emptySeed
    else {
      val paths = pathsOf(names.map(n => n -> n).toMap)
      val chunks = Array.newBuilder[String]
      val order = Array.newBuilder[SignalId]
      val sb = new java.lang.StringBuilder(64)
      sb.append(" data-signals=\"")
      seedInto(sb, chunks, order, paths, 0, paths.length, 0)
      sb.append('"')
      chunks += sb.toString
      new SignalSeed(chunks.result(), order.result())
    }

  private def seedInto(
      sb: java.lang.StringBuilder,
      chunks: scala.collection.mutable.Builder[String, Array[String]],
      order: scala.collection.mutable.Builder[SignalId, Array[SignalId]],
      paths: Array[(Array[String], SignalId)],
      from: Int,
      until: Int,
      depth: Int
  ): Unit = {
    sb.append('{')
    var i = from
    while (i < until) {
      val segment = paths(i)._1(depth)
      var j = i + 1
      while (j < until && paths(j)._1(depth) == segment) j += 1
      if (i > from) { val _ = sb.append(", ") }
      sb.append(segment).append(": ")
      if (paths(i)._1.length == depth + 1) {
        sb.append('\'')
        chunks += sb.toString
        order += paths(i)._2
        sb.setLength(0)
        sb.append('\'')
      } else seedInto(sb, chunks, order, paths, i, j, depth + 1)
      i = j
    }
    val _ = sb.append('}')
  }

  /** Falls back to [[signalsAttr]] unless `values` are exactly the seed's
    * signals: checked, since a mismatch would silently emit the wrong shape.
    */
  def seedAttrInto(
      out: java.lang.Appendable,
      seed: SignalSeed,
      values: Map[SignalId, SlotValue]
  ): Unit = {
    val n = seed.order.length
    // Before writing: `out` may be a stream, which cannot be unwound.
    val resolved =
      if (values.isEmpty || values.size != n) null
      else {
        val vs = new Array[String](n)
        var i = 0
        while (i < n && vs != null) {
          values.get(seed.order(i)) match {
            // The chunks bake in quotes, and a boolean seeds unquoted.
            case Some(v: String) => vs(i) = v; i += 1
            case _               => i = n + 1
          }
        }
        if (i == n) vs else null
      }
    if (values.isEmpty) ()
    else if (resolved eq null) { val _ = out.append(signalsAttr(values)) }
    else {
      out.append(seed.chunks(0))
      var k = 0
      while (k < n) {
        escapeJsInto(out, resolved(k))
        out.append(seed.chunks(k + 1))
        k += 1
      }
      ()
    }
  }

  /** Nested like [[nest]]: `{a.b: 'x'}` is not even valid JS. */
  private def nestJsInto(
      sb: java.lang.StringBuilder,
      paths: Array[(Array[String], SlotValue)],
      from: Int,
      until: Int,
      depth: Int
  ): Unit = {
    sb.append('{')
    var i = from
    while (i < until) {
      val segment = paths(i)._1(depth)
      var j = i + 1
      while (j < until && paths(j)._1(depth) == segment) j += 1
      if (i > from) { val _ = sb.append(", ") }
      sb.append(segment).append(": ")
      if (paths(i)._1.length == depth + 1) {
        // Unquoted: `'false'` is a truthy string, which sets a `data-attr`.
        paths(i)._2 match
          case b: Boolean => sb.append(b)
          case d: Double  => sb.append(SlotValue.number(d))
          case s: String  =>
            sb.append('\'')
            escapeJsInto(sb, s)
            sb.append('\'')
      } else nestJsInto(sb, paths, i, j, depth + 1)
      i = j
    }
    val _ = sb.append('}')
  }

  /** What `<slot>.bind` renders (ADR 0017). The signal is read bare: the value
    * carries its own shape (`39.37%`, `#ffb46b`, a real boolean), so the
    * transform is the one place that decides it. `data-bind` takes the name,
    * since it writes back.
    */
  def binding(
      signal: SignalId,
      kind: SignalBind,
      classes: ThemeClasses
  ): String = kind match
    case SignalBind.Bind => s"""data-bind="$signal""""
    case paints          => painting(s"$$$signal", paints, classes)

  /** A binding that only paints, over any expression: a signal, or a variable's
    * own signals (`BoundSlot.chosenSignal`). `Bind` writes back to a name, so
    * it has none.
    */
  def painting(read: String, kind: SignalBind, classes: ThemeClasses): String =
    kind match
      case SignalBind.Text            => s"""data-text="$read""""
      case SignalBind.Style(property) => s"""data-style:$property="$read""""
      case SignalBind.Attr(name)      => s"""data-attr:$name="$read""""
      // The bundle kebab-cases a `data-class` key, so write the CSS name.
      case SignalBind.Class(name) =>
        classes
          .expand(name)
          .map(c => s"""data-class:$c="$read"""")
          .mkString(" ")
      case SignalBind.Handler | SignalBind.Bind => ""

  /** [[jsLiteral]], but a boolean reads back as one: `'false'` is truthy. */
  def jsValue(value: SlotValue): String = value match
    case b: Boolean => b.toString
    case d: Double  => SlotValue.number(d)
    case s: String  => jsLiteral(s)

  /** The non-signal half of `<slot>.jsValue`, through the seed's escaper. */
  def jsLiteral(value: String): String = {
    val sb = new java.lang.StringBuilder(value.length + 2)
    val _ = sb.append('\'')
    escapeJsInto(sb, value)
    val _ = sb.append('\'')
    sb.toString
  }

  /** A JS string literal inside an HTML attribute, escaped for both in one
    * pass. `'` is JS-escaped, never `&#39;`, which decodes back and closes the
    * literal.
    */
  private def escapeJsInto(sb: java.lang.Appendable, s: String): Unit = {
    val n = s.length
    var i = 0
    var start = 0
    def flush(upto: Int): Unit =
      if (upto > start) { val _ = sb.append(s, start, upto) }
    while (i < n) {
      val replacement = s.charAt(i) match {
        case '\\' => "\\\\"
        case '\'' => "\\'"
        case '&'  => "&amp;"
        case '<'  => "&lt;"
        case '"'  => "&quot;"
        case _    => null
      }
      if (replacement ne null) {
        flush(i)
        sb.append(replacement)
        start = i + 1
      }
      i += 1
    }
    flush(n)
  }
}
