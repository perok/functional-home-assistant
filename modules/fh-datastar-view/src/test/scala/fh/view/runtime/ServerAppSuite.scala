package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fh.view.build.SystemPkl
import fh.view.testkit.{FakeHomeAssistant, PklWorkspace}
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

/** The startup path (objectives 2, 3 of the failed-dashboard plan): an
  * all-broken workspace still boots, every failed entry is REGISTERED (its ref
  * holds `Failed`, serving the error page), and the configured default stays
  * the default even when it is the broken one. Real eval path — the same
  * `prepareRenderers` -> `liveServer` sequence production's `run` uses.
  */
class ServerAppSuite extends munit.CatsEffectSuite {

  test("defaultSlugFrom: the configured default wins, even a failed one") {
    // Objective 3 — a broken configured default STAYS the default (its error
    // page is the fix path), it is not silently swapped for a dashboard that
    // built.
    assertEquals(
      ServerApp.defaultSlugFrom(Some("broken"), List("a", "broken"), List("a")),
      "broken"
    )
    // ...and beats even a built "dashboard".
    assertEquals(
      ServerApp.defaultSlugFrom(Some("b"), List("b", "dashboard"), List("dashboard")),
      "b"
    )
    // A configured default that names no entry falls through to the normal
    // preference order.
    assertEquals(
      ServerApp.defaultSlugFrom(Some("nope"), List("a", "b"), List("a", "b")),
      "a"
    )
  }

  test("defaultSlugFrom: a built dashboard wins over a merely-discovered one") {
    assertEquals(
      ServerApp.defaultSlugFrom(None, List("a", "dashboard", "c"), List("a", "dashboard")),
      "dashboard"
    )
    // No built "dashboard": the entry NAMED "dashboard" wins even when broken.
    assertEquals(
      ServerApp.defaultSlugFrom(None, List("a", "dashboard"), List("a")),
      "dashboard"
    )
    // No "dashboard" at all: the first BUILT slug, then the first entry.
    assertEquals(ServerApp.defaultSlugFrom(None, List("b", "a"), List("a")), "a")
    // All-fail: the first entry, so the root still serves something editable.
    assertEquals(ServerApp.defaultSlugFrom(None, List("b", "a"), Nil), "b")
  }

  test("prepareRenderers on an all-failed workspace returns the failures") {
    allFailed.use { case (prepared, _) =>
      IO {
        assertEquals(prepared.built, Nil)
        assertEquals(prepared.failed.map(_._1), List("a", "b"))
        assert(prepared.failed.forall(_._2.nonEmpty))
      }
    }
  }

  test("an all-failed workspace still boots: error page at / and /d/:slug") {
    allFailed.use { case (prepared, app) =>
      val failedMsg = prepared.failed.collectFirst { case ("a", m) => m }.get
      for {
        root <- get(app, "/")
        broken <- get(app, "/d/a")
        unknown <- get(app, "/d/unknown")
        base <- get(app, "/system/pkl/base.pkl")
        edit <- get(app, "/edit")
      } yield {
        // The root default is a failed entry (all-fail -> first entry "a") and
        // serves its error page — the root must not 404.
        assertEquals(root._1, Status.Ok)
        assert(root._2.contains("failed to build"), clue = root._2)
        assertEquals(broken._1, Status.Ok)
        assert(broken._2.contains("Dashboard a failed to build"), clue = broken._2)
        assert(broken._2.contains(htmlEscape(failedMsg)), clue = broken._2)
        // Unknown slugs are still 404, exactly as with a healthy server.
        assertEquals(unknown._1, Status.NotFound)
        // The surrounding surface keeps answering: /system/pkl and the editor.
        assertEquals(base._1, Status.Ok)
        assertEquals(edit._1, Status.Ok)
      }
    }
  }

  /** Stage an all-broken workspace (the real eval path: bootstrap ->
    * `prepareRenderers` -> `liveServer`) and compose the production route set
    * (server + editor) over it.
    */
  private def allFailed
      : Resource[IO, (ServerApp.Prepared, HttpApp[IO])] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-all-failed")).toResource
      _ <- IO.blocking {
        // Bootstrap a package-form workspace (lib + dump packages seeded), then
        // replace its entries with two that cannot possibly evaluate.
        val _ = PklWorkspace.bootstrap(tmp)
        os.list(tmp)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .foreach(os.remove)
        os.write.over(tmp / "a.pkl", "this is not valid pkl")
        os.write.over(tmp / "b.pkl", "neither is this")
      }.toResource
      fake <- FakeHomeAssistant.create(Nil).toResource
      feed <- HaFeed.resource(connect(fake))
      prepared <- ServerApp.prepareRenderers(feed, tmp, None).toResource
      stateOf = prepared.failed.map { case (slug, message) =>
        slug -> Server.RendererState.Failed(message)
      }.toMap
      refs <- prepared.entries
        .traverse { case (slug, _) =>
          SignallingRef[IO].of(stateOf(slug)).map(slug -> _)
        }
        .map(_.toMap)
        .toResource
      default = ServerApp.defaultSlugFrom(None, prepared.entries.map(_._1), Nil)
      server <- ServerApp.liveServer(
        feed,
        refs,
        default,
        systemPkl = SystemPkl.fromDisk(tmp)
      )
      editor = new EditorRoutes(tmp, None, default).routes(null)
    } yield (prepared, (server.routes <+> editor).orNotFound)

  private def connect(fake: FakeHomeAssistant): HaFeed.Connect =
    Resource.pure((fake, IO.never[Unit]))

  private def get(app: HttpApp[IO], path: String): IO[(Status, String)] =
    app
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .flatMap(resp =>
        resp.body.through(fs2.text.utf8.decode).compile.string.map(resp.status -> _)
      )

  private def htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
