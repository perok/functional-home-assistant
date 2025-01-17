import sbt.Keys._
import sbt._
import sbt.io.{IO, Path}
import sbt.nio.file.FileTreeView

// https://stackoverflow.com/a/23416018
trait NpmPlugin {
  lazy val frontendDirectory = settingKey[File]("Root of the frontend")
  lazy val frontendBuildOutputDirectory =
    settingKey[File]("Where the build will output files")
  lazy val frontendResourceOutputDirectory =
    settingKey[File]("Where in the JVM directory the output will be placed")

  lazy val frontendInstall =
    TaskKey[Seq[File]](
      "frontendInstall",
      "Install Yarn dependencies in frontend directory"
    )

  lazy val frontendBuild =
    TaskKey[Seq[File]]("frontendBuild", "Build the Yarn project")
}

object NpmPlugin extends AutoPlugin {
  override val trigger: PluginTrigger = noTrigger
  override val requires: Plugins = plugins.JvmPlugin

  object autoImport extends NpmPlugin
  import autoImport._

  // How to handle separate classpath for another tool: https://www.scala-sbt.org/1.x/docs/Faq.html#How+should+I+express+a+dependency+on+an+outside+tool+such+as+proguard%3F
  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Set default values
    frontendDirectory := baseDirectory.value / "src" / "frontend",
    frontendBuildOutputDirectory := frontendDirectory.value / "build",
    frontendResourceOutputDirectory := (Compile / resourceManaged).value / "generated" / "frontend",

    // Define tasks
    frontendInstall := frontendInstallTask.value,
    frontendBuild := frontendBuildTask.value,
    frontendBuild := (frontendBuild dependsOn frontendInstall).value,

    // Tell SBT to generate resources
    Compile / resourceGenerators += frontendBuild.taskValue
  )

  private def frontendInstallTask = Def.task {
    import sys.process._
    val s = streams.value
    val logger = s.log

    val _frontendDirectory = frontendDirectory.value

    val inputFiles = FileTreeView.default
      .list(
        Seq(
          _frontendDirectory.toGlob / "package.json",
          _frontendDirectory.toGlob / "package-lock.json",
          _frontendDirectory.toGlob / "elm.json",
          _frontendDirectory.toGlob / "elm-git.json"
        )
      )
      .map(_._1.toFile)

    val cachedFun = FileFunction.cached(
      s.cacheDirectory / "task-install-frontend",
      // Something is touching the files.
      // Therefore we use hash instead
      inStyle = FilesInfo.hash,
      outStyle = FilesInfo.exists
    ) { (a: Set[File]) =>
      val resultCode =
        Process("npm install", _frontendDirectory) ! logger

      if (resultCode != 0) {
        sys.error("Failed to install frontend dependencies")
      }

      FileTreeView.default
        .list(
          Seq(
            _frontendDirectory.toGlob / "node_modules" / ** / *,
            _frontendDirectory.toGlob / "elm-stuff" / ** / *
          )
        )
        .map(_._1.toFile)
        .toSet
    }

    cachedFun(inputFiles.toSet).toSeq
  }

  private def frontendBuildTask = Def.task {
    import sys.process._
    val s = streams.value
    val logger = s.log

    val _frontendDirectory = frontendDirectory.value

    val inputFiles = FileTreeView.default
      .list(
        Seq(
          _frontendDirectory.toGlob / "src" / ** / *,
          _frontendDirectory.toGlob / "test" / ** / *,
          _frontendDirectory.toGlob / "public" / ** / *,
          _frontendDirectory.toGlob / "elmapp.config.js",
          _frontendDirectory.toGlob / "package.json",
          _frontendDirectory.toGlob / "package-lock.json",
          _frontendDirectory.toGlob / "elm.json",
          _frontendDirectory.toGlob / "elm-git.json"
        )
      )
      .map(_._1.toFile)

    val outputDirectory = frontendResourceOutputDirectory.value

    val cachedFun =
      FileFunction.cached(s.cacheDirectory / "task-build-frontend") {
        (_: Set[File]) =>
          val allowDebugExpressions =
            sys.env.getOrElse("ENVIRONMENT", "") == "development";

          val notOptimizeFlag =
            if (allowDebugExpressions) "--no-optimize" else "";

          val command =
            s"npm run build -- --dist-dir ${outputDirectory.toString} ${notOptimizeFlag}";

          val resultCode = Process(command, _frontendDirectory) ! logger

          if (resultCode != 0) {
            sys.error(
              s"Failed to run '$command' for the frontend, exit code $resultCode."
            )
          }

          IO.copyDirectory(
            frontendBuildOutputDirectory.value,
            outputDirectory,
            overwrite = true
          )

          Path
            .allSubpaths(outputDirectory)
            .map(_._1)
            .filterNot(_.isDirectory)
            .toSet
      }

    cachedFun(inputFiles.toSet).toSeq
  }
}
