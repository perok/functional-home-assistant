package fh.view.runtime

import cats.effect.IO

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

/** The isolate library and the polyglot jars are resolved from one version
  * string in `build.sbt`, and a mismatch between them runs silently — correct
  * output, a different GraalJS (`docs/issue-report-3-graalvm-polyglot-isolate.md`).
  * `Engine.getVersion` reports the LIBRARY's version, so compare it to the jars'.
  */
class JsIsolateSuite extends munit.CatsEffectSuite {

  test("the isolate library is the polyglot jars' version") {
    // GraalVM publishes no isolate for macOS, where the chart falls back.
    assume(sys.props("os.name") == "Linux", "no isolate off Linux")
    val jars = Option(
      getClass.getResourceAsStream(
        "/META-INF/graalvm/org.graalvm.polyglot/version"
      )
    ).map(in => Using.resource(in)(s => String(s.readAllBytes(), UTF_8).trim))
      .getOrElse(fail("no polyglot version resource on the classpath"))
    JsIsolate.engine
      .use(e => IO(assertEquals(e.getVersion, jars)))
      .recover {
        // A second test run in one sbt server: the native library is still
        // bound to the first run's classloader. CI runs once per JVM.
        case e: IllegalStateException
            if e.getMessage.contains("already loaded in another classloader") =>
          assume(false, "isolate held by an earlier run; restart sbt")
      }
  }
}
