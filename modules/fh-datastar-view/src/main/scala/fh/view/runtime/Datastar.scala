package fh.view.runtime

import org.http4s.ServerSentEvent

/** Datastar SSE protocol framing.
  *
  * See https://data-star.dev/reference/sse_events — pin the client bundle
  * version to match (event names / `data-*` syntax have changed across
  * releases).
  */
object Datastar {

  /** A `datastar-patch-elements` event. The fragment's root element must carry
    * an `id`; Datastar morphs the matching element in place (default `outer`
    * mode).
    *
    * Datastar reads the HTML from a single `data: elements …` line. http4s puts
    * a `data:` prefix on the first line only and emits embedded newlines
    * verbatim, so a multi-line fragment's continuation lines are dropped by the
    * SSE parser (truncating the patch). We therefore collapse inter-tag
    * newlines/indentation — multiline templates are just authoring sugar; the
    * wire form must be one line. (Attribute values already never span lines.)
    */
  def patchElements(fragment: String): ServerSentEvent =
    ServerSentEvent(
      data = Some("elements " + collapse(fragment)),
      eventType = Some("datastar-patch-elements")
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
