package fh.view.runtime

import cats.effect.{ExitCode, IO, IOApp}

import java.io.OutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardOpenOption.{CREATE, TRUNCATE_EXISTING, WRITE}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.HexFormat
import java.util.zip.ZipFile
import scala.util.Using

/** Downloads the GraalJS isolate library that matches THIS jar, for a named
  * architecture. `home-addon/Dockerfile` runs it; nothing else does.
  *
  * A build-time tool inside the runtime jar looks misplaced until you ask what
  * decides the version. The library and the polyglot classes are two halves of
  * one engine and a mismatched pair is not reported — it runs, silently, as a
  * different GraalJS than the build declares. Anything that states the version
  * a second time (a file the Dockerfile reads, a build argument) can be right
  * about the repository and wrong about the jar actually being packaged. So the
  * jar reads its own: the version comes out of the classpath resource Truffle
  * itself version-checks against, and there is no second copy to keep honest.
  */
object JsIsolateFetch extends IOApp {

  /** Written by every GraalVM polyglot artifact, and unique in the assembled
    * jar — a second, differing copy would fail the assembly rather than be
    * picked between.
    */
  private val versionResource = "/META-INF/graalvm/org.graalvm.polyglot/version"

  private val central = "https://repo1.maven.org/maven2/org/graalvm/js"

  def run(args: List[String]): IO[ExitCode] = args match {
    case arch :: out :: Nil =>
      fetch(arch, Path.of(out)).as(ExitCode.Success)
    case _ =>
      IO.println("usage: JsIsolateFetch <amd64|arm64> <output-directory>")
        .as(ExitCode.Error)
  }

  private def fetch(targetArch: String, out: Path): IO[Unit] =
    for {
      version <- ownVersion
      arch <- graalArch(targetArch)
      artifact = s"js-isolate-linux-$arch"
      url = s"$central/$artifact/$version/$artifact-$version.jar"
      _ <- IO.println(s"fetching $artifact $version")
      _ <- IO
        .blocking(Files.createTempFile("js-isolate", ".jar"))
        .bracket { jar =>
          download(url, jar) *> verify(jar, s"$url.sha1") *>
            extract(jar, arch, out).flatMap(so =>
              IO.blocking(Files.size(so))
                .flatMap(n => IO.println(s"staged $so ($n bytes)"))
            )
        }(jar => IO.blocking(Files.deleteIfExists(jar)).void)
    } yield ()

  private def ownVersion: IO[String] =
    IO.blocking(Option(getClass.getResourceAsStream(versionResource))).flatMap {
      case None =>
        IO.raiseError(
          new IllegalStateException(
            s"$versionResource is not on the classpath — run this with the application jar on -cp"
          )
        )
      case Some(in) =>
        IO.blocking(
          Using.resource(in)(s => String(s.readAllBytes(), UTF_8).trim)
        )
    }

  /** buildx spells the architectures `amd64`/`arm64`; GraalVM says
    * `amd64`/`aarch64`. That is the entire per-architecture difference.
    */
  private def graalArch(targetArch: String): IO[String] = targetArch match {
    case "amd64" | "x86_64"  => IO.pure("amd64")
    case "arm64" | "aarch64" => IO.pure("aarch64")
    case other               =>
      IO.raiseError(
        new IllegalArgumentException(
          s"no GraalVM JS isolate is published for $other"
        )
      )
  }

  private def download(url: String, target: Path): IO[Unit] =
    IO.blocking {
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create(url)).build(),
          HttpResponse.BodyHandlers
            .ofFile(target, CREATE, WRITE, TRUNCATE_EXISTING)
        )
      if (response.statusCode() != 200)
        throw new IllegalStateException(s"GET $url -> ${response.statusCode()}")
    }

  /** Maven Central's own checksum, against a 159 MB body over one connection.
    * `cd` builds and PUSHES without running [[JsIsolateCheck]], so a truncated
    * download would otherwise ship — and the first thing to notice would be a
    * user's first chart.
    */
  private def verify(jar: Path, sha1Url: String): IO[Unit] =
    IO.blocking {
      val expected = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create(sha1Url)).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .body()
        .trim
      val digest = MessageDigest.getInstance("SHA-1")
      Using.resource(DigestInputStream(Files.newInputStream(jar), digest)) {
        in =>
          val _ = in.transferTo(OutputStream.nullOutputStream())
      }
      val actual = HexFormat.of().formatHex(digest.digest())
      if (actual != expected)
        throw new IllegalStateException(
          s"$sha1Url says $expected, the download hashes to $actual"
        )
    }

  private def extract(jar: Path, arch: String, out: Path): IO[Path] =
    IO.blocking {
      val name =
        s"META-INF/resources/engine/js-isolate-linux-$arch/libvm/libpolyglotisolate.so"
      val target = out.resolve("libpolyglotisolate.so")
      val _ = Files.createDirectories(out)
      Using.resource(ZipFile(jar.toFile)) { zip =>
        val entry = Option(zip.getEntry(name)).getOrElse(
          throw new IllegalStateException(s"$name is not in $jar")
        )
        // The read verifies the entry's CRC, so a corrupt library cannot be
        // written out silently.
        Using.resource(zip.getInputStream(entry)) { in =>
          val _ =
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
      target
    }
}
