package fh.view.runtime

import cats.effect.{IO, Resource}
import fh.view.FHError
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
  */
object JsIsolate {

  /** Absolute path to `libpolyglotisolate.so`, set by the add-on image, which
    * stages the library for its own architecture.
    *
    * There is no default and no classpath fallback: the alternative to naming
    * the library is letting Truffle extract it from a jar, which is a 159 MB
    * write per GraalVM version into a cache nothing prunes.
    */
  val libraryVar: String = "FH_JS_ISOLATE_LIBRARY"

  /** One engine for the life of the process; contexts are cheap against it,
    * engines are not.
    */
  def engine: Resource[IO, Engine] =
    Resource.eval(library).flatMap(engineAt)

  def engineAt(library: String): Resource[IO, Engine] =
    Resource.fromAutoCloseable(IO.blocking {
      Engine
        .newBuilder("js")
        // `engine.IsolateLibrary` is experimental and Truffle says so on
        // every boot. Taken deliberately — it is what keeps the fat jar
        // architecture-independent.
        .allowExperimentalOptions(true)
        // On `Engine.Builder`. The identically-named call on `Context.Builder`
        // is silently ineffective once a shared engine is in play, which looks
        // like the isolate simply not saving any memory.
        .spawnIsolate(true)
        .option("engine.IsolateLibrary", library)
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

  private def library: IO[String] =
    IO(sys.env.get(libraryVar)).flatMap {
      case Some(path) => IO.pure(path)
      case None       =>
        IO.raiseError(
          FHError.internal(
            s"$libraryVar is not set — no JavaScript isolate library to load"
          )
        )
    }
}
