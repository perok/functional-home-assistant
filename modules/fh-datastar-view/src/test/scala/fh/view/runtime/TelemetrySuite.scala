package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.otel4s.trace.Tracer

/** The two promises this instrumentation makes: it costs nothing when nobody
 * is collecting, and when somebody is, a log line can be tied to the trace it
 * happened in.
 */
class TelemetrySuite extends munit.CatsEffectSuite {

  // Every unconfigured spelling: absent, empty, and whitespace — the last two
  // being what an add-on option the user cleared actually arrives as.
  private val unconfigured =
    List(None, Some(""), Some("   "))

  test("with no endpoint configured, the SDK is never built") {
    // Asserted through the tracer's own `meta.isEnabled`, which is what every
    // span site consults before doing any work — a no-op tracer reports false
    // and the span never materialises. This is the property that keeps an
    // ordinary Pi install paying nothing for #75, so it is worth pinning
    // rather than trusting the branch to stay correct.
    unconfigured.traverse_(endpoint =>
      Telemetry
        .tracerFor("test", endpoint)
        .use(
          _.meta.isEnabled.map(on =>
            assert(!on, s"a tracer was built for endpoint $endpoint")
          )
        )
    )
  }

  test("a no-op tracer still runs the effect it wraps") {
    // The failure this guards against is the expensive one: instrumentation
    // that quietly skips the work when disabled would mean a page renders on
    // a traced install and not on an untraced one.
    Telemetry
      .tracerFor("test", None)
      .use(tracer =>
        IO.ref(0).flatMap { calls =>
          tracer
            .span("did-it-run")
            .surround(calls.update(_ + 1))
            .flatMap(_ => calls.get.assertEquals(1))
        }
      )
  }

  test("a log line outside any span carries no ids to mislead a reader") {
    val underlying = StructuredTestingLogger.impl[IO]()
    val logger = new TracedLogger(underlying, Tracer.noop[IO])
    for {
      _ <- logger.info("nothing traced here")
      logged <- underlying.logged
    } yield {
      val ctx = logged.map(_.ctx).headOption.getOrElse(Map.empty)
      assert(!ctx.contains("trace_id"), s"unexpected trace_id in $ctx")
      assert(!ctx.contains("span_id"), s"unexpected span_id in $ctx")
    }
  }

  test("the caller's own context survives, and wins on a key collision") {
    // The ids are merged UNDER the call site's map deliberately: a wrapper
    // that could overwrite what a caller explicitly attached would corrupt
    // data to add metadata.
    val underlying = StructuredTestingLogger.impl[IO]()
    val logger = new TracedLogger(underlying, Tracer.noop[IO])
    for {
      _ <- logger.info(Map("slug" -> "home", "trace_id" -> "mine"))("hello")
      logged <- underlying.logged
    } yield {
      val ctx = logged.map(_.ctx).headOption.getOrElse(Map.empty)
      assertEquals(ctx.get("slug"), Some("home"))
      assertEquals(ctx.get("trace_id"), Some("mine"))
    }
  }

  test("every arity reaches the underlying logger") {
    // 25 delegating methods written by hand is exactly where a copy-paste slip
    // lands — a `warn` that calls `info`, or an arity that drops its throwable
    // — and none of it would fail anywhere else.
    val underlying = StructuredTestingLogger.impl[IO]()
    val logger = new TracedLogger(underlying, Tracer.noop[IO])
    val boom = new RuntimeException("boom")
    for {
      _ <- logger.trace("a")
      _ <- logger.debug("b")
      _ <- logger.info("c")
      _ <- logger.warn("d")
      _ <- logger.error("e")
      _ <- logger.trace(boom)("f")
      _ <- logger.debug(boom)("g")
      _ <- logger.info(boom)("h")
      _ <- logger.warn(boom)("i")
      _ <- logger.error(boom)("j")
      logged <- underlying.logged
    } yield {
      assertEquals(logged.map(_.message).toList, List("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
      // The five that were given one must still carry it.
      assertEquals(logged.count(_.throwOpt.isDefined), 5)
    }
  }
}
