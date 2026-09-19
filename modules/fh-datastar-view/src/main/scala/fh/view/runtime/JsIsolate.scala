package fh.view.runtime

import cats.effect.{IO, Resource}
import org.graalvm.polyglot.{Context, Engine, HostAccess}

/** GraalJS, running in a polyglot isolate — a second, self-contained VM inside
  * a `dlopen`ed library rather than in this JVM.
  *
  * The route and the numbers behind it are `docs/plan-graaljs-isolate.md`. The
  * one thing worth repeating here is why the odd-looking configuration is the
  * cheap option and not the expensive one: the guest heap lives in the
  * isolate's compact native heap instead of ours, which measured 152 MB RSS
  * against 322 MB for interpreting the same workload in-heap, at half the
  * render time.
  *
  * Nothing is configured in code, deliberately. The library is found the way
  * Truffle finds it by itself — a `js-isolate-linux-<arch>` jar on the
  * classpath registers the provider, and the resources are read from the cache
  * `polyglot.engine.userResourceCache` names. The add-on image supplies both,
  * and unpacks that cache during its own build so nothing is extracted at
  * runtime. The alternative, naming the `.so` through `engine.IsolateLibrary`,
  * needs no jar but is an experimental option Truffle itself annotates "for
  * testing purposes only".
  *
  * So outside the add-on image there is no isolate and this raises. That is the
  * honest state: a local `sbt dashboardServe` has no JavaScript.
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
