package fh.view.runtime

import fh.view.build.{PklBuild, PklLspProject}
import fh.view.telemetry.Logging
import cats.effect.IO
import cats.effect.std.Queue
import fs2.{Chunk, Pipe, Pull, Stream}
import fs2.io.process.ProcessBuilder
import io.circe.Json
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.LoggerFactory

import java.nio.charset.StandardCharsets

/** Bridges the browser's LSP client (bare JSON per WebSocket frame) to pkl-lsp
  * run as a subprocess per connection, not embedded: the 16 MB shaded jar would
  * drag kotlin-stdlib, lsp4j and a native grammar into the assembly. Besides
  * `Content-Length` framing on the child's stdio, it names the workspace in the
  * client's `initialize` ([[withWorkspace]]). The process dies with the socket,
  * as a resource of `send`.
  */
object LspBridge {

  def wsResponse(
      wsb: WebSocketBuilder2[IO],
      pklLspJar: os.Path,
      workspace: os.Path,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[Response[IO]] =
    Queue.unbounded[IO, WebSocketFrame].flatMap { fromClient =>
      val log = loggerFactory.getLoggerFromName("fh.view.runtime.LspBridge")
      val receive: Pipe[IO, WebSocketFrame, Unit] =
        _.evalMap(fromClient.offer)
      val send: Stream[IO, WebSocketFrame] =
        Stream
          .fromQueueUnterminated(fromClient)
          .collect { case t: WebSocketFrame.Text => t.str }
          .through(session(pklLspJar, workspace, l => log.debug(l)))
          .map(WebSocketFrame.Text(_))
      wsb.build(send, receive)
    }

  /** One pkl-lsp for the length of the stream: the client's JSON messages in,
    * the server's out. `PklEditorSupportSuite` drives this, not the socket.
    */
  private[view] def session(
      jar: os.Path,
      workspace: os.Path,
      stderr: String => IO[Unit]
  ): Pipe[IO, String, String] = fromClient =>
    Stream.resource(spawn(jar, workspace)).flatMap { proc =>
      val toStdin: Stream[IO, Nothing] =
        fromClient
          .map(withWorkspace(_, workspace))
          .map(encodeFrame)
          .flatMap(Stream.chunk)
          .through(proc.stdin)

      val drainStderr: Stream[IO, Nothing] =
        proc.stderr
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .filter(_.nonEmpty)
          .foreach(stderr)
          .drain

      proc.stdout
        .through(toFrames)
        .concurrently(toStdin)
        .concurrently(drainStderr)
    }

  /** `initialize` with the workspace as its root and only folder, so pkl-lsp
    * loads the project the build keeps synced (`PklLspProject`): without a
    * declared folder it loads none, and resolves no package import.
    * `@codemirror/lsp-client` sends neither. Any other message passes as is.
    */
  private[runtime] def withWorkspace(
      message: String,
      workspace: os.Path
  ): String =
    io.circe.parser.parse(message).toOption match
      case Some(json)
          if json.hcursor
            .downField("method")
            .as[String]
            .contains("initialize") =>
        val uri =
          Json.fromString(workspace.toNIO.toUri.toString.stripSuffix("/"))
        json
          .deepMerge(
            Json.obj(
              "params" -> Json.obj(
                "rootUri" -> uri,
                "workspaceFolders" -> Json.arr(
                  Json.obj(
                    "uri" -> uri,
                    "name" -> Json.fromString(workspace.last)
                  )
                ),
                "capabilities" -> Json.obj(
                  "workspace" -> Json.obj("workspaceFolders" -> Json.True)
                )
              )
            )
          )
          .noSpaces
      case _ => message

  // The jar's manifest enables native access, so no extra flags. Its cache is
  // the workspace's, through the link the build keeps (`PklLspProject`).
  private def spawn(jar: os.Path, workspace: os.Path) =
    ProcessBuilder(javaExecutable, "-jar", jar.toString)
      .withExtraEnv(
        Map(
          "XDG_CACHE_HOME" -> PklLspProject
            .cacheHome(PklBuild.workspaceCacheDir(workspace))
            .toString
        )
      )
      .spawn[IO]

  /** pkl-lsp needs JDK 23+, and this app may run on an older one, so neither
    * `PATH` nor this JVM is trusted blindly: `PKL_LSP_JAVA`, this JVM if new
    * enough, the newest installed 23+, else this JVM (which fails visibly).
    */
  private def javaExecutable: String = {
    def bin(home: os.Path): String = (home / "bin" / "java").toString
    val self = os.Path(System.getProperty("java.home"))
    sys.env
      .get("PKL_LSP_JAVA")
      .filter(_.nonEmpty)
      .orElse(Option.when(Runtime.version().feature() >= 23)(bin(self)))
      .orElse(discoverJdk23)
      .getOrElse(bin(self))
  }

  private def discoverJdk23: Option[String] = {
    val roots = List(
      os.root / "usr" / "lib" / "jvm",
      os.root / "Library" / "Java" / "JavaVirtualMachines"
    )
    roots
      .filter(os.exists)
      .flatMap(r => os.list(r).filter(os.isDir))
      .map(d =>
        if (os.exists(d / "Contents" / "Home")) d / "Contents" / "Home" else d
      )
      .flatMap(home =>
        featureVersion(home)
          .filter(_ >= 23)
          .map(_ -> (home / "bin" / "java"))
      )
      .filter { case (_, java) => os.exists(java) }
      .sortBy(-_._1)
      .headOption
      .map(_._2.toString)
  }

  // `JAVA_VERSION` from `release`: `"25.0.3"` -> 25, `"1.8.0"` -> 8.
  private def featureVersion(home: os.Path): Option[Int] =
    scala.util
      .Try {
        os.read
          .lines(home / "release")
          .find(_.startsWith("JAVA_VERSION="))
          .map(_.split('=')(1).trim.replace("\"", ""))
          .map(_.stripPrefix("1.").takeWhile(_.isDigit).toInt)
      }
      .toOption
      .flatten

  private def encodeFrame(json: String): Chunk[Byte] = {
    val body = json.getBytes(StandardCharsets.UTF_8)
    val header =
      s"Content-Length: ${body.length}\r\n\r\n"
        .getBytes(StandardCharsets.US_ASCII)
    Chunk.array(header) ++ Chunk.array(body)
  }

  // Only `Content-Length` is read; pkl-lsp sends no other header.
  private def toFrames: Pipe[IO, Byte, String] = in => {
    def go(buf: Chunk[Byte], s: Stream[IO, Byte]): Pull[IO, String, Unit] =
      extract(buf) match {
        case Some((json, rest)) => Pull.output1(json) >> go(rest, s)
        case None               =>
          s.pull.uncons.flatMap {
            case Some((hd, tl)) => go(buf ++ hd, tl)
            case None           => Pull.done
          }
      }
    go(Chunk.empty, in).stream
  }

  private def extract(buf: Chunk[Byte]): Option[(String, Chunk[Byte])] = {
    val arr = buf.toArray
    val sep = indexOfSep(arr)
    if (sep < 0) None
    else {
      val header = new String(arr, 0, sep, StandardCharsets.US_ASCII)
      contentLength(header) match {
        case None => None // malformed header block; wait for more (defensive)
        case Some(len) =>
          val bodyStart = sep + 4
          if (arr.length - bodyStart < len) None
          else {
            val json =
              new String(arr, bodyStart, len, StandardCharsets.UTF_8)
            Some(
              (
                json,
                Chunk.array(arr, bodyStart + len, arr.length - bodyStart - len)
              )
            )
          }
      }
    }
  }

  private def indexOfSep(arr: Array[Byte]): Int = {
    var i = 0
    val end = arr.length - 3
    while (i < end) {
      if (
        arr(i) == 13 && arr(i + 1) == 10 && arr(i + 2) == 13 && arr(i + 3) == 10
      )
        return i
      i += 1
    }
    -1
  }

  private def contentLength(header: String): Option[Int] =
    header.linesIterator
      .map(_.trim)
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") =>
          l.drop("content-length:".length).trim
      }
      .flatMap(_.toIntOption)
}
