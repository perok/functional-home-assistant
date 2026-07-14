import sbt.*
import Keys.*

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

object FHCodegenPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    lazy val fhOutputDirectory = settingKey[File]("Output directory")
    lazy val fhCodegenPluginProject = settingKey[Project]("project")
    lazy val haSecret = settingKey[String]("HA secret")
    lazy val haUrl = settingKey[String]("HA URL")
    lazy val fhTaskCodeGen =
      taskKey[Seq[File]]("Create unmanaged source from HA instance")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    haUrl := "http://homeassistant.local:8123",
    // fhOutputDirectory := (Compile / sourceManaged).value,
    // Need to output to unmanaged source directory since we don't have anything to "cache from"
    fhOutputDirectory := (Compile / scalaSource).value,
    // TODO Could we trigger this when dependencies changes? and on first compile?
    fhTaskCodeGen := Def.uncached {
      Def.taskDyn {
        val dir = fhOutputDirectory.value
        val project = fhCodegenPluginProject.value
        val url = haUrl.value
        val secret = haSecret.value

        try {
          Files.walkFileTree(
            dir.toPath,
            new SimpleFileVisitor[Path] {
              override def visitFile(
                  file: Path,
                  attrs: BasicFileAttributes
              ): FileVisitResult = {
                Files.deleteIfExists(file)
                super.visitFile(file, attrs)
              }

              override def postVisitDirectory(
                  dir: Path,
                  exc: IOException
              ): FileVisitResult = {
                Files.deleteIfExists(dir)
                super.postVisitDirectory(dir, exc)
              }
            }
          )
        } catch {
          case e => e.printStackTrace()
        }
        Def.task {
          val result = (Compile / runMain)
            .toTask(
              s" fh.codegen.Plugin $dir $url $secret"
            )
            .value

          (dir ** "*.scala").get()
        }
      }.value
    }
    /*    Compile / sourceGenerators += Def.taskDyn {
      val dir = fhOutputDirectory.value
      val project = fhProject.value
      val url = haUrl.value
      val secret = haSecret.value

      val store = streams.value.cacheStoreFactory.make("something")
      import sbt._, sbt.util.CacheImplicits._

      val taskListFiles = Def.task {
        (dir ** "*.scala").get()
      }
      val taskRerun = Def.task {
        // streams.value.cacheStoreFactory.make("something")
        // val store = sbt.util.CacheStore(file("/tmp/something"))
        // Cache.cached(store)(doWork)

        // cache with https://www.scala-sbt.org/1.x/docs/Caching.html#Tracked.inputChanged ?

        val apiKey = ""

        // https://www.scala-sbt.org/1.x/docs/Howto-Generating-Files.html
        // https://stackoverflow.com/a/47441616

        /*
     * Note: For the efficiency of the build, sourceGenerators should avoid regenerating source files upon each call. Instead, the outputs should be cached based on the input values either using the File tracking system or by manually tracking the input values using sbt.Tracked.{ inputChanged, outputChanged } etc.
     * TODO how??
     */

        val result = (project / Compile / runMain)
          .toTask(
            s" fh.codegen.Plugin $dir $url $secret"
          )
          .value

        (dir ** "*.scala").get()
      }

      // TODO filecache on ha-api and fh-domain files?
      val bb: Int => Def.Initialize[Task[Seq[File]]] =
        Tracked.inputChanged[Int, Def.Initialize[Task[Seq[File]]]](store) {
          case (changed, _) =>
            if (changed) {
              println("input changed")
            }
            ???
        }

      val cc = fhPluginCacheCounter.value
      bb(cc)
    }*/
  )
}
