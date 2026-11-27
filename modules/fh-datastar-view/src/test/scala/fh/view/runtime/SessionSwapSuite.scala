package fh.view.runtime

import cats.effect.{IO, Resource}
import fh.view.build.{PklDump, Site}
import fh.view.testkit.PklWorkspace
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Status

import scala.concurrent.duration.*

/** A session whose dashboard is edited under it ([[Server]]'s `swapAnswer`):
  * its next step repaints under the new renderer, and the choices that one no
  * longer takes are checked once ([[Renderer.recheck]]) and fall back.
  */
class SessionSwapSuite extends CatsEffectSuite {

  private def site(tabs: String*) =
    s"""amends "@fh-dashboard/site.pkl"
       |import "@fh-dashboard/components.pkl" as c
       |dashboards {
       |  ["dash"] {
       |    card = (c.tabs) {
       |      tabs {
       |${tabs
        .map(t => s"""        ["$t"] { c.title("tab $t") }""")
        .mkString("\n")}
       |      }
       |    }
       |  }
       |}
       |""".stripMargin

  private def booted(source: String): Resource[IO, (os.Path, TestServer)] =
    for {
      ws <- IO.blocking(os.temp.dir(prefix = "fh-swap")).toResource
      _ <- IO.blocking {
        val _ = PklWorkspace.bootstrap(
          ws,
          PklDump.render(
            Json.obj(
              "areas" -> Json.obj(),
              "floors" -> Json.obj(),
              "entities" -> Json.obj()
            )
          )
        )
        os.write.over(ws / Site.EntryFile, source)
      }.toResource
      ts <- TestServer.ofWorkspace(ws)
    } yield (ws, ts._1)

  private def installed(ts: TestServer): IO[Renderer] =
    ts.server
      .liveSlug(ts.slug)
      .flatMap(_.renderer.get)
      .flatMap(s => IO.fromOption(s.rendererOf)(IllegalStateException(s"$s")))

  test(
    "a live session's choice the edit refuses falls back once, on a repaint"
  ) {
    booted(site("A", "B")).use { case (ws, ts) =>
      for {
        before <- installed(ts)
        key = before.vars.defaults.keys
          .find(_.name == "tab")
          .getOrElse(fail(s"no tab: ${before.vars.defaults}"))
        viewer <- ts.viewer()
        chose <- ts.choose(viewer.document.conn, key, Some("1"))
        _ <- viewer.pull
        _ <- ts.edit(ws / Site.EntryFile, site("A"))
        after <- fs2.Stream
          .repeatEval(installed(ts) <* IO.sleep(10.millis))
          .find(_.generation ne before.generation)
          .compile
          .lastOrError
          .timeout(15.seconds)
        frames <- viewer.pull
        state <- viewer.session.state
        again <- viewer.pull
      } yield {
        assertEquals(chose._1, Status.NoContent, chose)
        assertEquals(before.headHash, after.headHash)
        val sent = frames.flatMap(_.data).mkString("\n")
        assert(!sent.contains(Server.ReloadSignal), sent)
        assert(sent.contains("tab A") && !sent.contains("tab B"), sent)
        // The page's committed signal follows the fallback.
        assert(sent.contains(key.committedSignal), sent)
        assertEquals(state.vars, Map.empty)
        assert(state.checkedBy.exists(_ eq after.generation), state.checkedBy)
        assertEquals(state.painter, Painter.By(after.generation))
        assertEquals(again, Nil)
      }
    }
  }
}
