package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.testkit.FakePage
import org.http4s.{Method, Request, ServerSentEvent, Uri}

import scala.concurrent.duration.*

/** One viewer of a [[TestServer]] for the model-based oracle: a [[FakePage]]
  * fed what the real server sends it.
  *
  * A stream is the route's: [[connect]] opens it with what the page's
  * `data-init` carries, applies the opening block up to its cursor (or a
  * reload) and lets go, so the session lingers. While [[up]], the connection
  * loop is played by hand: [[deliver]] pulls at the doorbell and takes the
  * session's backlog, which holds every frame its steps queued (pulls, choices,
  * popups) in the order they ran, as an attached stream would get them. While
  * down nothing is taken, so a reconnect gets the backlog first, as a real one
  * does.
  */
final class FakeBrowser private (
    ts: TestServer,
    private var current: FakePage
) {

  private var streaming = false

  def page: FakePage = current

  def up: Boolean = streaming

  def conn: String = current.conn

  /** The stream from the page's own request; a `_reload` it sets loads the page
    * again at its URL and connects that one.
    */
  def connect: IO[Unit] =
    ts.gatedApp
      .run(
        Request[IO](
          Method.GET,
          Uri.unsafeFromString("/" + current.streamRequest)
        )
      )
      .flatMap(
        _.body
          .through(ServerSentEvent.decoder[IO])
          .evalMap(e =>
            IO(current(e.eventType.getOrElse(""), e.data.getOrElse(""))).as(e)
          )
          .takeThrough(e =>
            !e.data.exists(d =>
              d.contains(Server.StoreVersionSignal) || d.contains(
                Server.ReloadSignal
              )
            )
          )
          .compile
          .drain
      )
      .timeout(15.seconds) *>
      IO { streaming = true } *>
      IO(current.reloadAsked).ifM(reload, IO.unit)

  private def reload: IO[Unit] =
    ts.page(current.query)
      .flatMap(html => IO { current = FakePage.load(html) }) *> connect

  /** The stream dropped; the session lingers until something forgets it. */
  def drop: IO[Unit] = IO { streaming = false }

  /** What the connection loop would have sent by now. */
  def deliver: IO[Unit] =
    IO.whenA(streaming)(
      ts.sessions
        .get(conn)
        .flatMap(_.traverse_ { s =>
          ts.server
            .liveSlug(ts.slug)
            .flatMap(live =>
              live.doorbell.get.flatMap(ts.server.pull(live, s, _))
            ) *> s.takeBacklog.flatMap(fs => IO(fs.foreach(current(_))))
        })
    )

  /** A press posting `body`'s signals with this page's `conn`; a refusal's
    * signals ride the answer.
    */
  def post(path: String): IO[Unit] =
    ts.postResult(path, body = s"""{"${Server.ConnSignal}":"$conn"}""")
      .flatMap((_, body) => IO(current.applySse(body)))

  def choose(key: VarKey, value: Option[String]): IO[Unit] =
    ts.choice(key, value).flatMap(post)

  def openPopup(id: String): IO[Unit] = post(s"sse/surface/${ts.slug}/open/$id")

  def closePopup: IO[Unit] = post(s"sse/popup/${ts.slug}/close")

  /** The page a refresh would show now, through a viewer that leaves at once so
    * it holds no floor for later steps.
    */
  def fresh: IO[FakePage] =
    ts.viewer(current.query)
      .flatTap(_.leave)
      .map(v => FakePage.load(v.document.html))

  /** The oracle's check: once delivered, this page equals a fresh one. */
  def check: IO[hedgehog.Result] =
    fresh.map(f =>
      FakePage.matches(current, f).log(s"conn $conn at ${current.query}")
    )
}

object FakeBrowser {

  /** A page load, then its stream, as a browser opening `query`. */
  def open(ts: TestServer, query: String = ""): IO[FakeBrowser] =
    ts.page(query)
      .map(html => new FakeBrowser(ts, FakePage.load(html)))
      .flatTap(_.connect)

  /** Every session reaped, every stream cut. */
  def forgetAll(ts: TestServer, browsers: List[FakeBrowser]): IO[Unit] =
    ts.forgetConnections *> browsers.traverse_(_.drop)
}
