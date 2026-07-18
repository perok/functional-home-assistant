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
// rewrite. `fh init` fetches the instance's byte-identical scaffold
// (`.fh/base.pkl`, `PklProject`, `.gitignore`) verbatim and writes the two
// per-machine files this laptop needs — `.fh/machine.json` (its cache dir +
// the instance URL) and `.fh/pins.json` (the version pins); `fh pull` re-pins
// @fh-home. Because the scaffold matches the server's exactly, you can keep the
// workspace in git and use the same files on both sides (only `.fh/machine.json`
// differs, and it is gitignored). Resolution and evaluation run in-process on
// pkl-core (the same library — and for push the same ValueRenderers.json call —
// the instance itself uses). Stock pkl tooling still works on the workspace
// (pkl-lsp completion, the pkl CLI) but nothing here requires it.
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
import io.circe.{Decoder, Json}
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

// The workspace scaffold + per-machine data all live under `.fh/` in the CWD —
// the same layout the add-on writes, so the committed files (`.fh/base.pkl`,
// `PklProject`) are byte-identical to the instance's and a git copy Just Works.
val basePkl = Paths.get(".fh/base.pkl")
val machineJson = Paths.get(".fh/machine.json")
val pinsJson = Paths.get(".fh/pins.json")

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
// instance URL (from `.fh/machine.json`) and builds the same rewrite + cache
// the manifest declares rather than depending on how pkl-core surfaces
// evaluatorSettings.

// The package cache: the cross-platform user DATA dir under the SAME appdirs
// coordinates the add-on / BuildApp use, so a local instance and this script
// land in one cache. This absolute path is what `fh init` writes into
// `.fh/machine.json` as `cacheDir` (base.pkl's `moduleCacheDir`).
val cacheDir =
  Paths
    .get(s"${appdirs.getUserDataDir("fh", "0.0.1", "perok")}/pkl-cache")
    .toAbsolutePath

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

/** Read one string field out of a `.fh` json machine file. These are flat,
  * machine-generated `{ "k": "v" }` files, so a regex is enough (and avoids a
  * circe-parser dependency the toolkit doesn't bundle).
  */
def jsonField(file: Path, field: String): Option[String] =
  Option
    .when(Files.exists(file))(new String(Files.readAllBytes(file), UTF_8))
    .flatMap(s =>
      s""""$field"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(s).map(_.group(1))
    )

/** The instance this workspace is wired to, read from `.fh/machine.json`
  * (`instanceUrl`) — the per-machine file `fh init` writes and `base.pkl`'s
  * rewrite reads.
  */
def instanceUrl: IO[String] = IO.blocking {
  if !Files.exists(machineJson) then
    throw Die(
      s"not an fh workspace (no $machineJson) — run: fh init <instance-url>"
    )
  jsonField(machineJson, "instanceUrl").getOrElse(
    throw Die(s"no instanceUrl in $machineJson — re-run: fh init <instance-url>")
  )
}

/** The @fh-home version currently pinned, if any (for the pull message) — read
  * from `.fh/pins.json`'s `homeUri`.
  */
def pinnedHomeVersion: IO[Option[String]] = IO.blocking {
  jsonField(pinsJson, "homeUri")
    .flatMap("""fh-home@(.+)$""".r.unanchored.findFirstMatchIn(_).map(_.group(1)))
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

/** Fetch one served scaffold file from the instance (`/system/pkl/<name>`).
  * These are the machine-AGNOSTIC, byte-identical files the instance generates;
  * `fh` writes them verbatim, so the committed scaffold matches the server's.
  */
def fetchScaffold(client: Client[IO], url: String, name: String): IO[String] =
  client
    .expect[String](s"$url/system/pkl/$name")
    .adaptError {
      case err if !err.isInstanceOf[Die] =>
        Die(s"could not fetch $url/system/pkl/$name").tap(_.addSuppressed(err))
    }

/** Write the served scaffold: `.fh/base.pkl` verbatim (machine-agnostic, always
  * refreshed), and `PklProject` / `.gitignore` only when absent (the user's from
  * the moment they exist).
  */
def writeScaffold(client: Client[IO], url: String): IO[Unit] =
  for {
    base <- fetchScaffold(client, url, "base.pkl")
    consumer <- fetchScaffold(client, url, "PklProject")
    gitignore <- fetchScaffold(client, url, "gitignore")
    _ <- IO.blocking {
      Files.createDirectories(basePkl.getParent)
      Files.write(basePkl, base.getBytes(UTF_8))
      val proj = Paths.get("PklProject")
      if !Files.exists(proj) then Files.write(proj, consumer.getBytes(UTF_8))
      val gi = Paths.get(".gitignore")
      if !Files.exists(gi) then Files.write(gi, gitignore.getBytes(UTF_8))
    }
  } yield ()

/** The per-machine `{ cacheDir, instanceUrl }` that `base.pkl` reads — this
  * laptop's own cache and the instance URL. Gitignored; never committed.
  */
def writeMachine(url: String): IO[Unit] = IO.blocking {
  Files.createDirectories(machineJson.getParent)
  Files.write(
    machineJson,
    (Json
      .obj(
        "cacheDir" -> Json.fromString(cacheDir.toString),
        "instanceUrl" -> Json.fromString(url)
      )
      .spaces2 + "\n").getBytes(UTF_8)
  )
}

/** The version pins `base.pkl` reads. The @fh-home pin carries its checksum
  * (declared integrity, never trust-on-first-use). Same shape the add-on's
  * `Pins.Data` writes, so init and the server agree.
  */
def writePins(idx: PkgIndex): IO[Unit] = IO.blocking {
  Files.createDirectories(pinsJson.getParent)
  Files.write(
    pinsJson,
    (Json
      .obj(
        "dashboardUri" -> Json.fromString(
          s"package://fh.invalid/fh-dashboard@${idx.dashboardVersion}"
        ),
        "homeUri" -> Json.fromString(
          s"package://fh.invalid/fh-home@${idx.homeVersion}"
        ),
        "homeSha256" -> Json.fromString(idx.homeSha256)
      )
      .spaces2 + "\n").getBytes(UTF_8)
  )
}

def cmdInit(rawUrl: String): IO[Unit] = {
  val url = rawUrl.stripSuffix("/")

  withClient { client =>
    for
      idx <- fetchIndex(client, url)
      _ <- writeScaffold(client, url)
      _ <- writeMachine(url)
      _ <- writePins(idx)
      _ <- resolveDeps(url)
      _ <- IO.println(
        s"""wired to $url (@fh-dashboard ${idx.dashboardVersion}, @fh-home ${idx.homeVersion})
           |add *.pkl entries — completion and evaluation resolve from the instance|""".stripMargin
      )
    yield ()
  }
}

def cmdPull: IO[Unit] =
  withClient { client =>
    for
      url <- instanceUrl
      old <- pinnedHomeVersion
      idx <- fetchIndex(client, url)
      _ <- writePins(idx)
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
