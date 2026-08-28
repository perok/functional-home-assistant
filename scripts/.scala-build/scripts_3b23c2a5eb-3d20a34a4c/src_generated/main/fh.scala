final class fh$_ {
  def args = fh_sc.args$
  def scriptPath = """fh.sc"""
  /*<script>*/

//> using scala 3.7.4
//> using jvm 17
//> using toolkit typelevel:0.2.0
//> using dep org.pkl-lang:pkl-core:0.32.1
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
// @fh-home.
//
// `fh push a.pkl b.pkl` evaluates each entry here and installs the RESULT on
// the instance, live and ephemeral (pushing `site.pkl` installs every
// dashboard it names — ADR 0021); `--write` sends the SOURCE instead — the
// entry AND the local modules it imports — into the instance's own workspace,
// so it re-evaluates there and survives a restart; and
// `--watch` keeps doing either on every change to a `*.pkl` in this workspace,
// re-sending only the entries that change reaches (its import graph), so
// `fh push --watch *.pkl` does not repaint every dashboard on every save.
//
// Because the scaffold matches the server's exactly, you can keep the
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
  import cats.data.NonEmptyList

  import scala.concurrent.duration.*
  import scala.jdk.CollectionConverters.*
  import scala.util.chaining.scalaUtilChainingOps
  import cats.effect.{ExitCode, IO}
  import cats.effect.std.*
  import cats.syntax.all.*
  import com.monovore.decline.Opts
  import com.monovore.decline.effect.CommandIOApp
  import io.circe.{Decoder, Json}
  import org.http4s.{
    AuthScheme,
    Credentials,
    EntityDecoder,
    MediaType,
    Method,
    Request,
    Status,
    Uri
  }
  import org.http4s.circe.jsonOf
  import org.http4s.client.Client
  import org.http4s.ember.client.EmberClientBuilder
  import org.http4s.headers.{Authorization, `Content-Type`}

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

  /** What a failure prints as. No stack trace: for a pkl authoring error pkl's
    * own message is the useful part, and a [[Die]] is already user-facing
    * prose. Shared by the top-level handler and `push --watch`, which prints
    * the same line instead of exiting.
    */
  def errorMessage(e: Throwable): String = e match
    case err @ Die(_)                 => err.show
    case e: org.pkl.core.PklException => e.getMessage
    case e                            => e.toString

// The workspace scaffold + per-machine data all live under `.fh/` in the CWD —
// the same layout the add-on writes, so the committed files (`.fh/base.pkl`,
// `PklProject`) are byte-identical to the instance's and a git copy Just Works.
  val basePkl = Paths.get(".fh/base.pkl")
  val machineJson = Paths.get(".fh/machine.json")
  val pinsJson = Paths.get(".fh/pins.json")

  /** This laptop's credential for the instance (issue #89): `{"token": "..."}`,
    * a Home Assistant LONG-LIVED ACCESS TOKEN from Profile -> Security.
    *
    * The instance resolves it against HA exactly as it resolves a browser
    * login, so `fh` needs no shared secret of its own and gets its admin role
    * from HA. Gitignored, and — like the instance's own session file — living
    * in `.fh` is a known wart, tracked in issue #165.
    */
  val userSecret = Paths.get(".fh/user_secret.json")

  /** The workspace's one entrypoint (`Site.EntryFile` on the instance, ADR
    * 0021): the file that names every dashboard served. Everything else is a
    * module.
    */
  val entryFile = "site.pkl"

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
// The workspace manifest (`.fh/base.pkl`, reading `.fh/machine.json`) is the
// single source of the cache dir + the fh.invalid rewrite. Evaluation gets
// both through `applyFromProject`; only the dependency RESOLVER needs them
// wired by hand, because `PackageResolver` is a lower-level API where the
// caller owns the http client — so it reads the SAME
// `evaluatorSettings.http.rewrites` off the loaded project (what the pkl CLI
// does internally), never a second hand-built copy.

// The package cache: the cross-platform user DATA dir under the SAME appdirs
// coordinates the add-on / BuildApp use, so a local instance and this script
// land in one cache. This absolute path is what `fh init` writes into
// `.fh/machine.json` as `cacheDir` (base.pkl's `moduleCacheDir`).
  val cacheDir =
    Paths
      .get(s"${appdirs.getUserDataDir("fh", "0.0.1", "perok")}/pkl-cache")
      .toAbsolutePath

  def loadProject(): org.pkl.core.project.Project =
    val manifest = Paths.get("PklProject")
    if !Files.exists(manifest) then
      throw Die("no PklProject here — run: fh init <instance-url>")
    org.pkl.core.project.Project.loadFromPath(manifest)

  /** The resolver's http client, from the manifest's own
    * `evaluatorSettings.http.rewrites` (the same read `PklBuild` does on the
    * instance). A manifest without the block yields a plain client — resolution
    * then dials `fh.invalid` literally and fails with pkl's own message.
    */
  def projectHttpClient(
      project: org.pkl.core.project.Project
  ): org.pkl.core.http.HttpClient =
    org.pkl.core.http.HttpClient
      .builder()
      .tap { builder =>
        Option(project.getEvaluatorSettings)
          .flatMap(s => Option(s.http()))
          .flatMap(h => Option(h.rewrites()))
          .foreach(builder.setRewrites)
      }
      .build()

  /** The manifest's own `allowedResources`/`allowedModules`, lifted into a
    * security manager.
    *
    * From pkl 0.32 the resource allowlist is checked against the REWRITTEN url,
    * and `.fh/base.pkl` rewrites `package://fh.invalid/…` to this instance's
    * LAN address — plain http, since a home add-on has no certificate. base.pkl
    * therefore allows exactly that one origin; `applyFromProject` gives the
    * evaluator those lists, but `PackageResolver` takes its manager as an
    * argument, so resolution has to be handed the same thing.
    */
  def securityManagerFor(
      project: org.pkl.core.project.Project
  ): org.pkl.core.SecurityManager =
    val settings = project.getEvaluatorSettings
    org.pkl.core.SecurityManagers
      .standardBuilder()
      // standardBuilder() starts EMPTY — the defaults are not implied.
      .addAllowedModules(
        Option(settings.allowedModules)
          .getOrElse(org.pkl.core.SecurityManagers.defaultAllowedModules)
      )
      .addAllowedResources(
        Option(settings.allowedResources)
          .getOrElse(org.pkl.core.SecurityManagers.defaultAllowedResources)
      )
      .build()

  /** `pkl project resolve`, in-process: resolve the manifest's dependencies
    * from the instance (packages land in the machine.json cache) and write the
    * lockfile. Client and cache both come off the loaded project, so what the
    * manifest declares is what resolution uses.
    */
  def resolveDeps(): IO[Unit] = IO.blocking {
    import org.pkl.core.packages.PackageResolver
    import org.pkl.core.project.ProjectDependenciesResolver
    val project = loadProject()
    val cache = Option(project.getEvaluatorSettings.moduleCacheDir()).getOrElse(
      throw Die(
        s"$basePkl declares no moduleCacheDir — re-run: fh init <instance-url>"
      )
    )
    val resolver = new ProjectDependenciesResolver(
      project,
      PackageResolver.getInstance(
        securityManagerFor(project),
        projectHttpClient(project),
        cache
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
    * `applyFromProject` carries the manifest's `evaluatorSettings` (cache dir,
    * http rewrites — spike-verified on 0.31.1), so nothing is wired by hand.
    */
  def evalJson(entry: String): IO[String] = IO.blocking {
    import org.pkl.core.{EvaluatorBuilder, ModuleSource, ValueRenderers}
    Using(
      EvaluatorBuilder
        .preconfigured()
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

  /** Read one string field out of a `.fh` json machine file, with a real JSON
    * parse (circe-jawn rides in via http4s-circe, same as [[PkgIndex]]'s
    * decoder).
    */
  def jsonField(file: Path, field: String): Option[String] =
    Option
      .when(Files.exists(file))(new String(Files.readAllBytes(file), UTF_8))
      .flatMap(io.circe.jawn.parse(_).toOption)
      .flatMap(_.hcursor.get[String](field).toOption)

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
      throw Die(
        s"no instanceUrl in $machineJson — re-run: fh init <instance-url>"
      )
    )
  }

  /** This laptop's bearer credential, if it has one. Absent is normal and not
    * an error here: the read-only endpoints `init`/`pull` use are ungated, so
    * only `push` needs it — and `push` says so itself when the instance
    * refuses.
    */
  def userToken(file: Path = userSecret): IO[Option[String]] =
    IO.blocking(jsonField(file, "token").filter(_.nonEmpty))

  /** The @fh-home version currently pinned, if any (for the pull message) —
    * read from `.fh/pins.json`'s `homeUri`.
    */
  def pinnedHomeVersion: IO[Option[String]] = IO.blocking {
    jsonField(pinsJson, "homeUri")
      .flatMap(
        """fh-home@(.+)$""".r.unanchored.findFirstMatchIn(_).map(_.group(1))
      )
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
    * These are the machine-AGNOSTIC, byte-identical files the instance
    * generates; `fh` writes them verbatim, so the committed scaffold matches
    * the server's.
    */
  def fetchScaffold(client: Client[IO], url: String, name: String): IO[String] =
    client
      .expect[String](s"$url/system/pkl/$name")
      .adaptError {
        case err if !err.isInstanceOf[Die] =>
          Die(s"could not fetch $url/system/pkl/$name").tap(
            _.addSuppressed(err)
          )
      }

  /** Write the served scaffold: `.fh/base.pkl` verbatim (machine-agnostic,
    * always refreshed), and `PklProject` / `.gitignore` only when absent (the
    * user's from the moment they exist).
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
        _ <- resolveDeps()
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
        _ <- resolveDeps()
        _ <-
          if old.contains(idx.homeVersion) then
            IO.println(s"up to date (@fh-home ${idx.homeVersion})")
          else
            IO.println(
              s"@fh-home ${old.getOrElse("(none)")} -> ${idx.homeVersion}"
            )
      yield ()
    }

  /** One dashboard to push: the local entry file, and the slug it lands on. */
  case class Target(entry: Path, slug: String)

  /** Pair each entry with its slug — the filename sans `.pkl`, or `--slug` when
    * given. `--slug` renames ONE dashboard, so it is rejected against several
    * entries rather than silently applied to the last one.
    *
    * It is a `push` (JSON) option only. `--write` sends SOURCE, and a source
    * file's name is not a slug any more (ADR 0021: the slug is a key inside
    * `site.pkl`), so combining them would rename the file while claiming to
    * rename the dashboard.
    */
  def targets(
      entries: NonEmptyList[String],
      slugOpt: Option[String],
      write: Boolean = false
  ): IO[NonEmptyList[Target]] =
    def defaultSlug(entry: String) =
      Paths.get(entry).getFileName.toString.stripSuffix(".pkl")
    slugOpt match
      case Some(_) if write =>
        die(
          "--slug renames a pushed dashboard, but --write sends SOURCE — the " +
            "slug is a key in the instance's site.pkl, not a filename. " +
            "Drop --slug, or edit the key in site.pkl and write that."
        )
      case Some(_) if entries.size > 1 =>
        die(
          s"--slug names one dashboard, but ${entries.size} entries were given — " +
            "drop it and each entry lands on its own filename"
        )
      case Some(slug) =>
        IO.pure(NonEmptyList.one(Target(Paths.get(entries.head), slug)))
      case None =>
        IO.pure(entries.map(e => Target(Paths.get(e), defaultSlug(e))))

  /** Evaluate one entry and hand the result to the instance — as the live
    * `{cards, card}` JSON (`push`) or, with `--write`, as the Pkl SOURCE.
    *
    * Both forms evaluate locally first: a broken entry fails HERE (pkl-core
    * raises with the authoring error), so `--write` never overwrites a working
    * file on the instance with one that does not build.
    */
  def pushOne(
      client: Client[IO],
      url: String,
      target: Target,
      write: Boolean
  ): IO[Unit] =
    // The instance validates the payload too (unknown cards come back as a 400
    // naming them) — never silently on a screen.
    evalJson(target.entry.toString).flatMap(json =>
      if write then writeSource(client, url, target)
      else installJson(client, url, target, json)
    )

  /** POST the evaluated dashboard to `/system/push/<slug>`: live, ephemeral —
    * the instance holds it in memory and a restart returns it to its on-disk
    * dashboards.
    */
  def installJson(
      client: Client[IO],
      url: String,
      target: Target,
      json: String
  ): IO[Unit] =
    for
      token <- userToken()
      _ <- post(
        client,
        s"$url/system/push/${target.slug}",
        Method.POST,
        json,
        MediaType.application.json,
        "push",
        token
      )
      // An entrypoint names its OWN slugs (ADR 0021), so the instance installs
      // its keys and the URL's slug is inert — say what actually landed rather
      // than pointing at a `/d/dashboard` that does not exist.
      _ <- IO.println(
        siteSlugs(json) match
          case Some(slugs) =>
            s"pushed ${slugs.size} dashboard(s): " +
              slugs.map(s => s"$url/d/$s").mkString(", ") +
              " (ephemeral — gone when the instance restarts)"
          case None =>
            s"pushed: $url/d/${target.slug} (ephemeral — gone when the instance restarts)"
      )
    yield ()

  /** The slugs an evaluated ENTRYPOINT names, or `None` for a single dashboard
    * — the `dashboards` key is what tells the two payloads apart, on this side
    * and on the instance's (`Site.DashboardsKey`).
    */
  def siteSlugs(json: String): Option[List[String]] =
    io.circe.jawn
      .parse(json)
      .toOption
      .flatMap(_.hcursor.downField("dashboards").focus)
      .flatMap(_.asObject)
      .map(_.keys.toList.sorted)

  /** PUT the entry's SOURCE — and every local `*.pkl` it reads — into the
    * instance's own workspace (`/edit/file/<path>`, the route the /edit editor
    * saves through), so the instance owns and re-evaluates it and the result
    * survives a restart.
    *
    * '''The whole import set travels, not just the named file''', entrypoint
    * LAST ([[writeSet]]). A written file whose imports stayed on the laptop
    * does not build on the instance, and since #116 that is no longer a
    * one-dashboard problem: `site.pkl` importing a module the instance lacks
    * fails the site's evaluation, so EVERY dashboard shows that error (ADR
    * 0021). Sending what the entry actually reads, in an order whose prefixes
    * are all valid, is what leaves the instance evaluable even if a write fails
    * part-way.
    *
    * Writing the ENTRYPOINT is therefore how a dashboard is added, removed or
    * renamed, and it goes live immediately. Writing a plain module lands a file
    * nothing serves until a key in `site.pkl` points at it.
    */
  def writeSource(client: Client[IO], url: String, target: Target): IO[Unit] =
    for
      files <- writeSet(target.entry)
      token <- userToken()
      _ <- files.traverse_ { case (rel, path) =>
        IO.blocking(new String(Files.readAllBytes(path), UTF_8))
          .flatMap(
            post(
              client,
              s"$url/edit/file/$rel",
              Method.PUT,
              _,
              MediaType.text.plain,
              "write",
              token
            )
          )
      }
      _ <- IO.println(
        s"wrote ${files.size} file(s) on the instance: " +
          files.map(_._1).mkString(", ")
      )
    yield ()

  /** `(instance-relative path, local file)` for everything [[writeSource]]
    * sends: the entry plus its transitive local imports ([[importSet]]), each
    * keyed by its path relative to this workspace — which is the path it takes
    * on the instance, since both are the same workspace layout.
    *
    * The instance only accepts `<name>.pkl` and `lib/<name>.pkl` (its
    * `EditorRoutes.resolveEditable`), so anything outside the workspace or
    * deeper than that is refused HERE, naming the file — rather than as a 403
    * from a PUT halfway through the set.
    */
  def writeSet(
      entry: Path,
      root: Path = Paths.get("").toAbsolutePath.normalize,
      // The import analysis, injectable so a test can pin the ORDER without a
      // resolvable workspace (analysing one needs the instance's packages).
      imports: Path => IO[Option[Set[Path]]] = importSet
  ): IO[List[(String, Path)]] =
    def relative(p: Path): IO[(String, Path)] =
      val abs = p.toAbsolutePath.normalize
      val rel = root.relativize(abs).toString
      val depth = abs.getNameCount - root.getNameCount
      if !abs.startsWith(root) then
        die(
          s"$rel is outside this workspace — the instance only takes files from " +
            "the workspace itself; move it in, or push the evaluated JSON instead"
        )
      else if depth > 2 || (depth == 2 && !rel.startsWith("lib/")) then
        die(
          s"$rel is nested too deep for the instance, which takes <name>.pkl and " +
            "lib/<name>.pkl only"
        )
      else IO.pure(rel -> abs)
    imports(entry).flatMap { local =>
      local
        .getOrElse(Set(entry.toAbsolutePath.normalize))
        .toList
        // THE ENTRYPOINT GOES LAST, and that ordering is the safety property, not
        // a tidiness one. These are N independent PUTs, so a failure part-way
        // through leaves whatever landed. If the entrypoint landed first, the
        // instance would hold a `site.pkl` naming a module it does not have —
        // which fails the whole site's evaluation (ADR 0021), taking down
        // dashboards that were serving fine. Modules first, and a partial write
        // leaves at worst some unreferenced files, which serve nobody and break
        // nothing.
        .sortBy(p => (p.getFileName.toString == entryFile, p.toString))
        .traverse(relative)
    }

  /** What to do about a refused push. Writing pkl to the instance is admin-only
    * (issue #89), and the two failures need different advice: no token at all
    * is a setup step, while a token HA does not accept as an admin is a
    * different account — and the second reads as "it worked yesterday", so it
    * has to say which of the two happened.
    */
  def unauthorizedHelp(
      what: String,
      status: Status,
      hadToken: Boolean
  ): String =
    if !hadToken then
      s"""$what failed — the instance requires a Home Assistant admin ($status).
       |Create a long-lived access token in Home Assistant (Profile -> Security)
       |and save it as:
       |  $userSecret   {"token": "<the token>"}""".stripMargin
    else s"""$what failed — the instance did not accept this token ($status).
       |The token in $userSecret must belong to a Home Assistant ADMIN, and must
       |not have been revoked (Profile -> Security).""".stripMargin

  /** Send one body to the instance, failing with the response status (and its
    * body, which is where the server puts the validation message a pushing
    * author has no log to read).
    */
  def post(
      client: Client[IO],
      rawUri: String,
      method: Method,
      body: String,
      mediaType: MediaType,
      what: String,
      // Passed in rather than read here: `post` then has no hidden dependency on
      // the working directory, and both halves — the header it sends and the
      // advice it gives when refused — are testable without one.
      token: Option[String]
  ): IO[Unit] =
    for
      uri <- Uri
        .fromString(rawUri)
        .fold(_ => die(s"not a valid url: $rawUri"), IO.pure)
      _ <- client
        .run {
          val req = Request[IO](method, uri)
            .withEntity(body)
            .withContentType(`Content-Type`(mediaType))
          token.fold(req)(t =>
            req.putHeaders(
              Authorization(Credentials.Token(AuthScheme.Bearer, t))
            )
          )
        }
        .use(response =>
          IO.unlessA(response.status.isSuccess)(
            response.bodyText.compile.string.flatMap(text =>
              IO.raiseError(
                Die(
                  if response.status == Status.Unauthorized ||
                    response.status == Status.Forbidden
                  then unauthorizedHelp(what, response.status, token.isDefined)
                  else
                    s"$what failed — the instance rejected it (${response.status})${
                        if text.trim.nonEmpty then s": ${text.trim}" else ""
                      }"
                )
              )
            )
          )
        )
        // A stopped instance is the ordinary case under `--watch`, which prints
        // this on every tick — a bare ConnectException would be noise.
        .adaptError {
          case err if !err.isInstanceOf[Die] =>
            Die(s"$what failed — $rawUri did not answer")
              .tap(_.addSuppressed(err))
        }
    yield ()

  /** Write this laptop's instance credential.
    *
    * Writing it by hand was the alternative, and it is a bad one: the file
    * holds a Home Assistant token, so "make a JSON file with this shape"
    * invites both a typo and a token left somewhere world-readable.
    *
    * Read from stdin when no argument is given, because a token on a command
    * line lands in the shell history of every machine it is typed on.
    */
  def cmdLogin(token: Option[String]): IO[Unit] =
    for
      raw <- token.fold(
        IO.println("Paste a Home Assistant long-lived access token:") *>
          IO.readLine
      )(IO.pure)
      trimmed = raw.trim
      _ <- IO.raiseWhen(trimmed.isEmpty)(Die("no token given"))
      _ <- IO
        .blocking {
          Files.createDirectories(userSecret.toAbsolutePath.getParent)
          Files.writeString(
            userSecret,
            s"""{"token": ${quoteJson(trimmed)}}\n"""
          )
          // Owner-only, and set AFTER the write rather than only at creation: this
          // overwrites an existing file too, whose permissions we did not choose.
          Files.setPosixFilePermissions(
            userSecret,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
          )
        }
        .handleErrorWith {
          // A filesystem with no POSIX permissions is not a reason to fail: the
          // token is written either way, and saying so beats pretending.
          case _: UnsupportedOperationException =>
            IO.println(s"note: could not restrict permissions on $userSecret")
          case e => IO.raiseError(e)
        }
      _ <- IO.println(s"wrote $userSecret (owner-only)")
    yield ()

  /** A JSON string literal — the token is opaque and could contain anything. */
  def quoteJson(s: String): String = io.circe.Json.fromString(s).noSpaces

  def cmdPush(
      entries: NonEmptyList[String],
      slugOpt: Option[String],
      write: Boolean,
      watch: Boolean
  ): IO[Unit] =
    withClient { client =>
      for
        url <- instanceUrl
        ts <- targets(entries, slugOpt, write)
        _ <-
          if !watch then ts.traverse_(pushOne(client, url, _, write))
          else watchPush(client, url, ts, write)
      yield ()
    }

  /** `push --watch`: push everything once, then re-push only the entries a
    * change actually reaches.
    *
    * `fh push --watch *.pkl` is the normal invocation, so "any edit re-sends
    * every dashboard" is the wrong default — editing one entry would repaint
    * all of them on every keystroke-save. What an entry reads is
    * [[importSet]]-precise, so a shared module still re-sends its dependents,
    * and only its dependents.
    *
    * A failure is a message, not an exit: the whole point is to sit next to an
    * editor while an entry is broken half the time.
    */
  def watchPush(
      client: Client[IO],
      url: String,
      ts: NonEmptyList[Target],
      write: Boolean
  ): IO[Unit] =
    // Re-read what an entry imports every time it is pushed: an edit can add or
    // drop an import, so a dependency set is only true as of the last evaluation.
    def pushAndTrack(target: Target): IO[(Target, Option[Set[Path]])] =
      reporting(pushOne(client, url, target, write)) *>
        importSet(target.entry).map(target -> _)

    for
      initial <- ts.traverse(pushAndTrack).map(_.toList.toMap)
      deps <- IO.ref(initial)
      _ <- IO.println("watching *.pkl — ctrl-c to stop")
      _ <- watchSources(changed =>
        deps.get
          .map(affectedBy(ts.toList, _, changed))
          .flatMap(_.traverse(pushAndTrack))
          .flatMap(updated => deps.update(_ ++ updated))
      )
    yield ()

  /** The entries a set of changed files reaches: those importing one of them
    * (an entry's own file is in its own [[importSet]]).
    *
    * An entry whose imports could not be analyzed — `None` — counts as reached
    * by anything, so the failure mode is a redundant push and never a missed
    * one.
    */
  def affectedBy(
      ts: List[Target],
      deps: Map[Target, Option[Set[Path]]],
      changed: Set[Path]
  ): List[Target] =
    ts.filter(target =>
      deps.get(target).flatten.fold(true)(changed.intersect(_).nonEmpty)
    )

  /** Print what a failure would have exited with, and carry on. */
  def reporting(io: IO[Unit]): IO[Unit] =
    io.handleErrorWith(e => Console[IO].errorln(s"fh: ${errorMessage(e)}"))

  /** The local `*.pkl` files an entry actually reads — itself plus its
    * transitive `file:` imports — from pkl-core's static analyzer, the same
    * `Analyzer.importGraph` call the instance's `PklBuild` uses to decide what
    * to watch. `@fh-dashboard`/`@fh-home` imports resolve to `package:` URIs
    * and are filtered out: they are immutable per version, and a laptop cannot
    * edit them.
    *
    * `None` when the analysis fails or comes back empty — the caller then
    * treats the entry as reached by any change.
    */
  def importSet(entry: Path): IO[Option[Set[Path]]] = IO.blocking {
    import org.pkl.core.evaluatorSettings.TraceMode
    import org.pkl.core.module.ModuleKeyFactories
    import org.pkl.core.{Analyzer, SecurityManagers, StackFrameTransformers}
    scala.util
      .Try {
        val project = loadProject()
        val analyzer = new Analyzer(
          StackFrameTransformers.defaultTransformer,
          false,
          SecurityManagers.defaultManager,
          List(
            ModuleKeyFactories.standardLibrary,
            ModuleKeyFactories.file,
            ModuleKeyFactories.projectpackage,
            ModuleKeyFactories.pkg
          ).asJava,
          project.getEvaluatorSettings.moduleCacheDir(),
          project.getDependencies,
          org.pkl.core.http.HttpClient.dummyClient(),
          TraceMode.COMPACT
        )
        val graph = analyzer.importGraph(entry.toAbsolutePath.normalize.toUri)
        val files = (graph.imports.keySet.asScala.toSet ++
          graph.resolvedImports.values.asScala.toSet).iterator
          .filter(_.getScheme == "file")
          .map(u => Paths.get(u).toAbsolutePath.normalize)
          .toSet
        // The graph always names the entry itself, so an empty file set means the
        // analysis produced nothing usable — not "this entry imports no files".
        Option.when(files.nonEmpty)(files + entry.toAbsolutePath.normalize)
      }
      .toOption
      .flatten
  }

  val pollInterval = 400.millis

  /** Every `*.pkl` under the workspace, skipping dot-directories (`.fh`,
    * `.git`, `.scala-build`), as absolute paths — what a change is reported as,
    * and what [[importSet]] returns, so the two compare directly.
    *
    * The whole tree, not just the pushed entries: an edit to a shared module
    * changes what its dependents evaluate to, and re-scanning per tick means a
    * newly created file is watched without restarting.
    */
  def pklSources(root: Path = Paths.get(".")): IO[List[Path]] = IO.blocking {
    Using(Files.walk(root)) { paths =>
      paths
        .iterator()
        .asScala
        .filter(p => p.getFileName.toString.endsWith(".pkl"))
        .filterNot(
          root
            .relativize(_)
            .iterator()
            .asScala
            .exists(_.toString.startsWith("."))
        )
        .map(_.toAbsolutePath.normalize)
        .toList
    }.get
  }

  /** Call `onChange` with the files that changed, whenever any of the
    * workspace's Pkl sources do (added, edited or deleted).
    *
    * Polls (size + mtime) rather than taking a filesystem watch: the set is
    * small, an editor's save-then-rename is one stamp change either way, and it
    * behaves the same on the network/synced filesystems these workspaces
    * routinely live on. The stamp is taken BEFORE `onChange` runs, so an edit
    * made while a push is in flight is caught on the next tick instead of being
    * swallowed.
    */
  def watchSources(
      onChange: Set[Path] => IO[Unit],
      root: Path = Paths.get(".")
  ): IO[Unit] =
    // A file can vanish between the scan and the stat (an editor's atomic save is
    // a rename); that tick simply reads as changed.
    def stamp: IO[Map[Path, (Long, Long)]] = pklSources(root).map(
      _.map(p =>
        p -> scala.util
          .Try((Files.size(p), Files.getLastModifiedTime(p).toMillis))
          .getOrElse((0L, 0L))
      ).toMap
    )

    def loop(previous: Map[Path, (Long, Long)]): IO[Unit] =
      IO.sleep(pollInterval) *> stamp.flatMap { current =>
        val changed = (previous.keySet ++ current.keySet)
          .filter(p => previous.get(p) != current.get(p))
        IO.unlessA(changed.isEmpty)(onChange(changed)) *> loop(current)
      }

    stamp.flatMap(loop)

  /** Replace `self` (this file, at the real call site) with the copy at `from`
    * when the checksums differ. The previous copy survives as a dated backup
    * next to it. Parameterized so the test suite can drive it against a copy +
    * a stub URL in-process instead of rewriting the checked-in script.
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

  /** `<name>.backup.<date>`, disambiguated with a time suffix on collision —
    * the same convention the add-on uses for replaced user files.
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

  /** Where the pkl CLI reads OS-user-level settings from
    * (`~/.pkl/settings.pkl`, the documented location).
    */
  def pklUserSettings: Path =
    Paths.get(System.getProperty("user.home"), ".pkl", "settings.pkl")

  /** The user-level settings `init-lsp-fix` installs: the workspace's own
    * fh.invalid rewrite, lifted to `~/.pkl/settings.pkl`. Needed because the
    * pkl CLI applies a project's `evaluatorSettings.http.rewrites` only when
    * invoked FROM the project directory — `pkl project resolve <dir>` from
    * elsewhere (exactly how the IntelliJ plugin syncs) ignores them and dials
    * `fh.invalid` literally. User-level settings apply in both modes (verified
    * on pkl 0.31.0).
    *
    * From pkl 0.32 this is NOT sufficient on its own for an http instance: the
    * resource allowlist is checked against the rewritten url too, dir-arg mode
    * skips the project's `allowedResources` the same way it skips the rewrites,
    * and `pkl:settings` has no `allowedResources` property to lift it into.
    * Only the command line can carry it, so point the IDE's Pkl executable at
    * `scripts/pkl-fh`, which injects both flags (verified on 0.32.1; see
    * docs/issue-report-1-pkl-cli-http-rewrites-project-resolve.md).
    */
  def lspFixContent(url: String): String =
    s"""amends "pkl:settings"
     |// Written by `fh init-lsp-fix` — lets `pkl project resolve <dir>` (how
     |// IntelliJ/pkl-lsp sync a workspace) resolve package://fh.invalid/...
     |// from the instance; the CLI ignores the project's own http.rewrites in
     |// that invocation mode. Re-run the command after re-wiring to a
     |// different instance.
     |http {
     |  rewrites {
     |    ["https://fh.invalid/"] = "$url/system/pkl/packages/"
     |  }
     |}
     |""".stripMargin

  /** Write [[lspFixContent]] to `settings`: no-op when already current, dated
    * backup first when a different file exists (the user-file convention — it
    * is the user's global pkl config, never silently replaced).
    */
  def writeLspFix(settings: Path, rawUrl: String): IO[Unit] = IO.blocking {
    val url = rawUrl.stripSuffix("/")
    val content = lspFixContent(url)
    val existing =
      Option.when(Files.exists(settings))(Files.readString(settings))
    if existing.contains(content) then
      println(s"up to date ($settings already has the $url rewrite)")
    else
      existing.foreach { _ =>
        val backup = backupPath(settings)
        Files.copy(
          settings,
          backup,
          java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
        )
        println(s"previous settings kept as ${backup.getFileName}")
      }
      Files.createDirectories(settings.getParent)
      Files.write(settings, content.getBytes(UTF_8))
      println(
        s"wrote $settings — `pkl project resolve <dir>` now reaches this " +
          s"workspace's packages via $url (instance must be up). On pkl 0.32+ " +
          "an http instance ALSO needs an allowlist that only the command line " +
          "carries: point the IDE's Pkl executable at scripts/pkl-fh."
      )
  }

  def cmdInitLspFix(settings: Path = pklUserSettings): IO[Unit] =
    instanceUrl.flatMap(writeLspFix(settings, _))

  val opts: Opts[IO[ExitCode]] = Opts
    .subcommand(
      "init",
      "Make this directory a dashboards workspace wired to your instance " +
        "(the add-on's direct port, e.g. http://homeassistant.local:8080)."
    )(
      Opts.argument[String]("instance-url").map(cmdInit(_).as(ExitCode.Success))
    )
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
        "Evaluate entries locally and install them on the instance under " +
          "/d/<slug> (ephemeral: gone on its restart)."
      )(
        (
          Opts.arguments[String]("entry.pkl"),
          Opts
            .option[String](
              "slug",
              "Install under this name instead of the filename (one entry only; " +
                "not with --write, and inert when pushing an entrypoint, which " +
                "names its own slugs)."
            )
            .orNone,
          Opts
            .flag(
              "write",
              "Write the Pkl SOURCE into the instance's workspace instead — the " +
                "entry and every local module it imports — so it re-evaluates " +
                "there and survives a restart."
            )
            .orFalse,
          Opts
            .flag(
              "watch",
              "Stay running: re-evaluate and re-send on every change to a *.pkl " +
                "file in this workspace."
            )
            .orFalse
        )
          .mapN(cmdPush)
          .map(_.as(ExitCode.Success))
      )
    )
    .orElse(
      Opts.subcommand(
        "login",
        "Save a Home Assistant long-lived access token so `push` can write to " +
          "the instance (Profile -> Security in HA creates one). Reads it from " +
          "stdin if not given as an argument."
      )(
        Opts
          .argument[String]("token")
          .orNone
          .map(cmdLogin(_).as(ExitCode.Success))
      )
    )
    .orElse(
      Opts.subcommand(
        "init-lsp-fix",
        "Write ~/.pkl/settings.pkl with this workspace's fh.invalid rewrite, " +
          "so IntelliJ / the pkl CLI resolve packages when invoked from outside " +
          "the workspace (dated backup of any existing settings)."
      )(Opts(cmdInitLspFix().as(ExitCode.Success)))
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
    def main: Opts[IO[ExitCode]] = opts.map(_.handleErrorWith { e =>
      Console[IO].errorln(s"fh: ${errorMessage(e)}").as(ExitCode.Error)
    })
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

  /*</script>*/ /*<generated>*/ /*</generated>*/
}

object fh_sc {
  private var args$opt0 = Option.empty[Array[String]]
  def args$set(args: Array[String]): Unit = {
    args$opt0 = Some(args)
  }
  def args$opt: Option[Array[String]] = args$opt0
  def args$ : Array[String] = args$opt.getOrElse {
    sys.error("No arguments passed to this script")
  }

  lazy val script = new fh$_

  def main(args: Array[String]): Unit = {
    args$set(args)
    val _ = script
      .hashCode() // hashCode to clear scalac warning about pure expression in statement position
  }
}

export fh_sc.script as `fh`
