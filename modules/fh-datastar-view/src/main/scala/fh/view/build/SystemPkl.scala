package fh.view.build

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError

/** `/system/pkl/…` for external consumers (the `fh` script, pkl-lsp, the
  * editor). The server's own eval resolves offline from the seeded cache, so
  * there is no self-import cycle (ADR 0010).
  */
trait SystemPkl {

  def module(name: String): IO[String]

  /** `<name>@<version>` (metadata) or `<name>@<version>.zip`: every version the
    * cache holds, so a laptop pinned to an older lib still resolves.
    */
  def packageArtifact(
      @annotation.unused file: String
  ): IO[Array[Byte]] =
    FHError.notFound("this home serves no packages").raiseError[IO, Array[Byte]]

  /** What `fh pull` reads before rewriting a laptop's pins. */
  def packagesIndex: IO[String] =
    FHError.notFound("this home serves no packages").raiseError[IO, String]
}

object SystemPkl {

  /** In-memory, for tests; by-name so a live `Ref` is re-read per call. */
  def apply(hass: => Option[String], dump: => Option[String]): SystemPkl = {
    case "hass.pkl" =>
      hass.liftTo[IO](FHError.notFound("hass.pkl is not available"))
    case "dump.pkl" =>
      dump.liftTo[IO](FHError.notFound("dump.pkl is not available"))
    case name =>
      FHError.notFound(s"no module named '$name'").raiseError[IO, String]
  }

  val empty: SystemPkl = apply(None, None)

  /** `dump.pkl` is extracted from the currently pinned package, read per
    * lookup; `hass.pkl` only ships inside `@fh-dashboard`.
    */
  def fromDisk(dashboardsDir: os.Path): SystemPkl = {
    val cache = PklBuild.workspaceCacheDir(dashboardsDir)
    new SystemPkl {
      def module(name: String): IO[String] =
        name match {
          case "dump.pkl" =>
            IO.blocking(Pins.homeVersion(dashboardsDir)).flatMap {
              case None =>
                FHError
                  .notFound("no dump yet — this home has not been built")
                  .raiseError[IO, String]
              case Some(version) =>
                val ref = PackageRef(DumpPackage.Name, version)
                readZipEntry(ref.entryDir(cache) / ref.zipName, "dump.pkl")
                  .flatMap(
                    _.liftTo[IO](
                      FHError.notFound(
                        s"dump package $version is not in the cache"
                      )
                    )
                  )
            }
          case "hass.pkl" =>
            FHError
              .notFound(
                "hass.pkl is not a standalone module — it ships inside the " +
                  "@fh-dashboard package; resolve it via the packages route"
              )
              .raiseError[IO, String]
          case _ =>
            FHError.notFound(s"no module named '$name'").raiseError[IO, String]
        }
      // Internal, not notFound: startup seeds both packages before any route
      // serves.
      override def packagesIndex: IO[String] =
        IO.blocking(DumpPackage.index(dashboardsDir))
          .flatMap(
            _.liftTo[IO](
              FHError.internal(
                "package index unavailable — this workspace was not " +
                  "bootstrapped (no lib package seeded / no dump written)"
              )
            )
          )
      override def packageArtifact(file: String): IO[Array[Byte]] = {
        val (base, suffix) =
          if (file.endsWith(".zip")) (file.dropRight(4), ".zip")
          else (file, ".json")
        // This indexes into the filesystem, so check the shape even though the
        // router already splits on `/`.
        if (!ArtifactBase.matches(base))
          FHError
            .badCondition(s"invalid artifact name: $file")
            .raiseError[IO, Array[Byte]]
        else {
          val path = PackageRef.entryDir(cache, base) / s"$base$suffix"
          IO.blocking(Option.when(os.exists(path))(os.read.bytes(path)))
            .flatMap(
              _.liftTo[IO](FHError.notFound(s"no such artifact: $file"))
            )
        }
      }
    }
  }

  private def readZipEntry(
      zipPath: os.Path,
      entryName: String
  ): IO[Option[String]] =
    IO.blocking {
      Option
        .when(os.exists(zipPath)) {
          val zf = new java.util.zip.ZipFile(zipPath.toIO)
          try
            Option(zf.getEntry(entryName)).map { e =>
              val is = zf.getInputStream(e)
              try new String(is.readAllBytes(), "UTF-8")
              finally is.close()
            }
          finally zf.close()
        }
        .flatten
    }

  private val ArtifactBase =
    """[A-Za-z0-9][A-Za-z0-9._+-]*@[A-Za-z0-9][A-Za-z0-9._+-]*""".r
}
