package fh.view.runtime

import fh.view.model.{SignalBind, SignalId, SlotValue}
import io.circe.Json
import org.http4s.{EntityEncoder, MediaType, ServerSentEvent}
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
  * header-identical to http4s's own encoder.
  */
private[view] final case class SseFrame(bytes: Array[Byte]):

  def render: String = new String(bytes, UTF_8)

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

  // By content: arrays compare by reference.
  override def equals(that: Any): Boolean = that match
    case f: SseFrame => java.util.Arrays.equals(bytes, f.bytes)
    case _           => false

  override def hashCode: Int = java.util.Arrays.hashCode(bytes)

private[view] object SseFrame:

  def of(event: ServerSentEvent): SseFrame =
    SseFrame(event.renderString.getBytes(UTF_8))

  def comment(text: String): SseFrame =
    SseFrame(s": $text\n\n".getBytes(UTF_8))

  given frameStreamEncoder[F[_]]: EntityEncoder[F, fs2.Stream[F, SseFrame]] =
    EntityEncoder
      .entityBodyEncoder[F]
      .contramap[fs2.Stream[F, SseFrame]](
        _.flatMap(f => fs2.Stream.chunk(fs2.Chunk.array(f.bytes)))
      )
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))

/** Datastar SSE framing, for the pinned v1.0.2 bundle. */
object Datastar {

  def patchElements(fragment: String): SseFrame =
    patch(fragment)

  def patch(
      fragment: String,
      mode: PatchMode = PatchMode.Outer,
      selector: Option[String] = None
  ): SseFrame =
    sse(
      "datastar-patch-elements",
      selector.map(s => s"selector $s").toList ++
        Option.when(mode != PatchMode.Outer)(s"mode ${mode.wire}").toList ++
        List("elements " + collapse(fragment))
    )

  /** Removing an absent id is a no-op, which set member removal relies on. */
  def remove(selector: String): SseFrame =
    sse(
      "datastar-patch-elements",
      List("mode remove", s"selector $selector")
    )

  /** http4s writes the `data:` prefix once and embedded newlines verbatim, so
    * each protocol line is joined with its own prefix; each must be one line.
    */
  private def sse(eventType: String, lines: List[String]): SseFrame =
    SseFrame.of(
      ServerSentEvent(
        data = Some(lines.mkString("\ndata: ")),
        eventType = Some(eventType)
      )
    )

  /** Collapses whitespace runs containing a newline, so the fragment fits one
    * `elements` line. This also flattens the line breaks inside a `<pre>`.
    */
  private val LineRun = java.util.regex.Pattern.compile("\\s*\\r?\\n\\s*")

  private def collapse(html: String): String =
    if (html.indexOf('\n') < 0) html.trim
    else LineRun.matcher(html).replaceAll(" ").trim

  def patchSignals(signalsJson: String): SseFrame =
    SseFrame.of(
      ServerSentEvent(
        data = Some(s"signals $signalsJson"),
        eventType = Some("datastar-patch-signals")
      )
    )

  /** Sorted, so a frame's bytes are a function of its contents.
    *
    * '''Never a `Json.Null`''': null deletes the signal in the pinned bundle,
    * orphaning every binding on it, silently (`DatastarMorphContractSuite`). A
    * rule, not a type, because the cursor needs `Json` values.
    */
  def signalsJson(values: Map[SignalId, Json]): String =
    if (values.isEmpty) "{}"
    else nest(pathsOf(values), 0, values.size, 0).noSpaces

  /** Dotted paths are nested: `mergePatch` would store a flat
    * `"_e.light.x.state"` as one literal key, and a partial patch stays safe
    * because it assigns only at leaves. One walk over rows pre-sorted by path;
    * grouping per level was half a page open's allocation
    * (`RenderBench.pageSignals`).
    */
  private def nest(
      paths: Array[(Array[String], Json)],
      from: Int,
      until: Int,
      depth: Int
  ): Json = {
    val fields = List.newBuilder[(String, Json)]
    var i = from
    while (i < until) {
      val segment = paths(i)._1(depth)
      var j = i + 1
      while (j < until && paths(j)._1(depth) == segment) j += 1
      // No path is both leaf and prefix: `signalName` mints one depth.
      val value =
        if (paths(i)._1.length == depth + 1) paths(i)._2
        else nest(paths, i, j, depth + 1)
      fields += (segment -> value)
      i = j
    }
    Json.obj(fields.result()*)
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
    * `chunks` is one longer than `order`. A transform subject has no fixed
    * names and keeps [[signalsAttr]].
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
          case s: String  =>
            sb.append('\'')
            escapeJsInto(sb, s)
            sb.append('\'')
      } else nestJsInto(sb, paths, i, j, depth + 1)
      i = j
    }
    val _ = sb.append('}')
  }

  /** What `<slot>__bind` renders (ADR 0017). The signal is read bare: the value
    * carries its own shape (`39.37%`, `#ffb46b`, a real boolean), so the
    * transform is the one place that decides it. `data-bind` takes the name,
    * since it writes back.
    */
  def binding(signal: SignalId, kind: SignalBind): String = kind match
    case SignalBind.Text            => s"""data-text="$$$signal""""
    case SignalBind.Bind            => s"""data-bind="$signal""""
    case SignalBind.Style(property) =>
      s"""data-style:$property="$$$signal""""
    case SignalBind.Attr(name) => s"""data-attr:$name="$$$signal""""
    // The bundle kebab-cases a `data-class` key, so write the CSS name.
    case SignalBind.Class(name) => s"""data-class:$name="$$$signal""""
    case SignalBind.Handler     => ""

  /** The non-signal half of `<slot>__read`, through the seed's escaper. */
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
