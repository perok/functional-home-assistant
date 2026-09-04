package fh.view.runtime

import fh.view.model.{SignalBind, SignalId}
import io.circe.Json
import org.http4s.{EntityEncoder, MediaType, ServerSentEvent}
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets.UTF_8

/** `datastar-patch-elements` patch modes (the `data: mode …` value). */
enum PatchMode(val wire: String):
  case Outer extends PatchMode("outer")
  case Inner extends PatchMode("inner")
  case Replace extends PatchMode("replace")
  case Prepend extends PatchMode("prepend")
  case Append extends PatchMode("append")
  case Before extends PatchMode("before")
  case After extends PatchMode("after")
  case Remove extends PatchMode("remove")

/** One SSE event, encoded to its WIRE BYTES at construction rather than per
  * connection inside http4s's `ServerSentEvent.encoder`
  * (`_.map(_.renderString).through(utf8.encode)`).
  *
  * '''This does not, on its own, share anything.''' A live batch's frames are
  * built inside [[Patches.resume]], which runs per session — what a client is
  * owed depends on its own `holds`, permissions and selections — so a value
  * tick still costs one encode per client, exactly as the http4s encoder did.
  * What moving the encode here buys is the CONSTANT frames: the keep-alive
  * comment, the recover marker and the reload patch are encoded once for the
  * process instead of once per emission per socket. Any real fan-out saving has
  * to come from a frame minted where the RENDER already is — per slug, in
  * [[RenderCache]] — and nothing does that today.
  *
  * Byte-identical to the http4s path BY CONSTRUCTION: the bytes are the same
  * `renderString` output, UTF-8, and [[SseFrame.frameStreamEncoder]] is the
  * same `entityBody` + `text/event-stream` pair the http4s implicit is built
  * from.
  */
private[view] final case class SseFrame(bytes: Array[Byte]):

  /** The wire text — tests and diagnostics read this rather than re-derive. */
  def render: String = new String(bytes, UTF_8)

  /** The event's fields, parsed back out of the wire text — the assertion
    * surface for tests and diagnostics. The hot path only ever touches `bytes`;
    * nothing on it reads these.
    */
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

  // Bytes, not the default case-class identity: two frames carrying the same
  // event ARE equal (a shared wire and a per-client re-encode of the same
  // patch must compare equal in tests).
  override def equals(that: Any): Boolean = that match
    case f: SseFrame => java.util.Arrays.equals(bytes, f.bytes)
    case _           => false

  override def hashCode: Int = java.util.Arrays.hashCode(bytes)

private[view] object SseFrame:

  /** From an http4s event — the ONE encoding, verbatim. */
  def of(event: ServerSentEvent): SseFrame =
    SseFrame(event.renderString.getBytes(UTF_8))

  /** A comment-only keep-alive (`: text\n\n`). */
  def comment(text: String): SseFrame =
    SseFrame(s": $text\n\n".getBytes(UTF_8))

  /** SSE response encoder for a frame stream — the http4s implicit it replaces
    * was `entityBodyEncoder.contramap(_.through(ServerSentEvent.encoder))
    * .withContentType(text/event-stream)`; same shape, minus the per-connection
    * render + UTF-8, since the frames are already bytes. Header-identical.
    *
    * In the COMPANION, so implicit search finds it for any
    * `EntityEncoder[F, Stream[F, SseFrame]]` with no import. http4s puts its
    * own in `EntityEncoder`'s companion because it owns that type; `SseFrame`
    * is the part of the type we own, so ours goes here.
    */
  given frameStreamEncoder[F[_]]: EntityEncoder[F, fs2.Stream[F, SseFrame]] =
    EntityEncoder
      .entityBodyEncoder[F]
      .contramap[fs2.Stream[F, SseFrame]](
        _.flatMap(f => fs2.Stream.chunk(fs2.Chunk.array(f.bytes)))
      )
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))

/** Datastar SSE protocol framing.
  *
  * See https://data-star.dev/reference/sse_events — pin the client bundle
  * version to match (event names / `data-*` syntax have changed across
  * releases).
  */
object Datastar {

  /** A `datastar-patch-elements` event in the default `outer` mode. The
    * fragment's root element must carry an `id`; Datastar morphs the matching
    * element in place.
    *
    * Datastar reads the HTML from a single `data: elements …` line. http4s puts
    * a `data:` prefix on the first line only and emits embedded newlines
    * verbatim, so a multi-line fragment's continuation lines are dropped by the
    * SSE parser (truncating the patch). We therefore collapse inter-tag
    * newlines/indentation — multiline templates are just authoring sugar; the
    * wire form must be one line. (Attribute values already never span lines.)
    */
  def patchElements(fragment: String): SseFrame =
    patch(fragment)

  /** A `datastar-patch-elements` event with an explicit `mode` and optional
    * target `selector`. `outer` (the default) morphs the element matching the
    * fragment's own id; `inner` replaces a target's children;
    * `append`/`prepend` add to a target's children (e.g. stacking a popup into
    * a host) — these need a `selector`. See
    * https://data-star.dev/reference/sse_events.
    */
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

  /** A `datastar-patch-elements` event in `remove` mode: delete the element(s)
    * matching `selector`. This is the one patch shape that carries NO
    * `elements` payload (there is no HTML to send) — see the reference's remove
    * example (`data: mode remove` + `data: selector #id`, no `elements`).
    * Datastar resolves the selector with `querySelectorAll`, so removing an id
    * that is already absent matches nothing and is a no-op — the per-entity
    * candidate set path relies on that idempotency (a duplicate/late remove is
    * harmless).
    */
  def remove(selector: String): SseFrame =
    sse(
      "datastar-patch-elements",
      List("mode remove", s"selector $selector")
    )

  /** Build an SSE event with one Datastar protocol line per `data:` line.
    *
    * http4s 0.23 (`ServerSentEvent.render`) writes the `data:` prefix ONCE then
    * the whole `data` string verbatim, so embedded `\n`s would yield unprefixed
    * lines that the client drops. We therefore join with `"\ndata: "` so each
    * logical line carries its own prefix. Every line must itself be single-line
    * (HTML fragments are collapsed first).
    */
  private def sse(eventType: String, lines: List[String]): SseFrame =
    SseFrame.of(
      ServerSentEvent(
        data = Some(lines.mkString("\ndata: ")),
        eventType = Some(eventType)
      )
    )

  /** Collapse runs of whitespace containing a newline into a single space.
    *
    * The match REQUIRES a newline (`\r?\n`), so a fragment without one — the
    * renderer's own output, which is built single-line — skips the scan
    * entirely. Multi-line templates are just authoring sugar; the wire form
    * must be one line. (Attribute values already never span lines.)
    */
  private val LineRun = java.util.regex.Pattern.compile("\\s*\\r?\\n\\s*")

  private def collapse(html: String): String =
    if (html.indexOf('\n') < 0) html.trim
    else LineRun.matcher(html).replaceAll(" ").trim

  /** A `datastar-patch-signals` event carrying a JSON object of signal updates.
    */
  def patchSignals(signalsJson: String): SseFrame =
    SseFrame.of(
      ServerSentEvent(
        data = Some(s"signals $signalsJson"),
        eventType = Some("datastar-patch-signals")
      )
    )

  /** Signal-slot values as the [[patchSignals]] payload. Sorted, so one frame's
    * bytes are a function of its contents and a test can name them.
    *
    * '''Never put a `Json.Null` in here.''' Null DELETES a signal in the pinned
    * bundle (`if (a == null) delete r[o]` in the store proxy), and every
    * binding already subscribed to that name is then orphaned: reading it
    * afterwards re-creates it as `""`, which nothing is watching, so the
    * elements bound to it never update again. Nothing is reported — no
    * exception, no console error, nothing in `onPageError` — and the rest of
    * the page keeps working, which is what makes it hard to spot.
    *
    * `Map[SignalId, Json]` is wider than that on purpose (the cursor is a
    * nested object — see `Server.versionPatch`), so this is a rule rather than
    * a type. Measured from both directions in `DatastarMorphContractSuite`;
    * every producer today builds values with `Json.fromString`.
    */
  def signalsJson(values: Map[SignalId, Json]): String =
    if (values.isEmpty) "{}"
    else nest(pathsOf(values), 0, values.size, 0).noSpaces

  /** A dotted signal path is NESTED, never emitted as one flat key.
    *
    * `datastar-patch-signals` applies `mergePatch`, which recurses only where a
    * value is an object: a flat `"_e.light.taklys.state"` key would be stored
    * as one literal key with dots IN it, and `$_e.light.taklys.state` — which
    * the bundle rewrites to `$['_e']['light']['taklys']['state']` — would never
    * find it. (`mergePaths` does split dotted keys, but that is not the
    * function the SSE event uses.)
    *
    * Nesting is also what makes a partial patch safe: the same recursion
    * assigns only at leaves, so one entity's frame leaves its siblings alone.
    *
    * Keys are sorted at every level so a frame's bytes are deterministic. The
    * rows arrive sorted by whole path ([[pathsOf]]) and this walks them by
    * index, which is the same order a per-level sort produces — a segment
    * comparison decides before any deeper one is reached. Grouping per level
    * instead (`groupBy.toList.sortBy.collect`) rebuilds a `HashMap`, two
    * `List`s and a `ListBuffer` at EVERY level of EVERY node: half a page
    * open's allocation on a card whose one signal is four segments deep
    * (async-profiler over `RenderBench.pageSignals`).
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
      // A path that is both a leaf and a prefix cannot arise from
      // `Renderer.signalName` — every path it mints has the same depth.
      val value =
        if (paths(i)._1.length == depth + 1) paths(i)._2
        else nest(paths, i, j, depth + 1)
      fields += (segment -> value)
      i = j
    }
    Json.obj(fields.result()*)
  }

  /** The signals as `(segments, value)` rows, sorted by path — the input both
    * nesting walks index into.
    */
  private def pathsOf[A](
      values: Map[SignalId, A]
  ): Array[(Array[String], A)] = {
    val out = new Array[(Array[String], A)](values.size)
    var i = 0
    values.foreach { case (k, v) =>
      out(i) = (k.segments, v)
      i += 1
    }
    // ONE comparator for every element type, because it only ever reads `._1`.
    // A `def` with the type parameter would be cast-free, but it hides a
    // singleton behind a call and pays a type parameter for a value that does
    // not depend on it.
    val order =
      pathOrder.asInstanceOf[PathOrder[A]] // scalafix:ok DisableSyntax
    java.util.Arrays.sort(out, order)
    out
  }

  /** Lexicographic over SEGMENTS, not over the dotted string.
    *
    * The two agree almost everywhere — `.` sorts below every alphanumeric, so
    * comparing whole strings usually reproduces the segment order — and that
    * near-miss is the trap. They diverge exactly when a segment contains a
    * character BELOW `.` (0x2E): `a-b.c` against `a.b` sorts one way by segment
    * and the other by string, because `-` (0x2D) beats the separator. The wire
    * bytes are asserted, so sorting the cheaper way would be a rare, silent
    * reordering. Pinned in `DatastarNestSuite`.
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

  /** The same values as a `data-signals` ATTRIBUTE — the inline seed that lets
    * an element carry its own signals, so a first paint, a host fill or a
    * member insert needs no frame to be correct (ADR 0017). `""` for no values,
    * which renders as no attribute at all.
    *
    * `data-signals` is compiled as a JS EXPRESSION by the pinned bundle
    * (`returnsValue: true`), not parsed as JSON, so this emits a JS object
    * literal with single-quoted values. Two nested contexts, two escapes, in
    * this order: the value sits in a JS string literal which sits in an HTML
    * attribute. HTML-escaping alone is not enough — `&#39;` decodes back to a
    * bare `'` and closes the literal early. The same pair `Server`'s popup seed
    * uses, and the reason this lives here rather than in every card template.
    */
  def signalsAttr(values: Map[SignalId, String]): String =
    if (values.isEmpty) ""
    else
      // LEADING SPACE, like `Renderer.cellClasses`: this is spliced straight
      // after a quoted attribute value, and `id="c"data-signals=…` is a parse
      // error browsers only recover from by accident.
      val sb = new java.lang.StringBuilder(32 + values.size * 48)
      sb.append(" data-signals=\"")
      nestJsInto(sb, pathsOf(values), 0, values.size, 0)
      sb.append('"')
      sb.toString

  /** A node's seed with its VALUES cut out: `chunks` is the literal text either
    * side of each hole, `order` the signal filling each one, so `chunks` is
    * always one longer than `order`.
    *
    * A node's signal NAMES are fixed by its plan — only the values move — so
    * the nesting, the sort and the path split are all plan-time work that
    * [[signalsAttr]] was redoing on every paint. Rendering a seed is then one
    * append per chunk and one escape per value.
    *
    * Not reachable for every seed: a member's is MERGED across its regions, and
    * a card whose subject is a transform has no fixed names at all
    * (`Renderer.resolveDirect`). Those keep [[signalsAttr]].
    */
  final class SignalSeed private[runtime] (
      val chunks: Array[String],
      val order: Array[SignalId]
  )

  /** Most nodes carry no signals at all, and every one of them wants the same
    * empty seed — one object for the process rather than two arrays per plan.
    */
  private val emptySeed = new SignalSeed(Array(""), Array.empty)

  /** The seed shape for a node whose signals are `names` — plan-time. */
  def seedFor(names: Iterable[SignalId]): SignalSeed =
    if (names.isEmpty) emptySeed
    else {
      // The values ARE the names here: the walk emits them in the order a
      // paint's values will be needed, and each one ends a chunk.
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

  /** [[nestJsInto]] with the leaf VALUES cut out into `order` and the literal
    * text between them accumulated into `chunks`.
    */
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

  /** Render `seed` with `values`, straight into `out`.
    *
    * Falls back to [[signalsAttr]] unless the values are exactly the signals
    * the seed was built for. The names cannot drift for a planned node — but a
    * mismatch would silently emit a seed of the wrong SHAPE, so this is checked
    * rather than assumed, and the check is one integer compare plus the lookups
    * the render needs anyway.
    */
  def seedAttrInto(
      out: java.lang.Appendable,
      seed: SignalSeed,
      values: Map[SignalId, String]
  ): Unit = {
    val n = seed.order.length
    // Resolved BEFORE anything is written, so a mismatch falls back without
    // having to unwind a destination that may be a stream.
    val resolved =
      if (values.isEmpty || values.size != n) null
      else {
        val vs = new Array[String](n)
        var i = 0
        while (i < n && vs != null) {
          values.get(seed.order(i)) match {
            case Some(v) => vs(i) = v; i += 1
            case None    => i = n + 1
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

  /** The seed's JS object literal, nested for the same reason [[nest]] is —
    * `data-signals` is compiled as an EXPRESSION, so `{a.b: 'x'}` is not even
    * valid syntax, let alone the same store shape a frame patches.
    *
    * Written INTO the caller's builder, over the same once-sorted rows [[nest]]
    * walks. See [[nest]] for why the per-level grouping went.
    */
  private def nestJsInto(
      sb: java.lang.StringBuilder,
      paths: Array[(Array[String], String)],
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
        escapeJsInto(sb, paths(i)._2)
        sb.append('\'')
      } else nestJsInto(sb, paths, i, j, depth + 1)
      i = j
    }
    val _ = sb.append('}')
  }

  /** The binding attribute for a signal slot — what `<slot>__bind` renders to
    * (ADR 0017). `""` where a value is not signal-backed, which is what keeps
    * the plain form genuinely plain.
    *
    * Every kind reads the signal BARE, with no expression around it, because
    * the value carries whatever it needs — a fill percentage arrives as
    * `39.37%`, a colour as `#ffb46b`. That is deliberate: an expression in the
    * attribute would be a second place a value's shape is decided, and the
    * authoring layer already decides it in the transform.
    *
    * `data-bind` is the odd one out and takes the signal's NAME rather than a
    * `$`-read, because it is two-way — it writes the signal back on input.
    */
  def binding(signal: SignalId, kind: SignalBind): String = kind match
    case SignalBind.Text            => s"""data-text="$$$signal""""
    case SignalBind.Bind            => s"""data-bind="$signal""""
    case SignalBind.Style(property) =>
      s"""data-style:$property="$$$signal""""
    case SignalBind.Attr(name) => s"""data-attr:$name="$$$signal""""
    // The bundle kebab-cases a `data-class` key (`P(e, n, "kebab")`), so a
    // class name is written as it appears in CSS and nowhere else.
    case SignalBind.Class(name) => s"""data-class:$name="$$$signal""""

  /** Both escapes of a seeded value, in ONE pass, straight into the builder.
    *
    * The value sits in a JS string literal which sits in an HTML attribute, so
    * both apply: `\` and `'` are what a single-quoted JS literal can be broken
    * by, and `&`, `<`, `"` are the attribute's. A bare `'` is deliberately NOT
    * turned into `&#39;` — that decodes back to `'` and closes the literal
    * early, which is the trap this method exists to avoid.
    *
    * One pass rather than five `String.replace` calls, each of which copied the
    * whole value again for a value that usually contains none of these.
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
