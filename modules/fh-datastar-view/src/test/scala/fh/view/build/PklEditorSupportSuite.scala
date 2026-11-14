package fh.view.build

import cats.effect.{Deferred, IO, Resource}
import fh.view.testkit.{HouseFixture, LspClient, PklFixture, PklWorkspace}
import io.circe.{ACursor, Json}

import scala.concurrent.duration.*

/** What an author's editor offers through the library: each case asks pkl-lsp,
  * the jar `/edit` runs, about one cursor position (`‸`) in a module of a
  * bootstrapped workspace, and is either [[Status.Works]] or a recorded
  * [[Status.Gap]]. A gap that starts working fails as well, so
  * `docs/pkl-editor-support.md`, rendered from the cases, stays true both ways.
  *
  * Each case's source also goes through the dashboard build, with `insert` at
  * the cursor, so no case asks about code the build refuses (a diagnostic case
  * asks about code it must refuse).
  */
class PklEditorSupportSuite extends munit.CatsEffectSuite {
  import PklEditorSupportSuite.*

  override def munitIOTimeout: Duration = 3.minutes

  private val header =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-dashboard/theme.pkl" as th
       |import "@fh-home/dump.pkl" as dump
       |
       |local light = dump.entities.${HouseFixture.kitchenLight.dumpKey}
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |""".stripMargin

  val cases: List[Case] = List(
    Case(
      "a card's builders after a layout builder",
      "card = c.entityCard(light).columns(3).‸",
      """label("Hall")""",
      Ask.Completion(Set("label", "tapAction", "icon")),
      Status.Works
    ),
    Case(
      "a subclass's builders after one it inherits",
      "card = c.entitySlider(light).disabled(false).‸",
      """readout("percent")""",
      Ask.Completion(Set("readout", "fromChooser")),
      Status.Works
    ),
    Case(
      "the components through the package import",
      "card = c.‸",
      "entityCard(light)",
      Ask.Completion(Set("entityCard", "entitySlider", "column")),
      Status.Works
    ),
    Case(
      "a string-literal union's values",
      """card = c.entityCard(light).stick("‸")""",
      "top",
      Ask.Completion(Set("top", "bottom")),
      Status.Works
    ),
    Case(
      "a set's entity inside `render((l) -> …)`",
      "card = (c.column) { children { q.from(dump.lights.all).render((l) -> c.entityCard(l).label(l.‸)).build() } }",
      "entity_id",
      Ask.Completion(Set("entity_id")),
      Status.Works
    ),
    Case(
      "a builder's doc comment on hover",
      "card = c.entityCard(light).colu‸mns(3)",
      "",
      Ask.Hover("surrounding grid"),
      Status.Works
    ),
    Case(
      "go to a component's definition",
      "card = c.entity‸Card(light)",
      "",
      Ask.Definition("/components.pkl"),
      Status.Works
    ),
    Case(
      "a value outside a union, flagged before the build",
      """card = c.entityCard(light).stick("le‸ft")""",
      "",
      Ask.Diagnostic("left"),
      Status.Works
    ),
    Case(
      "a number outside a constraint, flagged before the build",
      "card = c.entityCard(light).columns(1‸3)",
      "",
      Ask.Diagnostic("13"),
      Status.Works
    ),
    Case(
      "the dump's entities",
      "card = c.entityCard(dump.entities.‸)",
      HouseFixture.kitchenLight.dumpKey,
      Ask.Completion(Set(HouseFixture.kitchenLight.dumpKey)),
      Status.Works
    ),
    Case(
      "an entity's kind on hover",
      s"card = c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey.patch(3, "‸", 0)})",
      "",
      Ask.Hover("Light"),
      Status.Gap(
        "the hover names the dump's class per entity (`E_light_kitchen`), not the kind it extends"
      )
    ),
    Case(
      "an entity's fields inside a CEL string",
      """card = c.entityCard(light).secondary(c.expr("value.‸"))""",
      "state",
      Ask.Completion(Set("state", "attr")),
      Status.Gap("CEL is a string to Pkl: nothing inside it completes")
    ),
    Case(
      "a CEL typo, flagged before the build",
      """card = c.entityCard(light).secondary(c.expr("value.stat‸"))""",
      "",
      Ask.Diagnostic("stat"),
      Status.Gap(
        "CEL compiles in the build's validate, which the editor does not run"
      )
    )
  )

  // No desktop session without the CLI: pkl-lsp would fall back to an older
  // `pkl`. `/edit` gets none in production either.
  private val desktop = ResourceSuiteLocalFixture(
    "pkl-lsp as a desktop editor drives it",
    Session.cli.fold(Resource.pure[IO, Option[Session]](None))(cli =>
      Session.resource(Client.Desktop, Some(cli)).map(Some(_))
    )
  )
  private val edit = ResourceSuiteLocalFixture(
    "pkl-lsp as /edit drives it",
    Session.resource(Client.Edit, None).map(Some(_))
  )
  override def munitFixtures = List(desktop, edit)

  cases.foreach { k =>
    test(s"${k.name}: the build agrees") {
      val module = header + k.source.replace(Cursor, k.insert)
      val built = scala.util
        .Try(PklFixture.buildDashboard("probe", module))
        .toEither
        .flatMap(
          _.validated().left.map(e => RuntimeException(e.mkString("\n")))
        )
      k.ask match
        case _: Ask.Diagnostic =>
          assert(
            built.isLeft,
            s"the build accepts what the case calls wrong:\n$module"
          )
        case _ =>
          built.left.foreach(e =>
            fail(s"the build refuses the case:\n$module\n$e")
          )
    }
    List(Client.Desktop -> desktop, Client.Edit -> edit).foreach {
      (client, session) =>
        test(s"${k.name}: ${client.label}") {
          // Skipped locally, never in CI, where a skip would hide the record.
          if (client == Client.Desktop && Session.cli.isEmpty)
            if (sys.env.contains("CI")) fail(Session.noCli)
            else assume(false, Session.noCli)
          val lsp = session().getOrElse(fail("no pkl-lsp session"))
          lsp.answer(k, header).map { answer =>
            k.status(client) match
              case Status.Works =>
                assert(answer.holds, s"pkl-lsp answered ${answer.seen}")
              case Status.Gap(_) =>
                assert(
                  !answer.holds,
                  "now works: mark it Status.Works and update docs/pkl-editor-support.md"
                )
          }
        }
    }
  }

  test("docs/pkl-editor-support.md lists the cases as they are") {
    val doc = os.pwd / "docs" / "pkl-editor-support.md"
    val text = os.read(doc)
    val start = text.indexOf(TableStart)
    val end = text.indexOf(TableEnd)
    assert(start >= 0 && end > start, s"$doc has no $TableStart … $TableEnd")
    assertNoDiff(
      text.substring(start + TableStart.length, end).trim,
      table(cases),
      "paste the rendered table between the markers"
    )
  }
}

object PklEditorSupportSuite {

  val Cursor = "‸"
  val TableStart = "<!-- cases: generated by PklEditorSupportSuite -->"
  val TableEnd = "<!-- end of cases -->"

  enum Ask:
    /** Every name among the items, an item's label up to its `(`. */
    case Completion(includes: Set[String])
    case Hover(contains: String)

    /** A location whose uri ends with this. */
    case Definition(endsWith: String)

    /** Some diagnostic of the module mentions this. */
    case Diagnostic(contains: String)

  enum Status derives CanEqual:
    case Works
    case Gap(why: String)

  /** Who drives pkl-lsp: their handshakes differ in what makes it sync. */
  enum Client(val label: String) derives CanEqual:
    /** VS Code or Neovim with the Pkl extension: workspace folders, real
      * configuration, a `pkl` CLI and `pkl/syncProjects`.
      */
    case Desktop extends Client("a desktop editor")

    /** The add-on's page, as `@codemirror/lsp-client` connects: no root, no
      * workspace folders, `workspace/configuration` unanswered, no sync, and no
      * `pkl` CLI in the image.
      */
    case Edit extends Client("/edit")

  final case class Case(
      name: String,
      source: String,
      insert: String,
      ask: Ask,
      desktop: Status,
      edit: Status = EditUnsynced
  ) {
    def status(c: Client): Status = c match
      case Client.Desktop => desktop
      case Client.Edit    => edit
  }

  /** Every case's on `/edit` (2026-11-14): pkl-lsp answered nothing at all, not
    * one diagnostic.
    */
  val EditUnsynced: Status = Status.Gap(
    "/edit never syncs the project: no root or workspace folders, configuration " +
      "unanswered, no `pkl/syncProjects`, no `pkl` CLI in the image"
  )

  final case class Answer(holds: Boolean, seen: String)

  def table(cases: List[Case]): String =
    def cell(s: Status) = s match
      case Status.Works  => "works"
      case Status.Gap(_) => "**gap**"
    val rows = cases.map { k =>
      val ask = k.ask match
        case Ask.Completion(_) => "completion"
        case Ask.Hover(_)      => "hover"
        case Ask.Definition(_) => "definition"
        case Ask.Diagnostic(_) => "diagnostic"
      // `/edit`'s shared reason is the doc's prose, not every row's.
      val why = List(Client.Desktop, Client.Edit)
        .map(c => c -> k.status(c))
        .collect {
          case (c, g @ Status.Gap(w)) if g != EditUnsynced => s"${c.label}: $w"
        }
        .mkString("; ")
      s"| ${k.name} | $ask | `${k.source.replace("|", "\\|")}` | " +
        s"${cell(k.desktop)} | ${cell(k.edit)} | $why |"
    }
    ("| Case | Asks | Source (`‸` the cursor) | Desktop editor | `/edit` | Why |" ::
      "|---|---|---|---|---|---|" :: rows).mkString("\n")

  /** One pkl-lsp for the suite, over a workspace synced once. */
  final class Session(lsp: LspClient, ws: os.Path) {
    private var n = 0

    def answer(k: Case, header: String): IO[Answer] = {
      n += 1
      val file = ws / s"case$n.pkl"
      val text = header + k.source.replace(Cursor, "")
      val at = header + k.source
      val before = at.substring(0, at.indexOf(Cursor))
      val line = before.count(_ == '\n')
      val char = before.length - before.lastIndexOf('\n') - 1
      val uri = file.toNIO.toUri.toString
      val doc = Json.obj("uri" -> Json.fromString(uri))
      val pos = Json.obj(
        "textDocument" -> doc,
        "position" -> Json.obj(
          "line" -> Json.fromInt(line),
          "character" -> Json.fromInt(char)
        )
      )
      IO.blocking(os.write.over(file, text)) >>
        lsp.notify(
          "textDocument/didOpen",
          Json.obj(
            "textDocument" -> Json.obj(
              "uri" -> Json.fromString(uri),
              "languageId" -> Json.fromString("pkl"),
              "version" -> Json.fromInt(1),
              "text" -> Json.fromString(text)
            )
          )
        ) >> ask(k.ask, pos, uri).guarantee(
          lsp.notify("textDocument/didClose", Json.obj("textDocument" -> doc))
        )
    }

    private def ask(a: Ask, pos: Json, uri: String): IO[Answer] = a match
      case Ask.Completion(includes) =>
        lsp.request("textDocument/completion", pos).map { r =>
          val result = r.hcursor.downField("result")
          val items = result
            .downField("items")
            .values
            .orElse(result.values)
            .toList
            .flatten
          val names = items
            .flatMap(_.hcursor.downField("label").as[String].toOption)
            // A method is labelled `icon(String)`, a string literal `"top"`.
            .map(_.takeWhile(_ != '(').stripPrefix("\"").stripSuffix("\""))
            .toSet
          Answer(includes.subsetOf(names), names.toList.sorted.mkString(", "))
        }
      case Ask.Hover(contains) =>
        lsp.request("textDocument/hover", pos).map { r =>
          val text =
            strings(r.hcursor.downField("result").downField("contents"))
          Answer(text.contains(contains), text)
        }
      case Ask.Definition(endsWith) =>
        lsp.request("textDocument/definition", pos).map { r =>
          val result = r.hcursor.downField("result")
          val all = result.values.toList.flatten.map(_.hcursor) :+ result
          // A package module is a `pkl-lsp://jar/<encoded zip path>!/…` uri.
          val uris = all.flatMap(c =>
            c.downField("uri")
              .as[String]
              .toOption
              .orElse(c.downField("targetUri").as[String].toOption)
              .map(java.net.URLDecoder.decode(_, "UTF-8"))
          )
          Answer(uris.exists(_.endsWith(endsWith)), uris.mkString(", "))
        }
      case Ask.Diagnostic(contains) =>
        lsp.diagnosticsFor(uri, 15.seconds).map { d =>
          val messages = d.toList
            .flatMap(_.hcursor.downField("diagnostics").values.toList.flatten)
            .flatMap(_.hcursor.downField("message").as[String].toOption)
          Answer(
            messages.exists(_.contains(contains)),
            messages.mkString(" / ")
          )
        }

    private def strings(c: ACursor): String =
      c.as[String]
        .toOption
        .orElse(c.downField("value").as[String].toOption)
        .orElse(
          c.values.map(_.map(j => strings(j.hcursor)).mkString("\n"))
        )
        .getOrElse("")
  }

  object Session {

    /** The `pkl` CLI pkl-lsp syncs a project with, which must read this
      * library: 0.32 fails the sync. CI installs 0.33.
      */
    private val onPath: List[(os.Path, String)] =
      sys.env
        .getOrElse("PATH", "")
        .split(java.io.File.pathSeparatorChar)
        .toList
        .filter(_.startsWith("/"))
        .map(d => os.Path(d) / "pkl")
        .filter(p => os.isFile(p) && java.nio.file.Files.isExecutable(p.toNIO))
        .distinct
        .map(p =>
          p -> scala.util
            .Try(os.proc(p, "--version").call(stderr = os.Pipe).out.text())
            .getOrElse("")
            .split(' ')
            .lift(1)
            .getOrElse("")
        )

    val cli: Option[String] =
      onPath.collectFirst {
        case (p, v) if v.split('.').take(2).map(_.toIntOption) match
              case Array(Some(0), Some(minor)) => minor >= 33
              case Array(Some(major), _)       => major > 0
              case _                           => false
            =>
          p.toString
      }

    val noCli =
      "no `pkl` CLI 0.33+ on the sbt server's PATH, so pkl-lsp cannot sync " +
        s"the workspace; found: ${onPath.map((p, v) => s"$p $v").mkString(", ")}"

    private def jar: os.Path =
      os.Path(
        scala.io.Source
          .fromResource("pkl-lsp-jar.txt")
          .mkString
          .trim
      )

    def resource(client: Client, cli: Option[String]): Resource[IO, Session] =
      for {
        root <- Resource.eval(IO.blocking(os.temp.dir()))
        ws = root / "ws"
        // pkl-lsp syncs through the CLI, which reads only its default cache:
        // pointed at the workspace's, the bundled package resolves offline.
        xdg = root / "xdg"
        _ <- Resource.eval(IO.blocking {
          val bundled = LibPackage.build(PklWorkspace.resourcesLib)
          val _ = PklWorkspace.bootstrapInto(ws, bundled, xdg / "pkl")
          val _ = DumpPackage.seedFromText(
            ws,
            PklDump.render(HouseFixture.transformedDump),
            Some(bundled)
          )
        })
        synced <- Resource.eval(Deferred[IO, Either[String, Unit]])
        lsp <- LspClient.spawn(
          jar,
          Map("XDG_CACHE_HOME" -> xdg.toString),
          client match
            case Client.Desktop => desktopAnswers(cli.getOrElse(""))
            case Client.Edit    => (_, _) => Left(methodNotFound),
          notified = (m, p) =>
            if (m != "window/showMessage") IO.unit
            else {
              val text = p.hcursor.downField("message").as[String].getOrElse("")
              if (text.contains("sync successful"))
                synced.complete(Right(())).void
              else if (text.contains("Failed to sync"))
                synced.complete(Left(text)).void
              else IO.unit
            }
        )
        wsUri = ws.toNIO.toUri.toString.stripSuffix("/")
        _ <- Resource.eval(client match
          case Client.Desktop =>
            lsp.request("initialize", initialize(wsUri)) >>
              lsp.notify("initialized", Json.obj()) >>
              // After the configuration reply, or the sync runs without a CLI.
              IO.sleep(2.seconds) >>
              lsp.request("pkl/syncProjects", Json.Null) >>
              synced.get.timeout(90.seconds).flatMap {
                case Right(_)  => IO.unit
                case Left(why) => IO.raiseError(RuntimeException(why))
              }
          case Client.Edit =>
            lsp.request("initialize", editInitialize) >>
              lsp.notify("initialized", Json.obj()) >>
              IO.sleep(2.seconds))
      } yield Session(lsp, ws)

    private val methodNotFound = Json.obj(
      "code" -> Json.fromInt(-32601),
      "message" -> Json.fromString("Method not implemented")
    )

    // Real values, never null: one null fails pkl-lsp's cast and drops them all.
    private def desktopAnswers(cli: String)(
        method: String,
        params: Json
    ): Either[Json, Json] =
      if (method != "workspace/configuration") Right(Json.Null)
      else
        Right(
          Json.arr(
            params.hcursor.downField("items").values.toList.flatten.map {
              item =>
                item.hcursor.downField("section").as[String].getOrElse("") match
                  case "pkl.cli.path" => Json.fromString(cli)
                  case "pkl.modulepath" | "pkl.projects.excludedDirectories" =>
                    Json.arr()
                  case _ => Json.fromString("")
            }*
          )
        )

    /** `@codemirror/lsp-client`'s `initialize` as `editor/app.js` connects: no
      * `rootUri` passed, so null.
      */
    private val editInitialize: Json =
      Json.obj(
        "processId" -> Json.Null,
        "clientInfo" -> Json.obj(
          "name" -> Json.fromString("@codemirror/lsp-client")
        ),
        "rootUri" -> Json.Null,
        "capabilities" -> Json.obj(
          "textDocument" -> Json.obj(
            "completion" -> Json.obj(),
            "hover" -> Json.obj(),
            "definition" -> Json.obj(),
            "publishDiagnostics" -> Json.obj()
          )
        )
      )

    // Without `workspaceFolders`, pkl-lsp ignores the workspace's project.
    private def initialize(wsUri: String): Json =
      Json.obj(
        "processId" -> Json.Null,
        "rootUri" -> Json.fromString(wsUri),
        "workspaceFolders" -> Json.arr(
          Json.obj(
            "uri" -> Json.fromString(wsUri),
            "name" -> Json.fromString("ws")
          )
        ),
        "capabilities" -> Json.obj(
          "workspace" -> Json.obj(
            "workspaceFolders" -> Json.True,
            "configuration" -> Json.True
          ),
          "textDocument" -> Json.obj(
            "completion" -> Json.obj(),
            "hover" -> Json.obj(),
            "definition" -> Json.obj(),
            "publishDiagnostics" -> Json.obj()
          )
        )
      )
  }
}
