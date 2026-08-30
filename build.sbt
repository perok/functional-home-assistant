import FHCodegenPlugin.autoImport.*
import smithy4s.codegen.Smithy4sCodegenPlugin
import org.typelevel.scalacoptions.ScalacOptions

val http4sVersion = "0.23.34"
val MUnitFramework = new TestFramework("munit.Framework")

val commonSettings = Seq(
  scalaVersion := "3.8.4",
  tpolecatExcludeOptions ++= Set(
    ScalacOptions.warnError
  ),
  // Test / tpolecatExcludeOptions ++= Set(
  //  ScalacOptions.fatalWarnings
  // ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.7.0",
    "io.scalaland" %% "chimney" % "1.11.0",
    "com.lihaoyi" %% "pprint" % "0.9.6"
  )
)
addCommandAlias("doCodegen", "; fhTaskCodeGen ; home-codegen / scalafmt")
// Datastar dashboard: build phase (regenerate dashboard.json) and runtime
// server. There is nothing to name — a workspace has ONE entrypoint,
// `site.pkl`, holding every dashboard it serves (ADR 0021).
addCommandAlias(
  "dashboardBuild",
  "fh-datastar-view/runMain fh.view.build.BuildApp"
)
addCommandAlias(
  // The workspace dir comes from `DASHBOARDS_DIR` (set to an absolute
  // repo-root path in the project's `run / envVars` above). A local run
  // bootstraps a package-form workspace there — its own home/, .fh/, seeded
  // entries, .pkl-cache — gitignored, exactly the shape the add-on writes.
  "dashboardServe",
  "fh-datastar-view/runMain fh.view.runtime.ServerApp"
)
// Rebaseline snapshots after an INTENTIONAL change.
//
// TWO gates, deliberately, because the two artifacts have opposite risk. The
// wire snapshots are regenerated often and reviewed as a JSON diff; the visual
// PNG baselines must NOT be regenerated on a developer machine at all (font
// rasterization differs from CI's, so a local rebaseline bakes this machine's
// rendering into the repo). One flag for both meant the routine operation
// silently rewrote the dangerous artifact.
//
// A COMMAND, not an `addCommandAlias` chain: sbt aborts the rest of a `;` chain
// when a task fails, so a trailing `sys.props.remove` never ran on the exact
// path that needs it most — leaving the persistent server stuck in regenerate
// mode, where every later run reports green while rewriting files. `try/finally`
// around `Command.process` clears it either way. (`sys.props`, not a shell
// export: sbt 2.0's server keeps its start-time env forever.)
lazy val snapshotUpdateCommands = Seq(
  Command.command("dashboardSnapshotsUpdate") { state =>
    runWithFlag("FH_UPDATE_SNAPSHOTS", "fh-datastar-view/testFull", state)
  },
  Command.command("dashboardVisualSnapshotsUpdate") { state =>
    runWithFlag(
      "FH_UPDATE_VISUAL_SNAPSHOTS",
      "fh-datastar-view/testOnly fh.view.smoke.*",
      state
    )
  }
)

def runWithFlag(flag: String, command: String, state: State): State = {
  sys.props.put(flag, "1")
  try Command.process(command, state)
  finally sys.props.remove(flag)
}

lazy val `ha-api` = project // todo add api layer here as well
  .in(file("modules/ha-api"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .dependsOn(`fh-domain`)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "org.typelevel" %% "cats-effect" % "3.7.0"
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0"
    ),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.3.5" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test
    )
  )

lazy val `fh-domain` = project
  .in(file("modules/fh-domain"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "shapeless3-deriving" % "3.6.0",
      "io.circe" %% "circe-core" % "0.14.16",
      "org.http4s" %% "http4s-core" % http4sVersion
    )
  )

lazy val `fh-api` = project // TODO needed?
  .in(file("modules/fh-api"))
  .settings(commonSettings)

// TODO? https://github.com/disneystreaming/smithy4s/blob/2522c02d3ffa901c2b6a9caa39035d31d9bfe2d0/build.sbt#L459-L462
// Though, can this val be referenced as a plugin to other projects here?
lazy val `fh-codegen-plugin` = project
  .in(file("modules/fh-codegen-plugin"))
  .dependsOn(`ha-api`, `fh-domain`)
  .settings(
    commonSettings,
    // TOdo alias instead
    // fhTaskCodeGen := (ThisBuild / scalafmt).dependsOn(fhTaskCodeGen),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalafmt-core" % "3.11.5", // check latest version
      // "org.scalameta" %% "scalameta" % "4.12.7", https://github.com/scalameta/scalameta/issues/4145
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0",
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16"
    )
  )

lazy val `fh-automation` = project // TODO needed?
  .in(file("modules/fh-automation"))
  .settings(commonSettings)

lazy val `home-codegen` =
  project // using ha-instance-codegen to generate instance code
    .enablePlugins(FHCodegenPlugin)
    .in(file("modules/home-codegen"))
    .dependsOn(`fh-codegen-plugin`, `fh-domain`)
    //   -> run scalafmt on src_managed folder
    .settings(
      commonSettings,
      fhCodegenPluginProject := `fh-codegen-plugin`,
      // Credentials come from `.env` (SERVER/SECRET), read at run time — see
      // `FHApi.fromEnv`. These placeholders only satisfy the task's signature.
      haSecret := "TODO",
      haUrl := "TODO"
    )

lazy val home = project // using the others as if they are libs
  .in(file("modules/home"))
  .dependsOn(`ha-api`, `home-codegen`)
  .settings(
    commonSettings,
    // assembly / assemblyMergeStrategy := {
    //  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    //  case x                             => MergeStrategy.first
    // },
    run / fork := true,
    run / javaOptions ++= Seq(
      "-Dcats.effect.tracing.mode=full"
      // "-Dcats.effect.tracing.buffer.size=1024"
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0"
    )
  )

lazy val `fh-datastar-view` = project
  .in(file("modules/fh-datastar-view"))
  .dependsOn(`ha-api`)
  // The frontend (src/js -> vite -> managed resources). `frontendBundle` is a
  // resource generator, so a plain compile/test/assembly builds it; node+npm
  // are therefore a build requirement for this module.
  .enablePlugins(NpmPlugin)
  .settings(
    commonSettings,
    run / fork := true,
    // DASHBOARDS_DIR for a local `dashboardServe` / `run`: an ABSOLUTE path (via
    // the `baseDirectory` helper) to the repo-root dev workspace, so the forked
    // run — whose cwd is the module base — always lands on ONE stable,
    // gitignored location regardless of cwd. The add-on sets this itself;
    // ServerApp's optional CLI arg still overrides it.
    run / envVars ++= (Test / envFromFile).value,
    run / envVars += "DASHBOARDS_DIR" ->
      ((ThisBuild / baseDirectory).value / "dashboard-local-dev-server").toString,
    // Fat jar for the HA add-on image (home-addon/Dockerfile COPYs it from
    // this fixed, gitignored path).
    assembly / mainClass := Some("fh.view.runtime.ServerApp"),
    // pkl-core embeds Truffle, whose versioned classes only load when the
    // (uber) jar manifest says Multi-Release — without it startup fails with
    // "Truffle could not be initialized".
    assembly / packageOptions +=
      Package.ManifestAttributes("Multi-Release" -> "true"),
    assembly / assemblyOutputPath := Def.uncached(
      (ThisBuild / baseDirectory).value / "target" / "addon" / "fh-dashboard.jar"
    ),
    assembly / assemblyMergeStrategy := {
      // JPMS descriptors from multi-release deps (circe/cats/pkl-core) —
      // meaningless on a flat classpath. Do NOT blanket-discard META-INF:
      // pkl-core and http4s need their META-INF/services entries (the
      // default strategy concatenates those).
      case "module-info.class" => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") =>
        MergeStrategy.discard
      // smithy4s ships duplicate smithy manifests; unused at runtime.
      case PathList("META-INF", "smithy", _*) => MergeStrategy.first
      case x => (assembly / assemblyMergeStrategy).value(x)
    },
    // The `smoke` package is Playwright-driven and is the slowest part of the
    // suite (issue #109 item 3); every test declared through SmokeSuite
    // carries the "Slow" munit tag (see SmokeSuite.test). Default
    // `test`/`testQuick` exclude it; `testFull` (and CI) still run everything
    // via the unfiltered Test/testFull/testOptions.
    Test / testQuick / testOptions +=
      Tests.Argument(MUnitFramework, "--exclude-tags=Slow"),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      // filesystem paths/IO for the build phase (was transitive via sjsonnet)
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      // pkl evaluation for the build phase (pure Java, needs JDK 17+)
      "org.pkl-lang" % "pkl-core" % "0.32.1",
      // already a runtime dep of pkl-core; explicit so PklDump can compile
      // against Lexer.maybeQuoteIdentifier (keep version in lockstep)
      "org.pkl-lang" % "pkl-parser" % "0.32.1",
      // Cross-platform user dirs (XDG / AppData / ~/Library) — the SAME lib +
      // app coordinates the `fh` script uses, so a local `sbt dashboardServe`
      // and the laptop `fh` resolve the same data dir (ADR 0010).
      "net.harawata" % "appdirs" % "1.5.0",
      // mustache templating for runtime value injection (pure Java)
      "com.github.spullara.mustache.java" % "compiler" % "0.9.14",
      // CEL for per-slot value transforms (compile-once/eval-many planner
      // runtime; bundles the extension libraries — string/list/math/bindings/
      // comprehensions — in the same jar).
      "dev.cel" % "cel" % "0.14.0",
      "org.scalameta" %% "munit" % "1.3.5" % Test,
      // Lets tests return IO[Unit] directly (no unsafeRunSync / global runtime)
      // and adds IO-aware assertions (assertIO, IO#assertEquals).
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
      // TestControl.executeEmbed: simulated time for ServerHarness suites
      // (issue #109 item 3) so IO.sleep-based polling in test bodies costs
      // nothing in wall clock instead of needing to be sped up.
      "org.typelevel" %% "cats-effect-testkit" % "3.7.0" % Test,
      // Browser smoke tests (docs/plan-playwright-smoke-tests.md): drives a
      // real Chromium in-JVM against the fixture-backed TestServer.
      "com.microsoft.playwright" % "playwright" % "1.62.0" % Test
    )
  )

// JMH benchmarks, in their own project so neither the benchmark nor jmh-core
// can reach the add-on jar `fh-datastar-view/assembly` builds.
//
// Benchmarks go in `src/main/scala` because that is JmhPlugin's documented
// layout — it instruments the Compile classes. (The README's `src/test`
// variant needs `Jmh / compile := (Jmh / compile).dependsOn(...)`, which sbt
// 2's task cache rejects: `compile` has no `JsonFormat` for its
// `CompileAnalysis` result. A separate project sidesteps that question rather
// than working around it.)
//
// `package fh.view.runtime`, deliberately: what a page open costs is measured
// through `renderPageTraced`, which — like `Traced` and `Painted` — is
// `private[runtime]`. Package-private is checked by PACKAGE, not by
// compilation unit, so a dependent project in that package reaches it and the
// runtime needs no widened API to be measurable.
lazy val benchmarks = project
  .in(file("modules/benchmarks"))
  .dependsOn(`fh-datastar-view`)
  .enablePlugins(JmhPlugin)
  .settings(
    commonSettings,
    publish / skip := true,
    libraryDependencies ++= Seq(
      // The old transform engine, now dev-tooling only: the JSONata REFERENCE
      // for the divergence gate + RenderBench's jsonata cells. The shipped
      // runtime is `fh-datastar-view` (CEL), which no longer depends on it.
      "com.dashjoin" % "jsonata" % "0.9.10",
      // CEL for the `engine-cel` comparison (RenderBench.cel / .celComplex).
      // Dev-tooling only: the shipped runtime is `fh-datastar-view`.
      // Bundles the extension libraries (strings/lists/math/
      // bindings/comprehensions) in the same jar.
      "dev.cel" % "cel" % "0.14.0"
    )
  )

lazy val root = project
  .in(file("."))
  .dependsOn(`ha-api`, `fh-domain`)
  .aggregate(
    `ha-api`,
    `fh-domain`,
    `fh-api`,
    `fh-codegen-plugin`,
    `fh-automation`,
    `home-codegen`,
    `fh-datastar-view`,
    benchmarks,
    home
  )
  .settings(
    name := "Functional home assistant",
    version := "0.1.0-SNAPSHOT",
    commonSettings,
    commands ++= snapshotUpdateCommands,
    // libraryDependencies += ("org.scalameta" %% "scalameta" % "4.11.0")
    // .cross(CrossVersion.for3Use2_13),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    )
  )
