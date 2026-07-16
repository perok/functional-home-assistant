// Tests for the fh script itself, run by scala-cli's own test command
// (toolkit-test rides in on the script's `using toolkit` directive):
//
//   scala-cli test --server=false scripts/fh scripts/fh.test.scala
//
// Black-box on purpose: fh is a shebang script, and referencing ANY member of
// its wrapper object lazily executes the whole body (command.parse + sys.exit
// included) — verified on scala-cli 1.x. So these tests drive the script as a
// subprocess, exactly as a user does. The instance-facing flows (init, push,
// pull against the real routes) live in UseCaseSuite, which has the backend
// to talk to; here we cover everything the script does WITHOUT an instance.

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class FhScriptSuite extends CatsEffectSuite:

  // The script cold-compiles on first spawn; generous ceiling for CI.
  override def munitIOTimeout: Duration = 10.minutes

  /** The checked-in script, whether the suite is invoked from the repo root or
    * from scripts/. A stable path keeps scala-cli's compile cache warm.
    */
  private val script: Path =
    List(Path.of("scripts/fh"), Path.of("fh"))
      .map(_.toAbsolutePath)
      .find(Files.isRegularFile(_))
      .getOrElse(fail("scripts/fh not found — run from the repo root"))

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

  test("--help lists the subcommands and exits 0") {
    emptyDir.flatMap(runFh(script, _, Map.empty, "--help")).map { (code, out) =>
      assertEquals(code, 0, clue = out)
      List("init", "pull", "push", "update")
        .foreach(sub => assert(out.contains(sub), clue = out))
    }
  }

  test("an unknown subcommand fails with usage") {
    emptyDir.flatMap(runFh(script, _, Map.empty, "frobnicate")).map {
      (code, out) =>
        assertEquals(code, 1, clue = out)
        assert(out.contains("Usage"), clue = out)
    }
  }

  test("pull outside a workspace dies with the init hint, no stack trace") {
    // The script resolves .fh/base.pkl against its cwd; an empty temp dir is
    // exactly the lost-user case the message is for.
    emptyDir.flatMap(runFh(script, _, Map.empty, "pull")).map { (code, out) =>
      assertEquals(code, 1, clue = out)
      assert(out.contains("not an fh workspace"), clue = out)
      assert(out.contains("fh init"), clue = out)
      assert(!out.contains("at fh"), clue = out) // Die prints no stack trace
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
        _ = assertEquals(code1, 0, clue = out1)
        _ = assert(out1.contains("updated"), clue = out1)
        // Replaced in place (still executable), previous copy kept.
        _ = assert(Files.readAllBytes(copy).sameElements(next))
        _ = assert(Files.isExecutable(copy))
        backups <- IO.blocking(
          Files
            .list(work)
            .iterator()
            .asScala
            .toList
            .filter(_.getFileName.toString.startsWith("fh.backup."))
        )
        _ = assertEquals(
          backups.map(Files.readString(_)),
          List(Files.readString(script))
        )

        second <- runFh(copy, work, env, "update")
        (code2, out2) = second
        _ = assertEquals(code2, 0, clue = out2)
        _ = assert(out2.contains("up to date"), clue = out2)
        after <- IO.blocking(
          Files
            .list(work)
            .iterator()
            .asScala
            .toList
            .filter(_.getFileName.toString.startsWith("fh.backup."))
        )
        _ = assertEquals(after, backups)
      yield ()
    }
  }
