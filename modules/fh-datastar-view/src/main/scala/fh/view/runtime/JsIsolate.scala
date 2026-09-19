package fh.view.runtime

import cats.effect.{IO, Resource}
import org.graalvm.polyglot.{Context, Engine, HostAccess}

/** GraalJS, running in a polyglot isolate. Design and measurements:
  * `docs/plan-graaljs-isolate.md`.
  *
  * Counter-intuitively the cheap option — the guest heap lives in the isolate's
  * native heap instead of ours, measuring 152 MB RSS against 322 MB for the
  * same workload interpreted in-heap, at half the render time.
  *
  * Nothing is configured here because the add-on image supplies it all: the
  * `js-isolate-linux-<arch>` jar on the classpath is what registers the
  * isolate, and `polyglot.engine.userResourceCache` points at resources its
  * build already unpacked. So outside that image there is no isolate and this
  * raises — a local `sbt dashboardServe` has no JavaScript.
  */
object JsIsolate {

  /** One engine for the life of the process; contexts are cheap against it,
    * engines are not.
    */
  def engine: Resource[IO, Engine] =
    Resource.fromAutoCloseable(IO.blocking {
      Engine.newBuilder("js").spawnIsolate(true).build()
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
