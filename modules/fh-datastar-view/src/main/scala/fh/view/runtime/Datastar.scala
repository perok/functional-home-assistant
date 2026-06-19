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
    */
  def patchElements(fragment: String): ServerSentEvent =
    ServerSentEvent(
      data = Some(s"elements $fragment"),
      eventType = Some("datastar-patch-elements")
    )

  /** A `datastar-patch-signals` event carrying a JSON object of signal updates.
    */
  def patchSignals(signalsJson: String): ServerSentEvent =
    ServerSentEvent(
      data = Some(s"signals $signalsJson"),
      eventType = Some("datastar-patch-signals")
    )
}
