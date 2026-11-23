package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Ix, TreeIndex}
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
  *
  * Every IO here reads the page when it RUNS (`IO.defer`): built ahead of the
  * step before it (`action *> b.listsAsFresh`), one that read `current.query`
  * at construction loaded the fresh page at the query from before the press.
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
  def connect: IO[Unit] = IO.defer {
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
  }

  private def reload: IO[Unit] = IO.defer {
    ts.page(current.query)
      .flatMap(html => IO { current = FakePage.load(html) }) *> connect
  }

  /** The stream dropped; the session lingers until something forgets it. */
  def drop: IO[Unit] = IO { streaming = false }

  /** What the connection loop would have sent by now. */
  def deliver: IO[Unit] = IO.defer {
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
  }

  /** A press posting `body`'s signals with this page's `conn`; a refusal's
    * signals ride the answer.
    */
  def post(path: String): IO[Unit] = IO.defer {
    ts.postResult(path, body = s"""{"${Server.ConnSignal}":"$conn"}""")
      .flatMap((_, body) => IO(current.applySse(body)))
  }

  def choose(key: VarKey, value: Option[String]): IO[Unit] =
    ts.choice(key, value).flatMap(post)

  def openPopup(id: String): IO[Unit] = post(s"sse/surface/${ts.slug}/open/$id")

  def closePopup: IO[Unit] = post(s"sse/popup/${ts.slug}/close")

  /** The page a refresh would show now, through a viewer that leaves at once so
    * it holds no floor for later steps.
    */
  def fresh: IO[FakePage] = IO.defer {
    ts.viewer(current.query)
      .flatTap(_.leave)
      .map(v => FakePage.load(v.document.html))
  }

  /** The oracle's check: once delivered, this page equals a fresh one, and each
    * host list its session holds is on the page and is the fresh page's own.
    */
  def check: IO[hedgehog.Result] =
    (fresh, listsInPage, listsAsFresh).mapN((f, inPage, asFresh) =>
      FakePage
        .matches(current, f)
        .and(inPage)
        .and(asFresh)
        .log(s"conn $conn at ${current.query}")
    )

  /** Each host this session holds a list for has the list a fresh load's
    * session holds, compared as [[Ix]] so a case's or clause's identity counts,
    * which the page's ids cannot show. A host it holds none for is allowed: a
    * forgotten page.
    */
  def listsAsFresh: IO[hedgehog.Result] = IO.defer {
    for {
      v <- ts.viewer(current.query)
      expected <- FakeBrowser.lists(v.session)
      _ <- v.leave
      held <- ts.sessions.get(conn).flatMap(_.traverse(FakeBrowser.lists))
    } yield {
      val wrong = held.toList.flatMap(_.toList).collect {
        case (host, shown) if !expected.get(host).contains(shown) =>
          s"$host: held $shown, fresh ${expected.get(host)}"
      }
      hedgehog.Result
        .assert(wrong.isEmpty)
        .log(
          wrong.mkString("host lists differ from a fresh load's:\n", "\n", "")
        )
    }
  }

  private def index: IO[TreeIndex] =
    ts.server
      .liveSlug(ts.slug)
      .flatMap(_.renderer.get)
      .map(_.rendererOf.fold(sys.error("no renderer"))(_.index))

  /** After a settle, each list this session holds is its host's children on the
    * page, in order.
    */
  def listsInPage: IO[hedgehog.Result] = IO.defer {
    (
      index,
      ts.sessions.get(conn).flatMap(_.traverse(FakeBrowser.lists))
    ).mapN { (index, held) =>
      val wrong = held.toList.flatMap(_.toList).collect {
        case (host, shown @ (first :: _))
            if current.siblingIds(index.id(first)) !=
              Some(shown.map(index.id)) =>
          s"${index.id(host)}: held ${shown.map(index.id)}, page ${current
              .siblingIds(index.id(first))}"
      }
      hedgehog.Result
        .assert(wrong.isEmpty)
        .log(wrong.mkString("host lists off the page:\n", "\n", ""))
    }
  }
}

object FakeBrowser {

  /** A page load, then its stream, as a browser opening `query`. */
  def open(ts: TestServer, query: String = ""): IO[FakeBrowser] =
    ts.page(query)
      .map(html => new FakeBrowser(ts, FakePage.load(html)))
      .flatTap(_.connect)

  private def lists(s: Session): IO[Map[Ix, List[Ix]]] =
    s.state.map(_.holds.flatMap((ix, h) => h.shows.map(l => ix -> l.toList)))

  /** Every session reaped, every stream cut. */
  def forgetAll(ts: TestServer, browsers: List[FakeBrowser]): IO[Unit] =
    ts.forgetConnections *> browsers.traverse_(_.drop)
}
