package fh.view.build

import org.pkl.core.project.Project
import org.pkl.core.{EvaluatorBuilder, ModuleSource, OutputFormat}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

/** The site evaluation in a JVM of its own, which exits with it.
  *
  * pkl-core's stdlib is a JVM-wide static whose Truffle inline caches keep the
  * user classes and closures they last saw: in-process, an evaluated site stays
  * reachable after `Evaluator.close` (a JFR old-object path runs
  * `BaseModule$ListClass.instance` → `mapIndexed`'s direct-call cache → a
  * workspace `VmClass` → the module's cached values). On the Pi's workspace
  * that is ~85 MB live per evaluation and up to three at once, which the heap
  * then keeps committed. docs/plan-memory-hunt.md.
  */
object PklEvalChild {

  /** `<dashboardsDir> <entry> <moduleCacheDir>`; the JSON on stdout, a Pkl
    * error on stderr with exit 1.
    */
  def main(args: Array[String]): Unit = args match {
    case Array(dir, entry, cache) =>
      val code =
        try {
          System.out.write(evaluate(Path.of(dir), entry, Path.of(cache)))
          System.out.flush()
          0
        } catch {
          case NonFatal(e) =>
            System.err.print(Option(e.getMessage).getOrElse(e.toString))
            1
        }
      // Not `sys.exit` from inside the try: Truffle threads must not keep the
      // JVM alive, and nothing here needs a shutdown hook.
      Runtime.getRuntime.halt(code)
    case _ =>
      System.err.print("usage: PklEvalChild <dashboardsDir> <entry> <cache>")
      Runtime.getRuntime.halt(2)
  }

  private def evaluate(dir: Path, entry: String, cache: Path): Array[Byte] = {
    val builder = EvaluatorBuilder.preconfigured()
    val projectFile = dir.resolve("PklProject")
    if (projectFile.toFile.exists)
      builder.applyFromProject(Project.loadFromPath(projectFile)): Unit
    builder.setModuleCacheDir(cache)
    val evaluator = builder.setOutputFormat(OutputFormat.JSON).build()
    try
      evaluator
        .evaluateOutputText(ModuleSource.path(dir.resolve(entry)))
        .getBytes(UTF_8)
    finally evaluator.close()
  }

  /** Runs [[main]] with this process's java and classpath. */
  def run(
      dir: os.Path,
      entry: String,
      cache: os.Path
  ): Either[String, String] = {
    val java = ProcessHandle.current.info.command.orElse("java")
    val cmd = List(
      java,
      // C2 never pays for itself in one evaluation; C1 alone was the faster
      // child on x86 (plan).
      "-XX:TieredStopAtLevel=1",
      "-XX:+UseSerialGC",
      "-cp",
      classpath,
      "fh.view.build.PklEvalChild",
      dir.toString,
      entry,
      cache.toString
    )
    val result = os
      .proc(cmd)
      .call(
        check = false,
        stderr = os.Pipe,
        timeout = TimeUnit.MINUTES.toMillis(10)
      )
    if (result.exitCode == 0) Right(result.out.text(UTF_8))
    else Left(result.err.text(UTF_8))
  }

  /** The add-on's `-cp` is the whole classpath, but under sbt `java.class.path`
    * is the launcher's and the test classpath lives only in the loaders.
    */
  private def classpath: String = {
    val loaders = Iterator
      .iterate[ClassLoader | Null](getClass.getClassLoader)(_.nn.getParent)
      .takeWhile(_ != null)
    val fromLoaders = loaders.flatMap {
      case u: java.net.URLClassLoader =>
        u.getURLs.nn.iterator.map(url => Path.of(url.nn.toURI).toString)
      case _ => Iterator.empty
    }
    (fromLoaders ++ System
      .getProperty("java.class.path")
      .nn
      .split(
        java.io.File.pathSeparatorChar
      )).distinct.mkString(java.io.File.pathSeparator)
  }
}
