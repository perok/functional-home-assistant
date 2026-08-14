package fh.view.runtime

import fh.view.model.{SignalBind, SignalId}
import io.circe.Json
import org.http4s.ServerSentEvent

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
  def patchElements(fragment: String): ServerSentEvent =
    patch(fragment)

  /** A `datastar-patch-elements` event with an explicit `mode` and optional
    * target `selector`. `outer` (the default) morphs the element matching the
    * fragment's own id; `inner` replaces a target's children;
    * `append`/`prepend` add to a target's children (e.g. stacking a popup into
    * a mount) — these need a `selector`. See
    * https://data-star.dev/reference/sse_events.
    */
  def patch(
      fragment: String,
      mode: PatchMode = PatchMode.Outer,
      selector: Option[String] = None
  ): ServerSentEvent =
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
    * dynamic group path relies on that idempotency (a duplicate/late remove is
    * harmless).
    */
  def remove(selector: String): ServerSentEvent =
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
  private def sse(eventType: String, lines: List[String]): ServerSentEvent =
    ServerSentEvent(
      data = Some(lines.mkString("\ndata: ")),
      eventType = Some(eventType)
    )

  /** Collapse runs of whitespace containing a newline into a single space. */
  private def collapse(html: String): String =
    html.replaceAll("\\s*\\r?\\n\\s*", " ").trim

  /** A `datastar-patch-signals` event carrying a JSON object of signal updates.
    */
  def patchSignals(signalsJson: String): ServerSentEvent =
    ServerSentEvent(
      data = Some(s"signals $signalsJson"),
      eventType = Some("datastar-patch-signals")
    )

  /** Signal-slot values as the [[patchSignals]] payload. Sorted, so one frame's
    * bytes are a function of its contents and a test can name them.
    */
  def signalsJson(values: Map[SignalId, String]): String =
    Json
      .obj(values.toList.sortBy(_._1).map { case (k, v) =>
        (k: String) -> Json.fromString(v)
      }*)
      .noSpaces

  /** The same values as a `data-signals` ATTRIBUTE — the inline seed that lets
    * an element carry its own signals, so a first paint, a mount fill or a
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
      values.toList
        .sortBy(_._1)
        // LEADING SPACE, like `Renderer.cellClasses`: this is spliced straight
        // after a quoted attribute value, and `id="c"data-signals=…` is a parse
        // error browsers only recover from by accident.
        .map { case (k, v) => s"$k: '${escapeJs(v)}'" }
        .mkString(""" data-signals="{""", ", ", """}"""")

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
