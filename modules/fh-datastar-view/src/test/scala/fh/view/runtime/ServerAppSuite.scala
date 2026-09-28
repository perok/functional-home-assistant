package fh.view.runtime

import cats.effect.{IO, Resource}
import fh.view.build.Site
import fh.view.testkit.PklWorkspace
import org.http4s.*

/** The startup path: a workspace whose entrypoint cannot evaluate still boots,
  * registers the failure (its error page at `/`), and the site's `default`
  * picks the slug. Through [[ServerApp.assemble]].
  */
class ServerAppSuite extends munit.CatsEffectSuite {

  test("workspaceDir: the argument wins, the env is the fallback") {
    assertEquals(
      ServerApp.workspaceDir(List("scratch/ws"), None),
      Right("scratch/ws")
    )
    assertEquals(
      ServerApp.workspaceDir(List("scratch/ws"), Some("/from/env")),
      Right("scratch/ws")
    )
    assertEquals(
      ServerApp.workspaceDir(Nil, Some("/from/env")),
      Right("/from/env")
    )
  }

  test("workspaceDir: a workspace is never guessed") {
    // No default: a relative fallback booted green on a fresh empty workspace,
    // so a mistyped path looked like it worked.
    List(None, Some("")).foreach { env =>
      val none = ServerApp.workspaceDir(Nil, env)
      assert(none.isLeft, clue = env)
      assert(none.left.exists(_.contains("DASHBOARDS_DIR")), clue = none)
    }

    // Refused, not ignored: an unquoted path with a space, or a glob matching
    // two directories, served a workspace nobody named.
    val two = ServerApp.workspaceDir(List("my", "workspace"), None)
    assert(two.isLeft, clue = two)
    // The shell already ate the quoting, so the message names what it got.
    assert(two.left.exists(_.contains("my, workspace")), clue = two)
  }

  test("defaultSlugFrom: the site's default wins, even a failed one") {
    // A broken default stays the default: its error page is the fix path.
    assertEquals(
      ServerApp.defaultSlugFrom(Some("broken"), List("a", "broken")),
      "broken"
    )
    assertEquals(
      ServerApp.defaultSlugFrom(Some("b"), List("b", "dashboard")),
      "b"
    )
    assertEquals(
      ServerApp.defaultSlugFrom(Some("nope"), List("a", "b")),
      "a"
    )
  }

  test("defaultSlugFrom: membership order, never build status") {
    // A failed one serves its error page, so nothing is gained by preferring a
    // buildable one.
    assertEquals(
      ServerApp.defaultSlugFrom(None, List("a", "dashboard", "c")),
      "dashboard"
    )
    assertEquals(ServerApp.defaultSlugFrom(None, List("b", "a")), "a")
    // A site that never evaluated: the name its failure is registered under.
    assertEquals(ServerApp.defaultSlugFrom(None, Nil), Server.DefaultSlug)
  }

  test("a broken entrypoint registers one failed dashboard") {
    allFailed.use { case (prepared, _) =>
      IO {
        assertEquals(prepared.built, Nil)
        // Nothing evaluated, so one failure under the name the root looks for.
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
        assertEquals(root._1, Status.Ok)
        assert(root._2.contains("failed to build"), clue = root._2)
        assertEquals(broken._1, Status.Ok)
        assert(broken._2.contains(htmlEscape(failedMsg)), clue = broken._2)
        assertEquals(unknown._1, Status.NotFound)
        assertEquals(base._1, Status.Ok)
        assertEquals(edit._1, Status.Ok)
      }
    }
  }

  test("a site.pkl that is really a dashboard says what it should be") {
    // Rare after the `site.pkl` rename, but reachable by hand, and then the
    // diagnostic is the instructions, shown as the error page at `/`.
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

  private def allFailed: Resource[IO, (ServerApp.Prepared, HttpApp[IO])] =
    staged("this is not valid pkl")

  private def staged(
      entrypoint: String
  ): Resource[IO, (ServerApp.Prepared, HttpApp[IO])] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-all-failed")).toResource
      _ <- IO.blocking {
        val _ = PklWorkspace.bootstrap(tmp)
        os.write.over(tmp / Site.EntryFile, entrypoint)
      }.toResource
      booted <- TestServer.ofWorkspace(tmp)
    } yield (booted._2, booted._1.gatedApp)

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
