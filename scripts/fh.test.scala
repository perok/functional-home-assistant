// Tests for the fh script itself, run by scala-cli's own test command
// (weaver-test rides in on the script's `using toolkit` directive — the
// typelevel toolkit's test scope IS weaver from 0.2.0 on):
//
//   scala-cli test --server=false scripts/fh.sc scripts/fh.test.scala
//
// Black-box on purpose: fh is a shebang script, and referencing ANY member of
// its wrapper object lazily executes the whole body (command.parse + sys.exit
// included) — verified on scala-cli 1.x. So these tests drive the script as a
// subprocess, exactly as a user does. The instance-facing flows (init, push,
// pull against the real routes) live in UseCaseSuite, which has the backend
// to talk to; here we cover everything the script does WITHOUT an instance.
//
// No timeout override needed: weaver imposes no default per-test timeout
// (verified in weaver-core sources), so the script's cold compile on first
// spawn can take as long as it takes.

import cats.effect.IO
import weaver.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object FhScriptSuite extends SimpleIOSuite:

  /** The checked-in script, whether the suite is invoked from the repo root or
    * from scripts/. A stable path keeps scala-cli's compile cache warm.
    */
  private val script: Path =
    List(Path.of("scripts/fh.sc"), Path.of("fh.sc"))
      .map(_.toAbsolutePath)
      .find(Files.isRegularFile(_))
      .getOrElse(sys.error("scripts/fh.sc not found — run from the repo root"))

  private def runFh(
      at: Path,
      cwd: Path,
      env: Map[String, String],
      args: String*
  ): IO[(Int, String)] = IO.blocking {
    val cmd =
      List("scala-cli", "shebang", "--server=false", at.toString) ++ args
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    pb.redirectErrorStream(true)
    env.foreach((k, v) => pb.environment.put(k, v))
    val proc = pb.start()
    val out = new String(proc.getInputStream.readAllBytes(), UTF_8)
    (proc.waitFor(), out)
  }

  private val emptyDir = IO.blocking(Files.createTempDirectory("fh-test-"))

  /** Exit-code check that shows the process output on failure (the weaver
    * `clue` of a passing sub-expectation is not rendered, so a plain
    * `expect(code == n)` would leave a wrong exit code unexplained).
    */
  private def exits(expected: Int)(code: Int, out: String): Expectations =
    if code == expected then success
    else failure(s"exit $code (wanted $expected): $out")

  test("--help lists the subcommands and exits 0") {
    emptyDir.flatMap(runFh(script, _, Map.empty, "--help")).map { (code, out) =>
      exits(0)(code, out) and
        forEach(List("init", "pull", "push", "update"))(sub =>
          expect(clue(out).contains(clue(sub)))
        )
    }
  }

  test("an unknown subcommand fails with usage") {
    emptyDir.flatMap(runFh(script, _, Map.empty, "frobnicate")).map {
      (code, out) =>
        exits(1)(code, out) and expect(clue(out).contains("Usage"))
    }
  }

  test("pull outside a workspace dies with the init hint, no stack trace") {
    // The script resolves .fh/base.pkl against its cwd; an empty temp dir is
    // exactly the lost-user case the message is for.
    emptyDir.flatMap(runFh(script, _, Map.empty, "pull")).map { (code, out) =>
      exits(1)(code, out) and expect.all(
        clue(out).contains("not an fh workspace"),
        out.contains("fh init"),
        !out.contains("at fh") // Die prints no stack trace
      )
    }
  }

  test("update: sha-compare against the repo copy, replace with a backup") {
    // `update`'s remote is normally the GitHub raw URL of scripts/fh; the
    // FH_SELF_URL override points it at a local stub serving a NEWER copy.
    // First run replaces the file (dated backup kept — the user-file
    // convention); second run is a no-op because local now matches remote.
    // The stub is the JDK's own HttpServer: no extra test dependencies.
    val next =
      (Files.readString(script) + "\n// a newer revision\n").getBytes(UTF_8)

    // Stable (non-temp-unique) work dir so the copy's compile cache stays warm.
    val work = Path.of(sys.props("java.io.tmpdir"), "fh-script-update-test")

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

    def listBackups: IO[List[Path]] = IO.blocking(
      Files
        .list(work)
        .iterator()
        .asScala
        .toList
        .filter(_.getFileName.toString.startsWith("fh.backup."))
    )

    stub.use { server =>
      val env = Map(
        "FH_SELF_URL" -> s"http://127.0.0.1:${server.getAddress.getPort}/fh"
      )
      for
        copy <- IO.blocking {
          if Files.exists(work) then
            Files
              .walk(work)
              .iterator()
              .asScala
              .toList
              .reverse
              .foreach(Files.delete)
          Files.createDirectories(work)
          val copy = Files.copy(script, work.resolve("fh"))
          Files.setPosixFilePermissions(
            copy,
            PosixFilePermissions.fromString("rwxr-xr-x")
          )
          copy
        }

        first <- runFh(copy, work, env, "update")
        (code1, out1) = first
        backups <- listBackups

        second <- runFh(copy, work, env, "update")
        (code2, out2) = second
        after <- listBackups
      yield exits(0)(code1, out1) and exits(0)(code2, out2) and expect.all(
        clue(out1).contains("updated"),
        // Replaced in place (still executable), previous copy kept.
        Files.readAllBytes(copy).sameElements(next),
        Files.isExecutable(copy),
        clue(out2).contains("up to date")
      ) and expect.same(
        List(Files.readString(script)),
        backups.map(Files.readString(_))
      ) and expect.same(backups, after)
    }
  }
