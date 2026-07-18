#!/usr/bin/env -S scala-cli shebang

//> using scala 3.7.4
//> using jvm 17
//> using toolkit typelevel:0.2.0
//> using dep org.pkl-lang:pkl-core:0.31.1
//> using dep org.slf4j:slf4j-nop:1.7.36
//> using dep net.harawata:appdirs:1.5.0

// TODO use @main

// fh — the laptop companion of the FH Dashboard add-on (ADR 0010).
//
// A workspace on your laptop never imports from the instance: it PINS the
// instance's packages — @fh-dashboard, the authoring library, and @fh-home,
// your home's typed entity dump as an immutable content-versioned snapshot —
// and pkl resolves them from the instance through the manifest's own http
// rewrite. This script only writes those pins and keeps the lockfile in step;
// resolution and evaluation run in-process on pkl-core (the same library —
// and for push the same ValueRenderers.json call — the instance itself uses).
// Stock pkl tooling still works on the workspace (pkl-lsp completion, the
// pkl CLI) but nothing here requires it.
//
// This file lives in the GitHub repo — that is its distribution channel:
//
//   curl -fsSLo fh https://raw.githubusercontent.com/perok/functional-home-assistant/main/scripts/fh.sc && chmod +x fh
//
// `fh update` re-fetches that URL and replaces this copy when the sha256
// differs (the previous copy is kept as fh.backup.<date>).
//
// Dependencies: scala-cli (runs this file and fetches everything else).

import cats.Show

import scala.util.chaining.scalaUtilChainingOps
import cats.effect.{ExitCode, IO}
import cats.effect.std.*
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import io.circe.Decoder
import org.http4s.{EntityDecoder, MediaType, Method, Request, Uri}
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import net.harawata.appdirs.*

import scala.util.Using

val appdirs = AppDirsFactory.getInstance()

/** A user-facing failure: printed as `fh: <msg>`, exit 1, no stack trace. */
case class Die(msg: String) extends RuntimeException(msg)

object Die {
  given Show[Die] = Show.show(err =>
    s"${err.msg}${
        if (err.getSuppressed.length > 0) then
          err.getSuppressed.map(_.getMessage).mkString("\n Internal error: ", ",\n", "")
        else ""
      }"
  )
}

def die(msg: String): IO[Nothing] = IO.raiseError(Die(msg))

val basePkl =
  Paths.get(s"${appdirs.getUserConfigDir("fh", "0.0.1", "perok")}/base.pkl")

/** Where `update` fetches the authoritative copy of this script from (the
  * checked-in file on the repo's main branch). Env-overridable for tests.
  */
val selfUrl = sys.env.getOrElse(
  "FH_SELF_URL",
  "https://raw.githubusercontent.com/perok/functional-home-assistant/main/scripts/fh.sc"
)

def sha256(bytes: Array[Byte]): String =
  java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .map("%02x".format(_))
    .mkString

// ---------------------------------------------------------------- pkl-core
// The workspace's own settings, applied by hand: the script knows the
// instance URL and wrote `.fh/base.pkl` itself, so it builds the same
// rewrite + cache the manifest declares rather than depending on how
// pkl-core surfaces evaluatorSettings.

// XDG config dirs
val cacheDir =
  Paths.get(appdirs.getUserCacheDir("fh", "0.0.1", "perok")).toAbsolutePath

def pklHttp(url: String): org.pkl.core.http.HttpClient =
  org.pkl.core.http.HttpClient
    .builder()
    .addRewrite(
      java.net.URI.create("https://fh.invalid/"),
      java.net.URI.create(s"$url/system/pkl/packages/")
    )
    .build()

def loadProject(): org.pkl.core.project.Project =
  val manifest = Paths.get("PklProject")
  if !Files.exists(manifest) then
    throw Die("no PklProject here — run: fh init <instance-url>")
  org.pkl.core.project.Project.loadFromPath(manifest)

/** `pkl project resolve`, in-process: resolve the manifest's dependencies from
  * the instance (packages land in `.fh/cache`) and write the lockfile.
  */
def resolveDeps(url: String): IO[Unit] = IO.blocking {
  import org.pkl.core.SecurityManagers
  import org.pkl.core.packages.PackageResolver
  import org.pkl.core.project.ProjectDependenciesResolver
  val resolver = new ProjectDependenciesResolver(
    loadProject(),
    PackageResolver.getInstance(
      SecurityManagers.defaultManager,
      pklHttp(url),
      cacheDir
    ),
    new java.io.PrintWriter(System.err)
  )

  Using(new java.io.FileOutputStream("PklProject.deps.json"))(out =>
    resolver.resolve().writeTo(out)
  ).get
}

/** `pkl eval -f json`, in-process: evaluate an entry against the project and
  * render it with pkl-core's own `ValueRenderers.json` — the exact call the
  * instance's backend uses, so the pushed JSON matches by construction.
  */
def evalJson(url: String, entry: String): IO[String] = IO.blocking {
  import org.pkl.core.{EvaluatorBuilder, ModuleSource, ValueRenderers}
  Using(
    EvaluatorBuilder
      .preconfigured()
      .setHttpClient(pklHttp(url))
      .setModuleCacheDir(cacheDir)
      .applyFromProject(loadProject())
      .build()
  ) { evaluator =>
    val module = evaluator.evaluate(ModuleSource.path(Paths.get(entry)))
    val writer = new java.io.StringWriter
    ValueRenderers.json(writer, "  ", true).renderDocument(module)
    writer.toString
  }.get
}

def withClient[A](f: Client[IO] => IO[A]): IO[A] =
  EmberClientBuilder.default[IO].build.use(f)

/** The instance this workspace is wired to, read back from the rewrite line in
  * the machine-managed base manifest.
  */
def instanceUrl: IO[String] = IO.blocking {
  if !Files.exists(basePkl) then
    throw Die(
      s"not an fh workspace (no $basePkl) — run: fh init <instance-url>"
    )
  val rewrite = """.*= *"(.*)/system/pkl/packages/".*""".r
  Files
    .readAllLines(basePkl)
    .toArray(Array.empty[String])
    .collectFirst { case rewrite(url) => url }
    .getOrElse(
      throw Die(s"no instance url in $basePkl — re-run: fh init <instance-url>")
    )
}

/** The @fh-home version currently pinned, if any (for the pull message). */
def pinnedHomeVersion: IO[Option[String]] = IO.blocking {
  val pin = """.*fh-home@([^"]*)".*""".r
  Files
    .readAllLines(basePkl)
    .toArray(Array.empty[String])
    .collectFirst { case pin(v) => v }
}

case class PkgIndex(
    dashboardVersion: String,
    homeVersion: String,
    homeSha256: String
)

object PkgIndex {
  given EntityDecoder[IO, PkgIndex] = jsonOf[IO, PkgIndex]

  given Decoder[PkgIndex] = Decoder.instance(c =>
    (
      c.downField("fh-dashboard").get[String]("version"),
      c.downField("fh-home").get[String]("version"),
      c.downField("fh-home").get[String]("sha256")
    ).mapN(PkgIndex.apply)
  )
}

/** The instance's package-discovery index (`/system/pkl/packages`): current
  * versions + metadata sha256 of the packages this home serves.
  */
def fetchIndex(client: Client[IO], url: String): IO[PkgIndex] =
  client
    .expectOr[PkgIndex](s"$url/system/pkl/packages")(errResponse => {
      errResponse.bodyText.compile.string.map(body => {
        Die(
          s"$url/system/pkl/packages answered ${errResponse.status}: ${body}"
        )

      })
    })
    .adaptError {
      case err if !err.isInstanceOf[Die] =>
        Die(
          s"$url/system/pkl/packages did not answer — is that the add-on's direct port, and has it finished starting?"
        ).tap(_.addSuppressed(err))
    }

/** Machine-managed; rewritten whole by init/pull. The @fh-home pin carries its
  * checksum (uri + checksums written together — declared integrity, never
  * trust-on-first-use). @fh-dashboard deliberately carries none here: a user
  * override in PklProject would inherit a stale checksum from the amended-over
  * default, so the lockfile pins the lib instead.
  */
def writeBase(url: String, idx: PkgIndex): IO[Unit] = IO.blocking {
  Files.createDirectories(basePkl.getParent)
  Files.write(
    basePkl,
    s"""/// Machine-managed — rewritten by `fh init` / `fh pull`; do not edit.
       |///
       |/// Wires this workspace to the Home Assistant instance at $url:
       |/// both packages resolve from it through the rewrite below (and stay cached
       |/// in .fh/cache for offline work).
       |///
       |///   @fh-dashboard  the instance's authoring library. The version here is the
       |///                  instance's current one; the pin in your PklProject
       |///                  overrides it.
       |///   @fh-home       YOUR home's typed entity dump, an immutable
       |///                  content-versioned snapshot. `fh pull` re-pins it.
       |amends "pkl:Project"
       |
       |evaluatorSettings {
       |  moduleCacheDir = ".fh/cache"
       |  http {
       |    rewrites {
       |      ["https://fh.invalid/"] = "$url/system/pkl/packages/"
       |    }
       |  }
       |}
       |
       |dependencies {
       |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@${idx.dashboardVersion}" }
       |  ["fh-home"] {
       |    uri = "package://fh.invalid/fh-home@${idx.homeVersion}"
       |    checksums { sha256 = "${idx.homeSha256}" }
       |  }
       |}
       |""".stripMargin.getBytes(UTF_8)
  )
}

def seedConsumer(idx: PkgIndex): IO[Unit] = IO.blocking {
  val consumer = Paths.get("PklProject")
  if !Files.exists(consumer) then
    Files.write(
      consumer,
      s"""/// Your dashboards project. Seeded by `fh init`; from then on it is yours —
         |/// fh never rewrites it. (The machine half — instance URL, cache, the @fh-home
         |/// pin — lives in .fh/base.pkl, which this file amends; fh regenerates that
         |/// one freely.)
         |///
         |/// The pin below names the @fh-dashboard library version your dashboards build
         |/// against; bump it deliberately. Deleting the entry tracks whatever the
         |/// instance currently ships. Third-party card packages are declared here too.
         |amends ".fh/base.pkl"
         |
         |dependencies {
         |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@${idx.dashboardVersion}" }
         |}
         |""".stripMargin.getBytes(UTF_8)
    )
}

def cmdInit(rawUrl: String): IO[Unit] = {
  val url = rawUrl.stripSuffix("/")

  withClient(client => fetchIndex(client, url)).flatMap(idx =>
    writeBase(url, idx) >>
      seedConsumer(idx) >>
      resolveDeps(url) >>
      IO.println(
        s"""wired to $url (@fh-dashboard ${idx.dashboardVersion}, @fh-home ${idx.homeVersion})
           |add *.pkl entries — completion and evaluation resolve from the instance|""".stripMargin
      )
  )
}

def cmdPull: IO[Unit] =
  withClient { client =>
    for
      url <- instanceUrl
      old <- pinnedHomeVersion
      idx <- fetchIndex(client, url)
      _ <- writeBase(url, idx)
      _ <- resolveDeps(url)
      _ <-
        if old.contains(idx.homeVersion) then
          IO.println(s"up to date (@fh-home ${idx.homeVersion})")
        else
          IO.println(
            s"@fh-home ${old.getOrElse("(none)")} -> ${idx.homeVersion}"
          )
    yield ()
  }

def cmdPush(entry: String, slugOpt: Option[String]): IO[Unit] =
  withClient { client =>
    val slug =
      slugOpt.getOrElse(
        Paths.get(entry).getFileName.toString.stripSuffix(".pkl")
      )
    for
      url <- instanceUrl
      // A broken entry fails HERE (pkl-core raises with the authoring error),
      // and the instance validates the payload too (unknown cards come back
      // as a 400 naming them) — never silently on a screen.
      json <- evalJson(url, entry)
      uri <- Uri
        .fromString(s"$url/system/push/$slug")
        .fold(_ => die(s"not a valid url: $url"), IO.pure)
      status <- client.status(
        Request[IO](Method.POST, uri)
          .withEntity(json)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      _ <- IO.raiseWhen(!status.isSuccess)(
        Die(s"push failed — the instance rejected it ($status)")
      )
      _ <- IO.println(
        s"pushed: $url/d/$slug (ephemeral — gone when the instance restarts)"
      )
    yield ()
  }

/** Replace `self` (this file, at the real call site) with the copy at `from`
  * when the checksums differ. The previous copy survives as a dated backup next
  * to it. Parameterized so the test suite can drive it against a copy + a stub
  * URL in-process instead of rewriting the checked-in script.
  */
def cmdUpdate(
    self: Path = Paths.get(scriptPath).toAbsolutePath,
    from: String = selfUrl
): IO[Unit] = withClient { client =>
  for
    remote <- client
      .expect[String](from)
      .map(_.getBytes(UTF_8))
      .adaptError { case _ => Die(s"could not fetch $from") }
    local <- IO.blocking(Files.readAllBytes(self))
    (localSha, remoteSha) = (sha256(local), sha256(remote))
    _ <-
      if localSha == remoteSha then
        IO.println(s"up to date (sha256 ${localSha.take(12)})")
      else
        IO.blocking {
          val backup = backupPath(self)
          // Copy (not move) so the original keeps its executable bit when
          // rewritten in place.
          Files.copy(
            self,
            backup,
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
          )
          Files.write(self, remote)
          backup
        }.flatMap { backup =>
          IO.println(
            s"updated ${localSha.take(12)} -> ${remoteSha.take(12)} (previous kept as ${backup.getFileName})"
          )
        }
  yield ()
}

/** `<name>.backup.<date>`, disambiguated with a time suffix on collision — the
  * same convention the add-on uses for replaced user files.
  */
def backupPath(of: Path): Path = {
  val dated =
    of.resolveSibling(s"${of.getFileName}.backup.${java.time.LocalDate.now}")
  if !Files.exists(dated) then dated
  else
    of.resolveSibling(
      s"${dated.getFileName}-${java.time.format.DateTimeFormatter.ofPattern("HHmmss").format(java.time.LocalTime.now)}"
    )
}

val opts: Opts[IO[ExitCode]] = Opts
  .subcommand(
    "init",
    "Make this directory a dashboards workspace wired to your instance " +
      "(the add-on's direct port, e.g. http://homeassistant.local:8080)."
  )(Opts.argument[String]("instance-url").map(cmdInit(_).as(ExitCode.Success)))
  .orElse(
    Opts.subcommand(
      "pull",
      "Re-pin @fh-home to the instance's current entity dump (run after " +
        "adding/renaming devices)."
    )(Opts(cmdPull.as(ExitCode.Success)))
  )
  .orElse(
    Opts.subcommand(
      "push",
      "Evaluate an entry locally and install it on the instance under " +
        "/d/<slug> (ephemeral: gone on its restart)."
    )(
      (Opts.argument[String]("entry.pkl"), Opts.argument[String]("slug").orNone)
        .mapN(cmdPush)
        .map(_.as(ExitCode.Success))
    )
  )
  .orElse(
    Opts.subcommand(
      "update",
      "Replace this script when the copy in the GitHub repo differs " +
        "(sha256 compare; the old copy is kept as a dated backup)."
    )(Opts(cmdUpdate().as(ExitCode.Success)))
  )

object Fh
    extends CommandIOApp(
      name = "fh",
      header =
        "The laptop companion of the FH Dashboard add-on: wire a local " +
          "dashboards workspace to your Home Assistant instance; authoring itself " +
          "is stock pkl tooling.",
      version = "0.0.1"
    ) {
  // Failures render as `fh: <msg>`, exit 1, no stack trace (Die's contract);
  // for pkl authoring errors, pkl's own message is the useful part.
  def main: Opts[IO[ExitCode]] = opts.map(_.handleErrorWith {
    case err @ Die(m) =>
      Console[IO].errorln(s"fh: ${err.show}")
    case e: org.pkl.core.PklException =>
      Console[IO].errorln(s"fh: ${e.getMessage}")
    case e =>
      Console[IO].errorln(s"fh: $e")
  }.as(ExitCode.Error))
}

// The test gate: the suite references this script's members, which executes
// this wrapper body — the gate keeps that from parsing the test JVM's argv.
// Checked in BOTH env and props: the env var is how `scala-cli test` is
// invoked, the property is what the suite itself can set (a JVM cannot set
// its own env), so either form works.
def testMode: Boolean =
  sys.env
    .get("SCALA_TEST_MODE")
    .orElse(sys.props.get("SCALA_TEST_MODE"))
    .contains("true")

if (!testMode) {
  Fh.main(args)
}
