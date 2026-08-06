//> using file fh.sc

// Tests for the fh script's functions, called directly through the `fh`
// script-wrapper namespace (weaver-test rides in on the script's
// `using toolkit` directive — the typelevel toolkit's test scope IS weaver
// from 0.2.0 on). Run from scripts/:
//
//   scala-cli test .
//
// Referencing any fh member executes the whole wrapper body, so the script
// gates its dispatcher behind SCALA_TEST_MODE (env or system property — the
// property below covers a run that forgot the env var). The decline wiring
// (--help, unknown-subcommand usage) is deliberately untested: that layer is
// decline's. The instance-facing flows (init, push, pull against the real
// routes) live in UseCaseSuite, which has the backend to talk to; here we
// cover what the script does WITHOUT an instance.

import cats.data.NonEmptyList
import cats.effect.{IO, Ref, Resource}
import org.http4s.{MediaType, Method}
import weaver.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object FhScriptSuite extends SimpleIOSuite:
  System.setProperty("SCALA_TEST_MODE", "true")

  private val emptyDir = IO.blocking(Files.createTempDirectory("fh-test-"))

  test("outside a workspace, instanceUrl dies with the init hint") {
    // What `fh pull`/`push` hit first in a directory that was never init'ed.
    // basePkl is cwd-relative and the suite's cwd is not a workspace.
    fh.instanceUrl.attempt.map {
      case Left(fh.Die(msg)) =>
        expect.all(
          clue(msg).contains("not an fh workspace"),
          msg.contains("fh init")
        )
      case other => failure(s"expected Die, got: $other")
    }
  }

  test("backupPath: dated sibling, disambiguated on collision") {
    emptyDir.map { dir =>
      val original = dir.resolve("fh")
      val dated = fh.backupPath(original)
      Files.createFile(dated)
      val second = fh.backupPath(original)
      expect.all(
        clue(dated.getFileName.toString) ==
          s"fh.backup.${java.time.LocalDate.now}",
        dated.getParent == dir,
        clue(second.getFileName.toString)
          .startsWith(s"fh.backup.${java.time.LocalDate.now}-"),
        second != dated
      )
    }
  }

  test("writeLspFix: writes, no-ops when current, backs up a foreign file") {
    // The three states of ~/.pkl/settings.pkl: absent (write), already ours
    // (no-op, no backup), someone else's (dated backup, then write) — the
    // user-file convention, since it is the user's global pkl config.
    val url = "http://ha.local:8080"
    def backups(dir: Path): List[Path] = Files
      .list(dir)
      .iterator()
      .asScala
      .toList
      .filter(_.getFileName.toString.startsWith("settings.pkl.backup."))
    emptyDir.flatMap { dir =>
      val settings = dir.resolve("settings.pkl")
      for
        _ <- fh.writeLspFix(settings, url)
        afterWrite = Files.readString(settings)
        _ <- fh.writeLspFix(settings, url)
        noopBackups = backups(dir)
        _ <- IO.blocking(
          Files.write(settings, "// the user's own settings\n".getBytes(UTF_8))
        )
        _ <- fh.writeLspFix(settings, s"$url/") // trailing slash normalized
        replacedBackups = backups(dir)
      yield expect.all(
        clue(afterWrite).contains(
          s"""["https://fh.invalid/"] = "$url/system/pkl/packages/""""
        ),
        afterWrite.startsWith("amends \"pkl:settings\""),
        noopBackups.isEmpty,
        replacedBackups.size == 1,
        Files
          .readString(replacedBackups.head)
          .contains("the user's own settings"),
        Files.readString(settings) == afterWrite
      )
    }
  }

  test("targets: a slug per entry, and --slug only names one") {
    // `push` takes several entries, each landing on its own filename; `--slug`
    // renames ONE, so it is rejected rather than quietly applied to the last.
    val many = NonEmptyList.of("a.pkl", "sub/b.pkl")
    for
      defaults <- fh.targets(many, None)
      renamed <- fh.targets(NonEmptyList.one("a.pkl"), Some("other"))
      ambiguous <- fh.targets(many, Some("other")).attempt
    yield expect.same(
      List("a" -> "a.pkl", "b" -> "sub/b.pkl"),
      defaults.toList.map(t => t.slug -> t.entry.toString)
    ) and expect.same(
      List("other" -> "a.pkl"),
      renamed.toList.map(t => t.slug -> t.entry.toString)
    ) and (ambiguous match
      case Left(fh.Die(msg)) => expect(clue(msg).contains("--slug names one"))
      case other             => failure(s"expected Die, got: $other"))
  }

  test("watchSources: fires on a *.pkl edit, ignores dot-directories") {
    // What `push --watch` sits in. The stamp is size+mtime, so the edit below
    // changes the size; the `.fh/` write must NOT wake it (that dir is where
    // the workspace's own machine files churn).
    emptyDir.flatMap { dir =>
      val entry = dir.resolve("a.pkl")
      def write(p: Path, s: String) = IO.blocking {
        Files.createDirectories(p.getParent)
        Files.write(p, s.getBytes(UTF_8))
      }
      for
        _ <- write(entry, "one")
        fired <- IO.ref(0)
        watching <- fh
          .watchSources(fired.update(_ + 1), dir)
          .start

        _ <- write(dir.resolve(".fh").resolve("pins.json"), "{}")
        _ <- IO.sleep(3 * fh.pollInterval)
        afterHidden <- fired.get

        _ <- write(entry, "one and more")
        _ <- awaitCount(fired, 1)

        // A file created after the watch started counts too — the source set is
        // re-scanned every tick, not fixed at startup.
        _ <- write(dir.resolve("b.pkl"), "two")
        _ <- awaitCount(fired, 2)
        _ <- watching.cancel
      yield expect.same(0, afterHidden)
    }
  }

  test("post: a rejection carries the instance's own message") {
    // The failure mode that matters for push: the server puts the validation
    // message in the body (the pushing author reads no server log), so it has
    // to reach the terminal rather than being flattened to a bare status.
    stubServer("nosuchcard is not a card", 400).use { url =>
      for rejected <- fh
          .withClient(
            fh.post(
              _,
              url,
              Method.POST,
              "{}",
              MediaType.application.json,
              "push"
            )
          )
          .attempt
      yield rejected match
        case Left(fh.Die(msg)) =>
          expect.all(
            clue(msg).contains("push failed"),
            msg.contains("400"),
            msg.contains("nosuchcard is not a card")
          )
        case other => failure(s"expected Die, got: $other")
    }
  }

  /** Wait for `ref` to reach `n` — polled, so the test costs one tick rather
    * than a guessed sleep, and hangs (weaver's own timeout) if it never does.
    */
  private def awaitCount(ref: Ref[IO, Int], n: Int): IO[Unit] =
    ref.get.flatMap(current =>
      IO.unlessA(current >= n)(IO.sleep(fh.pollInterval) *> awaitCount(ref, n))
    )

  /** A one-response HTTP stub (the JDK's own server — no extra dependency),
    * yielding its URL.
    */
  private def stubServer(body: String, status: Int): Resource[IO, String] =
    Resource
      .make(IO.blocking {
        val bytes = body.getBytes(UTF_8)
        val server = com.sun.net.httpserver.HttpServer
          .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/",
          exchange =>
            exchange.sendResponseHeaders(status, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
            exchange.close()
        )
        server.start()
        server
      })(server => IO.blocking(server.stop(0)))
      .map(server => s"http://127.0.0.1:${server.getAddress.getPort}/")

  test("update: sha-compare against the remote copy, replace with a backup") {
    // cmdUpdate is parameterized (self path + source URL) precisely so this
    // can run in-process against a copy and a local stub, not the real script
    // and GitHub. First run replaces the file (dated backup kept — the
    // user-file convention); second run is a no-op because local now matches
    // remote. The stub is the JDK's own HttpServer: no extra dependencies.
    val original = "// some previous revision of the script\n".getBytes(UTF_8)
    val next = "// a newer revision\n".getBytes(UTF_8)

    val stub = cats.effect.Resource.make(IO.blocking {
      val server = com.sun.net.httpserver.HttpServer
        .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/fh",
        exchange =>
          exchange.sendResponseHeaders(200, next.length.toLong)
          exchange.getResponseBody.write(next)
          exchange.close()
      )
      server.start()
      server
    })(server => IO.blocking(server.stop(0)))

    def backupsIn(dir: Path): IO[List[Path]] = IO.blocking(
      Files
        .list(dir)
        .iterator()
        .asScala
        .toList
        .filter(_.getFileName.toString.startsWith("fh.backup."))
    )

    stub.use { server =>
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/fh"
      for
        dir <- emptyDir
        copy <- IO.blocking {
          val copy = Files.write(dir.resolve("fh"), original)
          Files.setPosixFilePermissions(
            copy,
            java.nio.file.attribute.PosixFilePermissions
              .fromString("rwxr-xr-x")
          )
          copy
        }

        _ <- fh.cmdUpdate(copy, url)
        backups <- backupsIn(dir)

        _ <- fh.cmdUpdate(copy, url)
        after <- backupsIn(dir)
      yield expect.all(
        // Replaced in place (still executable), previous copy kept.
        Files.readAllBytes(copy).sameElements(next),
        Files.isExecutable(copy)
      ) and expect.same(
        List(new String(original, UTF_8)),
        backups.map(Files.readString(_))
      ) and expect.same(backups, after)
    }
  }
