package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fh.view.build.{Site, SystemPkl}
import fh.view.testkit.{FakeHomeAssistant, PklWorkspace}
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

/** The startup path (objectives 2, 3 of the failed-dashboard plan): a workspace
  * whose entrypoint cannot evaluate still boots, the failure is REGISTERED
  * (serving the error page at `/`), and the site's own `default` decides which
  * slug that is. Real eval path — the same `prepareRenderers` -> `liveServer`
  * sequence production's `run` uses.
  */
class ServerAppSuite extends munit.CatsEffectSuite {

  test("defaultSlugFrom: the site's default wins, even a failed one") {
    // Objective 3 — a broken default STAYS the default (its error page is the
    // fix path), it is not silently swapped for a dashboard that built.
    assertEquals(
      ServerApp.defaultSlugFrom(Some("broken"), List("a", "broken")),
      "broken"
    )
    // ...and beats even a built "dashboard".
    assertEquals(
      ServerApp.defaultSlugFrom(Some("b"), List("b", "dashboard")),
      "b"
    )
    // A default that names no dashboard falls through to the normal
    // preference order.
    assertEquals(
      ServerApp.defaultSlugFrom(Some("nope"), List("a", "b")),
      "a"
    )
  }

  test("defaultSlugFrom: membership order, never build status") {
    // The dashboard NAMED "dashboard" wins even when broken: a failed one
    // serves its error page, so there is nothing to prefer a buildable one for.
    assertEquals(
      ServerApp.defaultSlugFrom(None, List("a", "dashboard", "c")),
      "dashboard"
    )
    // No "dashboard" at all: the first slug, built or not.
    assertEquals(ServerApp.defaultSlugFrom(None, List("b", "a")), "a")
    // Nothing at all — a site that never evaluated: the name the boot
    // registers its failure under, so `/` still serves the error page.
    assertEquals(ServerApp.defaultSlugFrom(None, Nil), Server.DefaultSlug)
  }

  test("a broken entrypoint registers one failed dashboard") {
    allFailed.use { case (prepared, _) =>
      IO {
        assertEquals(prepared.built, Nil)
        // Nothing evaluated, so nothing can be attributed to a slug: ONE
        // failure, under the name the root looks for.
        assertEquals(prepared.failed.map(_._1), List(Server.DefaultSlug))
        assert(prepared.failed.forall(_._2.nonEmpty))
      }
    }
  }

  test("a broken entrypoint still boots: error page at / and /d/:slug") {
    allFailed.use { case (prepared, app) =>
      val failedMsg = prepared.failed.head._2
      for {
        root <- get(app, "/")
        broken <- get(app, s"/d/${Server.DefaultSlug}")
        unknown <- get(app, "/d/unknown")
        base <- get(app, "/system/pkl/base.pkl")
        edit <- get(app, "/edit")
      } yield {
        // The root serves the failure's error page — it must not 404.
        assertEquals(root._1, Status.Ok)
        assert(root._2.contains("failed to build"), clue = root._2)
        assertEquals(broken._1, Status.Ok)
        assert(broken._2.contains(htmlEscape(failedMsg)), clue = broken._2)
        // Unknown slugs are still 404, exactly as with a healthy server.
        assertEquals(unknown._1, Status.NotFound)
        // The surrounding surface keeps answering: /system/pkl and the editor.
        assertEquals(base._1, Status.Ok)
        assertEquals(edit._1, Status.Ok)
      }
    }
  }

  test("a legacy pre-entrypoint dashboard.pkl says how to migrate") {
    // No automatic migration (the file is the user's), so the diagnostic IS
    // the instructions — and it reaches the user as the error page at `/`.
    staged(
      """amends "@fh-dashboard/entry.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |card = c.title("hi")
        |""".stripMargin
    ).use { case (prepared, app) =>
      get(app, "/").map { case (status, body) =>
        assertEquals(status, Status.Ok)
        val message = prepared.failed.head._2
        assert(message.contains("has no `dashboards`"), clue = message)
        assert(message.contains("@fh-dashboard/site.pkl"), clue = message)
        assert(body.contains("failed to build"), clue = body)
      }
    }
  }

  /** Stage a workspace whose entrypoint cannot evaluate at all. */
  private def allFailed: Resource[IO, (ServerApp.Prepared, HttpApp[IO])] =
    staged("this is not valid pkl")

  /** Stage a workspace carrying `entrypoint` (the real eval path: bootstrap ->
    * `prepareRenderers` -> `liveServer`) and compose the production route set
    * (server + editor) over it.
    */
  private def staged(
      entrypoint: String
  ): Resource[IO, (ServerApp.Prepared, HttpApp[IO])] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-all-failed")).toResource
      _ <- IO.blocking {
        // Bootstrap a package-form workspace (lib + dump packages seeded), then
        // replace its entrypoint with the one under test.
        val _ = PklWorkspace.bootstrap(tmp)
        os.write.over(tmp / Site.EntryFile, entrypoint)
      }.toResource
      fake <- FakeHomeAssistant.create(Nil).toResource
      feed <- HaFeed.resource(connect(fake))
      prepared <- ServerApp.prepareRenderers(feed, tmp, None).toResource
      refs <- prepared.states.toList
        .traverse { case (slug, state) =>
          SignallingRef[IO].of(state).map(slug -> _)
        }
        .map(_.toMap)
        .toResource
      site <- Server.LiveSite
        .of(
          refs,
          ServerApp.defaultSlugFrom(prepared.default, refs.keys.toList)
        )
        .toResource
      server <- ServerApp.liveServer(
        feed,
        site,
        systemPkl = SystemPkl.fromDisk(tmp)
      )
      editor = new EditorRoutes(tmp, None, site.defaultSlug, site.names)
        .routes(null)
    } yield (prepared, (server.routes <+> editor).orNotFound)

  private def connect(fake: FakeHomeAssistant): HaFeed.Connect =
    Resource.pure((fake, IO.never[Unit]))

  private def get(app: HttpApp[IO], path: String): IO[(Status, String)] =
    app
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .flatMap(resp =>
        resp.body
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .map(resp.status -> _)
      )

  private def htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
