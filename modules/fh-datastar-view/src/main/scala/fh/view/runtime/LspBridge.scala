package fh.view.runtime

import cats.effect.IO
import cats.effect.std.Queue
import fs2.{Chunk, Pipe, Pull, Stream}
import fs2.io.process.ProcessBuilder
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import java.nio.charset.StandardCharsets

/** Bridges a browser LSP client (CodeMirror's `@codemirror/lsp-client`, one
  * JSON-RPC message per WebSocket text frame, no headers) to the real Pkl
  * language server.
  *
  * pkl-lsp ships as a shaded stdio CLI (`java -jar pkl-lsp.jar`, its manifest
  * sets `Enable-Native-Access` for the tree-sitter grammar), so we run it as a
  * **subprocess per connection** rather than embedding the 16 MB shaded jar on
  * the app classpath (which would drag kotlin-stdlib/gson/lsp4j + the native
  * grammar into the deployed assembly jar). Isolation is the whole point:
  * classpath, native loading, and JVM flags all live in the child.
  *
  * The one translation the two protocols need is framing: lsp4j (the child's
  * stdio) uses LSP `Content-Length` headers; the WebSocket carries bare JSON.
  * [[toFrames]] parses the child's stdout into messages; [[encodeFrame]] wraps
  * each client message with a header before it reaches stdin.
  *
  * Lifecycle: the child is a `Stream.resource` inside the `send` stream, so when
  * the socket closes (http4s finalizes `send`) the process is destroyed. The
  * client → server direction rides a [[Queue]] created before `build` so both
  * halves can see it.
  */
object LspBridge {

  /** Build the `GET /lsp/pkl` WebSocket response: spawn pkl-lsp, pump the
    * socket both ways. `workspaceRoot` is informational here — the client sends
    * the real `workspaceFolders`/document URIs (absolute on-disk paths) in its
    * `initialize`, which is how pkl-lsp resolves `lib/` imports and the dump.
    */
  def wsResponse(
      wsb: WebSocketBuilder2[IO],
      pklLspJar: os.Path
  ): IO[Response[IO]] =
    Queue.unbounded[IO, WebSocketFrame].flatMap { fromClient =>
      // Client -> server: stash every inbound frame; the process stream drains
      // it into stdin. Ignore close frames (the send stream's finalizer, driven
      // by http4s on socket close, tears the process down).
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

          // Surface the child's diagnostics/log to our stdout; never fatal.
          val drainStderr: Stream[IO, Nothing] =
            proc.stderr
              .through(fs2.text.utf8.decode)
              .through(fs2.text.lines)
              .filter(_.nonEmpty)
              .foreach(l => IO.println(s"[pkl-lsp] $l"))
              .drain

          proc.stdout
            .through(toFrames)
            .map(WebSocketFrame.Text(_))
            .concurrently(toStdin)
            .concurrently(drainStderr)
        }

      wsb.build(send, receive)
    }

  /** `java -jar pkl-lsp.jar` as an fs2 subprocess. The jar's manifest carries
    * `Enable-Native-Access: ALL-UNNAMED`, so the tree-sitter grammar loads with
    * no extra flags.
    */
  private def spawn(jar: os.Path) =
    ProcessBuilder(javaExecutable, "-jar", jar.toString).spawn[IO]

  /** The `java` used to launch pkl-lsp — which requires **JDK 23+** and rejects
    * older class files. We do NOT trust `PATH` (whatever `java` is first) nor
    * blindly reuse this JVM: the app may itself be forked on an older JDK (e.g.
    * a Nix-pinned sbt on 21), which would fail pkl-lsp's version check. Order:
    * `PKL_LSP_JAVA` override, then this JVM if it's new enough, then the newest
    * JDK 23+ discovered under the usual install roots, else this JVM's `java`
    * (which then errors visibly rather than silently picking a wrong one).
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

  /** Newest `java` (>= 23) found under the standard JDK install roots, by
    * reading each candidate's `release` file. Best-effort; `None` if none.
    */
  private def discoverJdk23: Option[String] = {
    val roots = List(
      os.root / "usr" / "lib" / "jvm",
      os.root / "Library" / "Java" / "JavaVirtualMachines"
    )
    roots
      .filter(os.exists)
      .flatMap(r => os.list(r).filter(os.isDir))
      .map(d => if (os.exists(d / "Contents" / "Home")) d / "Contents" / "Home" else d)
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

  /** The Java feature version from a JDK home's `release` file (`JAVA_VERSION`),
    * e.g. `"25.0.3"` -> 25, `"1.8.0"` -> 8.
    */
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

  /** Wrap a JSON-RPC message in an LSP `Content-Length` frame (ASCII header +
    * UTF-8 body) for the child's stdin.
    */
  private def encodeFrame(json: String): Chunk[Byte] = {
    val body = json.getBytes(StandardCharsets.UTF_8)
    val header =
      s"Content-Length: ${body.length}\r\n\r\n"
        .getBytes(StandardCharsets.US_ASCII)
    Chunk.array(header) ++ Chunk.array(body)
  }

  /** Parse a byte stream of LSP `Content-Length` frames into JSON message
    * bodies. Buffers across chunk boundaries and emits every complete frame
    * before pulling more input. Only `Content-Length` is honored (the sole
    * required header; pkl-lsp sends no others).
    */
  private def toFrames: Pipe[IO, Byte, String] = in => {
    def go(buf: Chunk[Byte], s: Stream[IO, Byte]): Pull[IO, String, Unit] =
      extract(buf) match {
        case Some((json, rest)) => Pull.output1(json) >> go(rest, s)
        case None =>
          s.pull.uncons.flatMap {
            case Some((hd, tl)) => go(buf ++ hd, tl)
            case None           => Pull.done
          }
      }
    go(Chunk.empty, in).stream
  }

  /** Extract one frame from the front of `buf`, returning it plus the remainder,
    * or `None` if a complete frame isn't buffered yet. The header block is
    * ASCII; the `\r\n\r\n` separator ends it.
    */
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
            Some((json, Chunk.array(arr, bodyStart + len, arr.length - bodyStart - len)))
          }
      }
    }
  }

  /** Index of the `\r\n\r\n` header/body separator, or -1. */
  private def indexOfSep(arr: Array[Byte]): Int = {
    var i = 0
    val end = arr.length - 3
    while (i < end) {
      if (arr(i) == 13 && arr(i + 1) == 10 && arr(i + 2) == 13 && arr(i + 3) == 10)
        return i
      i += 1
    }
    -1
  }

  /** Read the `Content-Length` value from an LSP header block (case-insensitive
    * header name, per the base protocol).
    */
  private def contentLength(header: String): Option[Int] =
    header.linesIterator
      .map(_.trim)
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") =>
          l.drop("content-length:".length).trim
      }
      .flatMap(_.toIntOption)
}
