import FHCodegenPlugin.autoImport.*
import smithy4s.codegen.Smithy4sCodegenPlugin
import org.typelevel.scalacoptions.ScalacOptions

val http4sVersion = "0.23.34"

val commonSettings = Seq(
  scalaVersion := "3.8.4",
  tpolecatExcludeOptions ++= Set(
    ScalacOptions.warnError
  ),
  //Test / tpolecatExcludeOptions ++= Set(
  //  ScalacOptions.fatalWarnings
  //),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.7.0",
    "io.scalaland" %% "chimney" % "1.10.0",
    "com.lihaoyi" %% "pprint" % "0.9.6"
  )
)
addCommandAlias("doCodegen", "; fhTaskCodeGen ; home-codegen / scalafmt")
// Datastar dashboard: build phase (regenerate dashboard.json) and runtime server.
addCommandAlias("dashboardBuild", "fh-datastar-view/runMain fh.view.build.BuildApp")
addCommandAlias("dashboardServe", "fh-datastar-view/runMain fh.view.runtime.ServerApp")

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
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15",
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0"
    )
  )

lazy val `fh-domain` = project
  .in(file("modules/fh-domain"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "shapeless3-deriving" % "3.6.0",
      "io.circe" %% "circe-core" % "0.14.15",
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
      "org.scalameta" %% "scalafmt-core" % "3.11.1", // check latest version
      // "org.scalameta" %% "scalameta" % "4.12.7", https://github.com/scalameta/scalameta/issues/4145
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0",
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15"
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
      haSecret := "TODO", // envVars.value.apply("SECRET"), // TODO SWAP TO SERVER AND SECRET
      haUrl := "TODO" //envVars.value.apply("SERVER"), // TODO SWAP TO SERVER AND SECRET
      //haSecret := secretToken, // TODO SWAP TO SERVER AND SECRET
      //haUrl := haServer // from .env SERVER (default http://192.168.1.174:8123)
    )

lazy val home = project // using the others as if they are libs
  .in(file("modules/home"))
  .dependsOn(`ha-api`, `home-codegen`)
  .settings(
    commonSettings,
    //assembly / assemblyMergeStrategy := {
    //  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    //  case x                             => MergeStrategy.first
    //},
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
  .settings(
    commonSettings,
    run / fork := true,
    //run / envVars := Map(
    //  "SERVER" -> (`home-codegen` / haUrl).value,
    //  "SECRET" -> secretToken
    //),
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
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15",
      // filesystem paths/IO for the build phase (was transitive via sjsonnet)
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      // pkl evaluation for the build phase (pure Java, needs JDK 17+)
      "org.pkl-lang" % "pkl-core" % "0.31.1",
      // already a runtime dep of pkl-core; explicit so PklDump can compile
      // against Lexer.maybeQuoteIdentifier (keep version in lockstep)
      "org.pkl-lang" % "pkl-parser" % "0.31.1",
      // mustache templating for runtime value injection (pure Java)
      "com.samskivert" % "jmustache" % "1.16",
      // JSONata for per-slot value transforms (pure-JVM port of the spec)
      "com.dashjoin" % "jsonata" % "0.9.8",
      "org.scalameta" %% "munit" % "1.3.3" % Test
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
    home
  )
  .settings(
    name := "Functional home assistant",
    version := "0.1.0-SNAPSHOT",
    commonSettings,
    // libraryDependencies += ("org.scalameta" %% "scalameta" % "4.11.0")
    // .cross(CrossVersion.for3Use2_13),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.3" % Test,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    )
  )
