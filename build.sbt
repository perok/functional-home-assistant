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
      haSecret := secretToken,
      haUrl := "http://192.168.1.174:8123" // jmdns for mdns in java?
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
    run / envVars := Map(
      "SERVER" -> (`home-codegen` / haUrl).value,
      "SECRET" -> secretToken
    ),
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
    run / envVars := Map(
      "SERVER" -> (`home-codegen` / haUrl).value,
      "SECRET" -> secretToken
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15",
      // jsonnet evaluation for the build phase (pure-JVM)
      "com.databricks" %% "sjsonnet" % "0.6.3",
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

val secretToken =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJjN2ExZmZmYjgxMjE0YTQzODM3NTA5YjVmMjgzMGVkZSIsImlhdCI6MTc4MTY5NTc4MCwiZXhwIjoyMDk3MDU1NzgwfQ.qVaj37vsmLVy6PPId2D0d4YxdMdAn2zngS_iGPTi33c"
  //"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiIwMmU0ZTJkNzFkNmU0MDYyODhjOWRkMTc1NTU2ZjgyOSIsImlhdCI6MTczMDkyMjA0MCwiZXhwIjoyMDQ2MjgyMDQwfQ.X59FBGhVGBWOxEmvgRF-A6SHpvsErJFemqLFU0TtMgU"
