package fh.view.build

import scala.jdk.CollectionConverters.*

/** The lib off the running classpath, dev directory or fat jar alike, so the
  * jar is the only source of its bytes (ADR 0010). Entries match a directory
  * walk, so both mint the same version.
  */
object BundledLib {

  private val LibResourcePrefix = "dashboards/lib"

  def artifacts(): LibPackage.Artifacts = LibPackage.build(entries())

  def entries(): Seq[(String, Array[Byte])] = {
    val cl =
      Option(getClass.getClassLoader).getOrElse(
        ClassLoader.getSystemClassLoader
      )
    val marker = Option(cl.getResource(s"$LibResourcePrefix/PklProject"))
      .getOrElse(
        sys.error(
          s"bundled lib not on the classpath ($LibResourcePrefix/PklProject missing)"
        )
      )
    marker.getProtocol match {
      case "file" =>
        val libDir = os.Path(java.nio.file.Paths.get(marker.toURI)) / os.up
        os.walk(libDir)
          .filter(os.isFile)
          .map(f => f.relativeTo(libDir).toString -> os.read.bytes(f))
      case "jar" =>
        // Not closed: the classloader owns the JarFile.
        val jar = marker.openConnection() match {
          case conn: java.net.JarURLConnection => conn.getJarFile
          case other                           =>
            throw new IllegalStateException(
              s"a jar: URL opened as ${other.getClass.getName}, not a JarURLConnection"
            )
        }
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
