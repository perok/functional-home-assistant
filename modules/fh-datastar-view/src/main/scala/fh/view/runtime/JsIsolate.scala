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
  * Nothing is configured here: the `js-isolate-linux-<arch>` jar on the
  * classpath registers the isolate, and `polyglot.engine.userResourceCache`
  * says where Truffle unpacks it. GraalVM publishes no macOS isolate; there
  * [[engineOrInHeap]] falls back.
  */
object JsIsolate {

  /** One engine for the life of the process; contexts are cheap against it,
    * engines are not.
    */
  def engine: Resource[IO, Engine] =
    Resource.fromAutoCloseable(IO.blocking {
      Engine.newBuilder("js").spawnIsolate(true).build()
    })

  /** The interpreter where there is no isolate — macOS, and any sbt run, whose
    * classpath carries in-heap JavaScript. The image's does not
    * (`forbiddenJarEntries`), so there this fails too and every chart shows its
    * error; the warning is what names the staged library as the cause.
    */
  def engineOrInHeap(onFallback: Throwable => IO[Unit]): Resource[IO, Engine] =
    engine.handleErrorWith((e: Throwable) =>
      Resource.eval(onFallback(e)) *> inHeap
    )

  // `WarnInterpreterOnly` off: [[engineOrInHeap]] already logs the fallback.
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
