package fh.view.runtime

import fh.view.telemetry.Logging
import cats.effect.IO
import cats.effect.std.Queue
import fs2.{Chunk, Pipe, Pull, Stream}
import fs2.io.process.ProcessBuilder
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.LoggerFactory

import java.nio.charset.StandardCharsets

/** Bridges the browser's LSP client (bare JSON per WebSocket frame) to pkl-lsp
  * run as a subprocess per connection, not embedded: the 16 MB shaded jar would
  * drag kotlin-stdlib, lsp4j and a native grammar into the assembly. The only
  * translation is `Content-Length` framing on the child's stdio. The process
  * dies with the socket, as a resource of `send`.
  */
object LspBridge {

  /** The client's `initialize` carries the workspace and document URIs. */
  def wsResponse(
      wsb: WebSocketBuilder2[IO],
      pklLspJar: os.Path,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[Response[IO]] =
    Queue.unbounded[IO, WebSocketFrame].flatMap { fromClient =>
      val log = loggerFactory.getLoggerFromName("fh.view.runtime.LspBridge")
      val receive: Pipe[IO, WebSocketFrame, Unit] =
        _.evalMap(fromClient.offer)

      val send: Stream[IO, WebSocketFrame] =
        Stream.resource(spawn(pklLspJar)).flatMap { proc =>
          val toStdin: Stream[IO, Nothing] =
            Stream
              .fromQueueUnterminated(fromClient)
              .collect { case t: WebSocketFrame.Text => t.str }
              .map(encodeFrame)
              .flatMap(Stream.chunk)
              .through(proc.stdin)

          val drainStderr: Stream[IO, Nothing] =
            proc.stderr
              .through(fs2.text.utf8.decode)
              .through(fs2.text.lines)
              .filter(_.nonEmpty)
              .foreach(l => log.debug(l))
              .drain

          proc.stdout
            .through(toFrames)
            .map(WebSocketFrame.Text(_))
            .concurrently(toStdin)
            .concurrently(drainStderr)
        }

      wsb.build(send, receive)
    }

  // The jar's manifest enables native access, so no extra flags.
  private def spawn(jar: os.Path) =
    ProcessBuilder(javaExecutable, "-jar", jar.toString).spawn[IO]

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
