package fh.view.testkit

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fh.view.runtime.LspBridge
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.duration.*

/** pkl-lsp over its stdio, as `/edit`'s socket drives it through [[LspBridge]]:
  * requests matched to replies by id, the server's own requests answered by
  * `answer`, and the last diagnostics per document kept.
  */
final class LspClient private (
    out: Queue[IO, String],
    nextId: Ref[IO, Long],
    pending: Ref[IO, Map[Long, Deferred[IO, Json]]],
    diagnostics: Ref[IO, Map[String, Json]]
) {

  def request(method: String, params: Json): IO[Json] =
    for {
      id <- nextId.getAndUpdate(_ + 1)
      reply <- Deferred[IO, Json]
      _ <- pending.update(_ + (id -> reply))
      _ <- out.offer(
        Json
          .obj(
            "jsonrpc" -> Json.fromString("2.0"),
            "id" -> Json.fromLong(id),
            "method" -> Json.fromString(method),
            "params" -> params
          )
          .noSpaces
      )
      json <- reply.get.timeout(90.seconds)
    } yield json

  def notify(method: String, params: Json): IO[Unit] =
    out.offer(
      Json
        .obj(
          "jsonrpc" -> Json.fromString("2.0"),
          "method" -> Json.fromString(method),
          "params" -> params
        )
        .noSpaces
    )

  /** The last `publishDiagnostics` for `uri`, once one has arrived. */
  def diagnosticsFor(uri: String, within: FiniteDuration): IO[Option[Json]] =
    Stream
      .repeatEval(diagnostics.get.map(_.get(uri)))
      .metered(100.millis)
      .collectFirst { case Some(d) => d }
      .interruptAfter(within)
      .compile
      .last
}

object LspClient {

  /** `answer` gets each request the server sends (method, params) and returns
    * its result, or the error to reply with.
    */
  def spawn(
      jar: os.Path,
      env: Map[String, String],
      answer: (String, Json) => Either[Json, Json],
      stderr: String => IO[Unit] = _ => IO.unit,
      notified: (String, Json) => IO[Unit] = (_, _) => IO.unit
  ): Resource[IO, LspClient] =
    for {
      proc <- LspBridge.spawn(jar, env)
      out <- Resource.eval(Queue.unbounded[IO, String])
      nextId <- Resource.eval(Ref.of[IO, Long](1L))
      pending <- Resource.eval(
        Ref.of[IO, Map[Long, Deferred[IO, Json]]](Map.empty)
      )
      diags <- Resource.eval(Ref.of[IO, Map[String, Json]](Map.empty))
      _ <- Stream
        .fromQueueUnterminated(out)
        .map(LspBridge.encodeFrame)
        .flatMap(Stream.chunk)
        .through(proc.stdin)
        .compile
        .drain
        .background
      _ <- proc.stderr
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .evalMap(stderr)
        .compile
        .drain
        .background
      _ <- proc.stdout
        .through(LspBridge.toFrames)
        .evalMap { text =>
          val msg = parse(text).getOrElse(Json.Null).hcursor
          val id = msg.downField("id").focus
          val method = msg.downField("method").as[String].toOption
          (id, method) match {
            case (Some(i), Some(m)) =>
              val params = msg.downField("params").focus.getOrElse(Json.Null)
              val reply = answer(m, params).fold("error" -> _, "result" -> _)
              out.offer(
                Json
                  .obj("jsonrpc" -> Json.fromString("2.0"), "id" -> i, reply)
                  .noSpaces
              )
            case (Some(i), None) =>
              i.asNumber.flatMap(_.toLong).traverse_ { n =>
                pending
                  .modify(p => (p - n, p.get(n)))
                  .flatMap(_.traverse_(_.complete(msg.value).void))
              }
            case (None, Some("textDocument/publishDiagnostics")) =>
              val params = msg.downField("params")
              params.downField("uri").as[String].toOption.traverse_ { uri =>
                diags.update(_ + (uri -> params.focus.getOrElse(Json.Null)))
              }
            case (None, Some(m)) =>
              notified(m, msg.downField("params").focus.getOrElse(Json.Null))
            case _ => IO.unit
          }
        }
        .compile
        .drain
        .background
    } yield new LspClient(out, nextId, pending, diags)
}
