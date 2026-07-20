package fh.view

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Method, Request, Status}
import org.http4s.implicits.*

/** The terminal-error mechanism: the helpers fix the status, and the app-level
  * [[FHError.handle]] turns a raised `FHError` into that `status + message`
  * while letting any other error fall through as a 500.
  */
class FHErrorSuite extends CatsEffectSuite {

  test("helpers carry the sensible status code") {
    assertEquals(FHError.badCondition("x").status, 400)
    assertEquals(FHError.notFound("x").status, 404)
    assertEquals(FHError.unavailable("x").status, 503)
    assertEquals(FHError.internal("x").status, 500)
  }

  test("FHError is a Throwable whose message is the description") {
    val e = FHError.notFound("no such slug: foo")
    assert(e.isInstanceOf[Throwable])
    assertEquals(e.getMessage, "no such slug: foo")
  }

  test("handle maps a raised FHError to its status + message") {
    val app = FHError.handle(
      HttpApp[IO](_ => IO.raiseError(FHError.notFound("no such slug: foo")))
    )
    for {
      resp <- app.run(Request[IO](Method.GET, uri"/"))
      body <- resp.bodyText.compile.string
    } yield {
      assertEquals(resp.status, Status.NotFound)
      assertEquals(body, "no such slug: foo")
    }
  }

  test("handle lets a non-FHError fall through (Ember reports it as 500)") {
    val boom = new RuntimeException("unnamed bug")
    val app = FHError.handle(HttpApp[IO](_ => IO.raiseError(boom)))
    app.run(Request[IO](Method.GET, uri"/")).attempt.map {
      case Left(err) => assertEquals(err, boom)
      case Right(r)  => fail(s"expected the error to propagate, got $r")
    }
  }
}
