package fh.view.build

import scala.jdk.CollectionConverters.*

/** The bundled `@fh-dashboard` library, read straight off the RUNNING jar's own
  * classpath resources — dev: the sbt-copied `target/…/classes/dashboards/lib`;
  * the add-on: inside the fat jar. So the jar is the single source of the lib
  * bytes: no `/opt/fh/lib` COPY in the image, no `FH_BUNDLED_LIB` path to keep
  * in sync (ADR 0010).
  *
  * Emits the same `(zip-relative-name, bytes)` entries [[LibPackage.build]]
  * consumes, so the content version is BYTE-identical whether built from here
  * or from a directory (a test's repo `lib/`) — dev `sbt dashboardServe` and the
  * assembled jar therefore seed the exact same `@fh-dashboard@<v>`.
  */
object BundledLib {

  private val LibResourcePrefix = "dashboards/lib"

  /** Built artifacts for the classpath-bundled lib. */
  def artifacts(): LibPackage.Artifacts = LibPackage.build(entries())

  /** Every module under `dashboards/lib/` as `(name, bytes)` — `name` relative
    * to the lib root (matching a dir walk's `relativeTo`), including
    * `PklProject` (LibPackage reads its `version` and excludes it from the zip).
    */
  def entries(): Seq[(String, Array[Byte])] = {
    val cl =
      Option(getClass.getClassLoader).getOrElse(ClassLoader.getSystemClassLoader)
    // Anchor on a file that always exists; its URL tells us dir vs jar.
    val marker = Option(cl.getResource(s"$LibResourcePrefix/PklProject"))
      .getOrElse(
        sys.error(
          s"bundled lib not on the classpath ($LibResourcePrefix/PklProject missing)"
        )
      )
    marker.getProtocol match {
      case "file" =>
        // Dev / exploded classpath: the lib is a real directory (the marker's
        // parent). Reuse the same walk a dir build does.
        val libDir = os.Path(java.nio.file.Paths.get(marker.toURI)) / os.up
        os.walk(libDir)
          .filter(os.isFile)
          .map(f => f.relativeTo(libDir).toString -> os.read.bytes(f))
      case "jar" =>
        // Fat jar: list the zip entries under the lib prefix. Do NOT close the
        // shared JarFile (the classloader owns it).
        val conn =
          marker.openConnection().asInstanceOf[java.net.JarURLConnection]
        val jar = conn.getJarFile
        val prefix = s"$LibResourcePrefix/"
        jar
          .entries()
          .asScala
          .filter(e => !e.isDirectory && e.getName.startsWith(prefix))
          .map { e =>
            val is = jar.getInputStream(e)
            val bytes =
              try is.readAllBytes()
              finally is.close()
            e.getName.stripPrefix(prefix) -> bytes
          }
          .toList
      case other =>
        sys.error(s"unsupported classpath protocol for bundled lib: $other")
    }
  }
}
