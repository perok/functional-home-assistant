package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.model.{CardDef, Dashboard, Region, Theme}
import fh.view.testkit.DashboardBuilders.{col, component, lit}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** That the document still streams once it is a RESPONSE.
  *
  * [[SinkStreamingSuite]] proves the walk hands its bytes down incrementally,
  * but it stops at the `Writer`. Everything after that — `readOutputStream`,
  * the entity encoder, the response http4s builds — could still collect the
  * body and hand it over whole, which would score identically in every
  * benchmark and lose the peak this path exists for.
  */
class PageStreamRouteSuite extends ServerHarness {

  // A document fetch cannot run under `TestControl` — see
  // [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  // Several chunks wide, or "it came in one piece" and "it is smaller than one
  // chunk" are the same observation.
  private val Leaves = 400

  private val wideDash = Dashboard(
    cards = Map(
      "leaf" -> CardDef("""<div class="leaf">{{v}}</div>""", slots = List("v")),
      "col" -> CardDef(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region())
      )
    ),
    card = col(
      (1 to Leaves).map(i =>
        component("leaf", "v" -> lit(s"value-$i-" + "x" * 40))
      )*
    ),
    theme = Theme(styles = ".x{color:red}\n" * 200)
  )

  test("a page response is a stream of chunks, not one buffered body") {
    (for {
      store <- StateStore.inMemory(Map.empty)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(wideDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/d/dashboard"))
            .flatMap { res =>
              res.body.chunks
                .map(_.size)
                .compile
                .toList
                .map((res.contentLength, _))
            }
        }
    } yield out).timeout(30.seconds).map { case (length, chunks) =>
      val total = chunks.sum
      assert(
        total > 4 * Server.PageChunkBytes,
        s"fixture too small to prove anything: $total bytes"
      )
      // A `Content-Length` means the whole body was known before it was sent,
      // which is exactly what streaming gives up.
      assertEquals(length, None)
      assert(
        chunks.length > 1,
        s"the whole document arrived in ${chunks.length} chunk(s) — " +
          "something downstream of the walk is buffering the page"
      )
      assert(
        chunks.forall(_ <= Server.PageChunkBytes),
        s"chunk of ${chunks.max} B exceeds ${Server.PageChunkBytes}"
      )
    }
  }
}
