package fh.view.runtime

import fh.view.model.{SignalBind, SignalId}
import io.circe.Json
import org.http4s.{EntityEncoder, ServerSentEvent}
import org.http4s.EntityEncoder

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

/** One SSE event, encoded to its WIRE BYTES once — at construction.
  *
  * http4s's SSE encoder (`ServerSentEvent.encoder`) rendered every event per
  * connection: `renderString` built the event text into a growing
  * `StringWriter` and UTF-8 encoded it, per client per frame — but the bytes
  * are identical for every client that receives a given frame (a tick's patches
  * and cursor are minted once per slug in the changelog, and every session's
  * `encode` is offered the same ones). So the encoding happens HERE, where the
  * event is born, and the socket writes what is already in hand.
  *
  * Byte-identical to the old path BY CONSTRUCTION: the bytes are the same
  * `renderString` output, UTF-8, and the response's entity encoder is the same
  * `entityBody` + `text/event-stream` pair the old implicit was built from (the
  * cursor and keep-alive streams interleave with nothing else on the socket, so
  * framing is the only contract).
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
    * render + UTF-8. Available wherever frames are served (Server + tests).
    */
  implicit def sseFrameStreamEncoder[F[_]]
      : EntityEncoder[F, fs2.Stream[F, SseFrame]] =
    EntityEncoder
      .entityBodyEncoder[F]
      .contramap[fs2.Stream[F, SseFrame]](
        _.flatMap(f => fs2.Stream.chunk(fs2.Chunk.array(f.bytes)))
      )
      .withContentType(
        org.http4s.headers
          .`Content-Type`(org.http4s.MediaType.`text/event-stream`)
      )

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
    nest(
      values.toList.map((k, v) => k.segments -> v)
    ).noSpaces

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
    * Keys are sorted at every level so a frame's bytes are deterministic.
    */
  private def nest(entries: List[(List[String], Json)]): Json =
    Json.obj(
      entries
        .groupBy(_._1.head)
        .toList
        .sortBy(_._1)
        .map { case (segment, rows) =>
          val deeper =
            rows.collect { case (_ :: rest, v) if rest.nonEmpty => rest -> v }
          // A path that is both a leaf and a prefix cannot arise from
          // `Renderer.signalName` — every path it mints has the same depth.
          segment -> (if (deeper.isEmpty) rows.head._2 else nest(deeper))
        }*
    )

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
      s""" data-signals="${nestJs(
          values.toList.map((k, v) => k.segments -> v)
        )}""""

  /** The seed's JS object literal, nested for the same reason [[nest]] is —
    * `data-signals` is compiled as an EXPRESSION, so `{a.b: 'x'}` is not even
    * valid syntax, let alone the same store shape a frame patches.
    */
  private def nestJs(entries: List[(List[String], String)]): String =
    entries
      .groupBy(_._1.head)
      .toList
      .sortBy(_._1)
      .map { case (segment, rows) =>
        val deeper = rows.collect {
          case (_ :: rest, v) if rest.nonEmpty => rest -> v
        }
        val value =
          if (deeper.isEmpty) s"'${escapeJs(rows.head._2)}'" else nestJs(deeper)
        s"$segment: $value"
      }
      .mkString("{", ", ", "}")

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

  /** Backslash and single quote — everything a single-quoted JS string literal
    * can be broken by. The attribute is double-quoted, so `"` needs no JS
    * escape; [[escapeHtmlAttr]] handles it.
    */
  private def escapeJs(s: String): String =
    escapeHtmlAttr(s.replace("\\", "\\\\").replace("'", "\\'"))

  /** `'` is deliberately NOT escaped: the delimiters of the JS string literals
    * above have to survive into the browser, and a value's own quote was
    * already backslashed by [[escapeJs]] before this runs.
    */
  private def escapeHtmlAttr(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
