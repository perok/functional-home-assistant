//> using file fh.sc

// Tests for the fh script's functions, called directly through the `fh`
// script-wrapper namespace (weaver-test rides in on the script's
// `using toolkit` directive — the typelevel toolkit's test scope IS weaver
// from 0.2.0 on). Run from scripts/:
//
//   SCALA_TEST_MODE=true scala-cli test .
//
// Referencing any fh member executes the whole wrapper body, so the script
// gates its dispatcher behind SCALA_TEST_MODE (env or system property — the
// property below covers a run that forgot the env var). The decline wiring
// (--help, unknown-subcommand usage) is deliberately untested: that layer is
// decline's. The instance-facing flows (init, push, pull against the real
// routes) live in UseCaseSuite, which has the backend to talk to; here we
// cover what the script does WITHOUT an instance.

import cats.effect.IO
import weaver.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
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
