import FHCodegenPlugin.autoImport.*
import smithy4s.codegen.Smithy4sCodegenPlugin

val http4sVersion = "0.23.34"
// log4cats already arrives transitively (http4s logs through it); named here
// so the version the app logs on is chosen rather than inherited.
val log4catsVersion = "2.8.0"
val otel4sVersion = "1.1.0"
// Pinned to what otel4s-oteljava resolves, so the exporter cannot drift from
// the SDK it plugs into. otel4s' own SDK modules are NOT used: they moved to a
// separate repo and are still marked experimental.
val otelJavaVersion = "1.65.0"
val otelMiddlewareVersion = "0.18.0"
// The ONLY place a GraalVM version is written, for the polyglot jars AND for
// the isolate libraries the image stages — both resolved from this one string
// below, so they cannot disagree. They must not: a mismatched library and jar
// pair is not reported, it just runs as a different GraalJS than this names.
val graalVmVersion = "25.3.4.1"

// The isolate libraries are RESOLVED but never on a classpath. `hide` keeps
// this configuration out of compile, test and assembly, so the fat jar stays
// architecture-independent (159 MB of `.so` per platform would otherwise land
// in it twice) while coursier still fetches, checksums and caches them like
// any other dependency, and `stageIsolateJars` hands them to the image build.
lazy val JsIsolate = config("js-isolate").hide

lazy val stageIsolateJars = taskKey[Seq[File]](
  "Stage both platforms' GraalJS isolate jars beside the add-on jar"
)

// The hand-off point to the add-on image build, named once at BUILD level
// because it belongs to no single module: `home-addon/Dockerfile` COPYs out of
// it (its paths are relative to the repo root, which is the build context),
// and CI ships it between jobs as the `addon-jar` artifact. Anything that
// stages a file for the image writes here — which is also why the path is not
// a module's own `target`, and why it is a setting rather than three
// hand-stitched copies of the same string.
lazy val addonStage =
  settingKey[File]("Where the add-on image build picks its inputs up")
ThisBuild / addonStage := (ThisBuild / baseDirectory).value / "target" / "addon"
val MUnitFramework = new TestFramework("munit.Framework")

// Warnings are advisory while you work and fatal where the flag says so (#115).
// sbt-tpolecat defaults to `CiMode` — fatal — in EVERY invocation, which is why
// this build used to cancel it by excluding `warnError` outright; that made the
// warnings unenforceable anywhere, and #285 (a missing `else` that blanked a
// card) was named by `-Wvalue-discard` and shipped regardless.
//
// The DEFAULT is advisory and the flag is `SBT_TPOLECAT_CI` — the plugin's own
// env var, so nothing here re-reads the environment; `cicd.yml` sets it in the
// job env, which is the one place to look to know what CI enforces. It is not
// inferred from a generic `CI`, so a local run only becomes fatal when someone
// asks for it:
//
//   SBT_TPOLECAT_CI=1 sbt clean compile   # a fresh server, exactly like CI
//   sbt tpolecatCiMode <task>             # in an already-running server
//
// The second form is the one that works day to day: the env is read when the
// build LOADS, so exporting the variable at a shell that talks to a running sbt
// server changes nothing.
//
// `ThisBuild`, not `commonSettings`: the key is a tpolecat BUILD setting, and a
// project-scoped copy is simply never read (measured — the mode stayed
// `CiMode`).
ThisBuild / tpolecatDefaultOptionsMode := org.typelevel.sbt.tpolecat.DevMode

// Scalafix's other half of #115: `sbt scalafixAll` applies `.scalafix.conf`.
// `RemoveUnused` reads the compiler's own -Wunused findings out of semanticdb,
// so it deletes exactly what the gate would fail on.
ThisBuild / semanticdbEnabled := true
ThisBuild / scalafixDependencies += "org.typelevel" %% "typelevel-scalafix" % "0.5.0"

val commonSettings = Seq(
  scalaVersion := "3.9.0",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.7.1",
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
  // `sbt 'dashboardServe <dir>'` — the directory is REQUIRED (the alias
  // forwards the rest of the input; the forked run's cwd is the repo root, so a
  // relative path resolves from where sbt was started). The build sets no
  // `DASHBOARDS_DIR`: a default meant every run served one blessed workspace
  // whether or not that was the one you meant, and the add-on names its own in
  // `run.sh` anyway.
  //
  // The run bootstraps a package-form workspace there — its own home/, .fh/,
  // seeded entries, .pkl-cache — gitignored, exactly the shape the add-on
  // writes. So naming a directory that does not exist yet is how you get a
  // fresh scratch workspace, not an error.
  "dashboardServe",
  // stagePklLsp first: the editor's LSP runs the staged pkl-lsp jar, which
  // `run / envVars` points PKL_LSP_JAR at.
  "; pkl-lsp-dist/stagePklLsp ; fh-datastar-view/runMain fh.view.runtime.ServerApp"
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
    // smithy4s spells a union's discriminator `$ordinal`, mirroring the name the
    // compiler itself generates for a sealed hierarchy — and Scala 3.9 started
    // warning (E230) that `$` is reserved for exactly that internal use. 14 of
    // them, in `src_managed` only, and `-Werror` makes them a build failure.
    // Scoped to the generated tree rather than excluding `warnError` for the
    // module (what `home-codegen` does): the hand-written WebSocket client lives
    // here too and is the half of this project the gate is for.
    scalacOptions += "-Wconf:src=.*src_managed.*&id=E230:s",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "org.typelevel" %% "cats-effect" % "3.7.1"
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0",
      // Logging. No backend here — this is a library; `fh-datastar-view`
      // brings the one binding the whole app logs through.
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
    ),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
      // Test-only: without a binding on the classpath slf4j prints two
      // "Failed to load class ... StaticLoggerBinder" lines at the top of
      // every run of this module's suites. The app's binding is not visible
      // here, because a library must not impose one.
      "ch.qos.logback" % "logback-classic" % "1.6.3" % Test
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

// The pkl-lsp CLI jar, resolved by the build instead of downloaded at runtime.
//
// NOTHING depends on this project, deliberately. pkl-lsp is published only as a
// shaded CLI fat jar that bundles its own UNRELOCATED JNA (5.14.0, via clikt's
// terminal lib) — putting it on fh-datastar-view's classpath collides with
// appdirs' JNA 5.18.1 and fails `assembly` outright. It is a subprocess, so it
// belongs beside the app as a plain file, not on its classpath.
// Unit-returning: sbt 2.0 rejects File/Path as a cached task's output type.
val stagePklLsp = taskKey[Unit]("Copy the pkl-lsp CLI jar to target/addon/")

lazy val `pkl-lsp-dist` = project
  .in(file("modules/pkl-lsp-dist"))
  .settings(
    scalaVersion := "3.8.4",
    libraryDependencies += "org.pkl-lang" % "pkl-lsp" % "0.8.0",
    stagePklLsp := {
      val conv = fileConverter.value
      val jars = (Compile / dependencyClasspath).value
        .map(a => conv.toPath(a.data).toFile)
        .filter(_.getName.startsWith("pkl-lsp-"))
      val jar = jars.headOption.getOrElse(
        sys.error(s"pkl-lsp jar not on the classpath (found: $jars)")
      )
      val dest = (ThisBuild / addonStage).value / "pkl-lsp.jar"
      IO.copyFile(jar, dest)
      streams.value.log.info(s"staged ${jar.getName} -> $dest")
    }
  )

lazy val `home-codegen` =
  project // using ha-instance-codegen to generate instance code
    .enablePlugins(FHCodegenPlugin)
    .in(file("modules/home-codegen"))
    .dependsOn(`fh-codegen-plugin`, `fh-domain`)
    //   -> run scalafmt on src_managed folder
    .settings(
      commonSettings,
      // The one project the warning gate does not apply to: every source here
      // is written by `fhTaskCodeGen` and wiped by the next run, so a warning
      // is a bug report against the GENERATOR and there is nothing in this
      // directory to fix. It has one today — two HA devices whose names differ
      // only in case mint objects that collide on a case-insensitive
      // filesystem. CI never compiles this project (it needs a live HA), so
      // this only keeps a local `SBT_TPOLECAT_CI=1` run honest.
      tpolecatExcludeOptions += org.typelevel.scalacoptions.ScalacOptions.warnError,
      // Scalafix skips it for a blunter reason: generated names carry `:`
      // (`hci0-(DC:A6:32:2D:13:3A).scala`), and scalafix turns a relative path
      // into a URI, so those files abort the whole run with
      // `URISyntaxException: Illegal character in scheme name`.
      Compile / scalafix / unmanagedSources := Nil,
      Test / scalafix / unmanagedSources := Nil,
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
    // No DASHBOARDS_DIR here on purpose: a local run serves the workspace it is
    // GIVEN (`sbt 'dashboardServe <dir>'`), and refuses to guess one. See the
    // `dashboardServe` alias above.
    run / envVars ++= (Test / envFromFile).value,
    // The pkl-lsp CLI jar the build stages. The add-on image sets the same
    // variable at its own path; nothing downloads it at runtime.
    run / envVars += "PKL_LSP_JAR" ->
      ((ThisBuild / addonStage).value / "pkl-lsp.jar").toString,
    // Fat jar for the HA add-on image (home-addon/Dockerfile COPYs it from
    // this fixed, gitignored path).
    assembly / mainClass := Some("fh.view.runtime.ServerApp"),
    // pkl-core embeds Truffle, whose versioned classes only load when the
    // (uber) jar manifest says Multi-Release — without it startup fails with
    // "Truffle could not be initialized".
    assembly / packageOptions +=
      Package.ManifestAttributes("Multi-Release" -> "true"),
    assembly / assemblyOutputPath := Def.uncached(
      (ThisBuild / addonStage).value / "fh-dashboard.jar"
    ),
    ivyConfigurations += JsIsolate,
    // Named by BUILDX's architecture spelling, not GraalVM's (`arm64`, not
    // `aarch64`), and with the version dropped. That is the whole reason this
    // renames at all: the Dockerfile can then say `js-isolate-$TARGETARCH.jar`
    // and contain no version, no coordinate and no architecture mapping.
    //
    // `Def.uncached` for the reason `NpmPlugin` documents: sbt 2 caches task
    // results by default and a `File` is not a valid cached output, because
    // the graph cannot see whether the file is still where it was put.
    stageIsolateJars := {
      val out = (ThisBuild / addonStage).value
      val resolved = update.value.select(configurationFilter(JsIsolate.name))
      Def.uncached {
        IO.createDirectory(out)
        Seq("amd64" -> "amd64", "arm64" -> "aarch64").map {
          (dockerArch, graalArch) =>
            val source = resolved
              .find(_.getName.startsWith(s"js-isolate-linux-$graalArch-"))
              .getOrElse(
                sys.error(s"no js-isolate-linux-$graalArch jar resolved")
              )
            val target = out / s"js-isolate-$dockerArch.jar"
            // 140 MB of copying on every `assembly` otherwise, and `assembly`
            // runs constantly. The stamp records the SOURCE's name, which is
            // the only thing here that carries a version: the target's name
            // deliberately does not, so "same name, same size" would compare a
            // 25.3.4.1 jar against a 25.2.4 one and skip on a collision. That
            // is the silent library/jar drift of
            // docs/issue-report-3-graalvm-polyglot-isolate.md, self-inflicted.
            val stamp = out / s"js-isolate-$dockerArch.source"
            val want = source.getName
            if (
              !target.exists || !stamp.exists || IO.read(stamp).trim != want
            ) {
              IO.copyFile(source, target)
              IO.write(stamp, want)
            }
            target
        }
      }
    },
    // So `sbt fh-datastar-view/assembly` leaves a build context the Dockerfile
    // can use, rather than one that is complete only if you knew to ask.
    assembly := assembly.dependsOn(stageIsolateJars).value,
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
      // OSGi bundle metadata (okhttp, jspecify, the otel exporters) — read by
      // an OSGi container, and there is none here.
      case path if path.endsWith("OSGI-INF/MANIFEST.MF") =>
        MergeStrategy.discard
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
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      // filesystem paths/IO for the build phase
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
      // GraalJS, run in a polyglot ISOLATE (docs/plan-graaljs-isolate.md).
      // Note what is NOT here: no js-language and no Truffle runtime. The
      // JavaScript lives entirely in the isolate library, which the add-on
      // image stages outside the jar so the jar stays the same bytes on both
      // architectures.
      //
      // Nearly free in the fat jar, because pkl-core already brings polyglot
      // and truffle-api — at 25.0.1, which these evict. So the four lines buy
      // a Truffle version bump and three small jars, not 18 MB of new ones.
      //
      // The last three are what `org.graalvm.js:js-isolate-linux-<arch>` would
      // bring transitively. Named here because depending on that artifact is
      // exactly what we are avoiding: its payload is a 159 MB
      // per-architecture `.so`, which would land in the fat jar twice.
      "org.graalvm.polyglot" % "polyglot" % graalVmVersion,
      "org.graalvm.truffle" % "truffle-api" % graalVmVersion,
      "org.graalvm.sdk" % "nativebridge" % graalVmVersion,
      "org.graalvm.sdk" % "jniutils" % graalVmVersion,
      // The libraries themselves, in the hidden configuration above: staged
      // into the image per architecture, never onto a classpath here.
      "org.graalvm.js" % "js-isolate-linux-amd64" % graalVmVersion % JsIsolate,
      "org.graalvm.js" % "js-isolate-linux-aarch64" % graalVmVersion % JsIsolate,
      // Logging, and the ONE slf4j binding in the build. log4cats and an
      // unbound slf4j-api were already on the classpath via http4s, which
      // means http4s' own logging went nowhere; logback lights that up too.
      // Kept in this module because it assembles the add-on jar — a binding
      // belongs to the application, not to a library.
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % "1.6.3",
      "org.typelevel" %% "otel4s-oteljava" % otel4sVersion,
      "org.http4s" %% "http4s-otel4s-middleware-trace-server" % otelMiddlewareVersion,
      "org.http4s" %% "http4s-otel4s-middleware-trace-client" % otelMiddlewareVersion,
      "org.http4s" %% "http4s-otel4s-middleware-metrics" % otelMiddlewareVersion,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-client" % http4sVersion,
      // Runtime-only on purpose: nothing compiles against it, autoconfigure
      // picks it at boot, and it stays unloaded unless an endpoint is set.
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % otelJavaVersion % Runtime,
      // In-memory span and log-record exporters. The only way to assert that a
      // log record carries the span it was written inside — which is the whole
      // claim `fh.view.runtime.Logging` makes, and otel4s ships no logs testkit
      // to check it with.
      "io.opentelemetry" % "opentelemetry-sdk-testing" % otelJavaVersion % Test,
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      // Lets tests return IO[Unit] directly (no unsafeRunSync / global runtime)
      // and adds IO-aware assertions (assertIO, IO#assertEquals).
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test,
      // Property-based testing for the digest biconditional (ADR 0029):
      // equal input digest ⟺ equal patch bytes must hold over GENERATED node
      // shapes, because a missed input fails silently and permanently.
      "qa.hedgehog" %% "hedgehog-core" % "0.14.0" % Test,
      "qa.hedgehog" %% "hedgehog-runner" % "0.14.0" % Test,
      "qa.hedgehog" %% "hedgehog-munit" % "0.14.0" % Test,
      // TestControl.executeEmbed: simulated time for ServerHarness suites
      // (issue #109 item 3) so IO.sleep-based polling in test bodies costs
      // nothing in wall clock instead of needing to be sped up.
      "org.typelevel" %% "cats-effect-testkit" % "3.7.1" % Test,
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
  // Also on the TEST config: `RendererTestOps` is where the buffered
  // convenience forms of the render entry points live now that no production
  // caller reaches them, and the page benchmarks are the other caller. One copy
  // of that shim, not a second one here.
  .dependsOn(`fh-datastar-view` % "compile->compile;compile->test")
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
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    )
  )
