package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.graalvm.polyglot.{Context, Engine, HostAccess}

/** GraalJS, running in a polyglot isolate. Design and measurements:
  * `docs/plan-graaljs-isolate.md`.
  *
  * Counter-intuitively the cheap option — the guest heap lives in the isolate's
  * native heap instead of ours, measuring 152 MB RSS against 322 MB for the
  * same workload interpreted in-heap, at half the render time.
  *
  * Nothing is configured here because the classpath supplies it all: the
  * `js-isolate-linux-<arch>` jar is what registers the isolate, and
  * `polyglot.engine.userResourceCache` says where Truffle may unpack its native
  * resources — which it does by itself, once, the first time this is built.
  *
  * `build.sbt` puts THIS machine's isolate on the compile classpath where
  * GraalVM publishes one (linux/amd64, linux/arm64) and stages both into the
  * image, so a local run and the add-on normally draw on the same engine. Where
  * it publishes none — macOS — [[engineOrInHeap]] is what happens instead.
  */
object JsIsolate {

  /** One engine for the life of the process; contexts are cheap against it,
    * engines are not.
    */
  def engine: Resource[IO, Engine] =
    Resource.fromAutoCloseable(IO.blocking {
      Engine.newBuilder("js").spawnIsolate(true).build()
    })

  /** The isolate where this machine has one, the interpreter where it does not,
    * and `onFallback` told which happened.
    *
    * Not a second design: ADR 0032 measured both columns and the SVG is
    * byte-identical, so this is a performance switch — ECharts evaluates in
    * ~1.0 s against ~0.3 s, a warm render in ~51 ms against ~30 ms, and the
    * interpreted one grows with the point count where the isolate stays flat.
    *
    * It exists because the alternative is worse than a slow chart: more-info
    * composes one for every numeric sensor, so an engine that raises means a
    * 503 on the popup of half the house. Loud rather than quiet, because in the
    * add-on image this must never fire and `JsIsolateCheck` is what proves it
    * in CI.
    */
  def engineOrInHeap(onFallback: Throwable => IO[Unit]): Resource[IO, Engine] =
    engine.handleErrorWith((e: Throwable) =>
      Resource.eval(onFallback(e)) *> inHeap
    )

  /** GraalJS interpreted, in this JVM's own heap.
    *
    * `WarnInterpreterOnly` off because the warning is addressed to someone who
    * could install a compiler, and here nobody can: the choice was already made
    * by the isolate not being on the classpath.
    */
  def inHeap: Resource[IO, Engine] =
    Resource.fromAutoCloseable(IO.blocking {
      Engine
        .newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()
    })

  def context(engine: Engine): Resource[IO, Context] =
    Resource.fromAutoCloseable(IO.blocking {
      Context
        .newBuilder("js")
        .engine(engine)
        .allowHostAccess(HostAccess.SCOPED)
        .build()
    })
}
