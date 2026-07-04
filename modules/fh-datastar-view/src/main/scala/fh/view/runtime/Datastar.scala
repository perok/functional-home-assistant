package fh.view.runtime

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
}
