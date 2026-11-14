package fh.view.build

import cats.effect.{IO, Resource}
import fh.view.testkit.{HouseFixture, LspClient, PklFixture, PklWorkspace}
import io.circe.{ACursor, Json}

import scala.concurrent.duration.*

/** What `/edit` offers through the library: each case asks pkl-lsp, through
  * [[fh.view.runtime.LspBridge.session]] as the page's `@codemirror/lsp-client`
  * connects, about one cursor position (`‸`) in a workspace the backend has
  * prepared, and is either [[Status.Works]] or a recorded [[Status.Gap]]. A gap
  * that starts working fails as well, so `docs/pkl-editor-support.md`, rendered
  * from the cases, stays true both ways.
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

  private val session = ResourceSuiteLocalFixture("pkl-lsp", Session.resource)
  override def munitFixtures = List(session)

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
    test(s"${k.name}: /edit") {
      session().answer(k, header).map { answer =>
        k.status match
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

  final case class Case(
      name: String,
      source: String,
      insert: String,
      ask: Ask,
      status: Status
  )

  final case class Answer(holds: Boolean, seen: String)

  def table(cases: List[Case]): String =
    val rows = cases.map { k =>
      val ask = k.ask match
        case Ask.Completion(_) => "completion"
        case Ask.Hover(_)      => "hover"
        case Ask.Definition(_) => "definition"
        case Ask.Diagnostic(_) => "diagnostic"
      val (status, why) = k.status match
        case Status.Works    => ("works", "")
        case Status.Gap(why) => ("**gap**", why)
      s"| ${k.name} | $ask | `${k.source.replace("|", "\\|")}` | $status | $why |"
    }
    ("| Case | Asks | Source (`‸` the cursor) | `/edit` | Why |" ::
      "|---|---|---|---|---|" :: rows).mkString("\n")

  /** One pkl-lsp for the suite, as one `/edit` page holds one. */
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

    private def jar: os.Path =
      os.Path(
        scala.io.Source.fromResource("pkl-lsp-jar.txt").mkString.trim
      )

    /** A workspace as the add-on serves it: bootstrapped, a dump seeded, and
      * built once, which resolves the project the way every build does.
      */
    def resource: Resource[IO, Session] =
      for {
        ws <- Resource.eval(IO.blocking {
          val root = os.temp.dir()
          val ws = root / "ws"
          val bundled = LibPackage.build(PklWorkspace.resourcesLib)
          val _ = PklWorkspace.bootstrapInto(ws, bundled, root / "cache")
          val _ = DumpPackage.seedFromText(
            ws,
            PklDump.render(HouseFixture.transformedDump),
            Some(bundled)
          )
          val _ = PklBuild.eval(ws, Site.EntryFile)
          ws
        })
        // `@codemirror/lsp-client` replies to every server request this way.
        lsp <- LspClient.spawn(jar, ws, (m, _) => Left(methodNotFound(m)))
        _ <- Resource.eval(
          lsp.request("initialize", editInitialize) >>
            lsp.notify("initialized", Json.obj())
        )
      } yield Session(lsp, ws)

    private def methodNotFound(method: String) = Json.obj(
      "code" -> Json.fromInt(-32601),
      "message" -> Json.fromString(s"Method not implemented: $method")
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
  }
}
