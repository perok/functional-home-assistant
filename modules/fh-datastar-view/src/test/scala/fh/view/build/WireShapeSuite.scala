package fh.view.build

import fh.view.model.{Access, Cell, LayoutNode, Predicate, SlotSource}
import fh.view.testkit.PklWorkspace
import io.circe.Json

/** The wire shape is declared TWICE — once as a Pkl class in `components.pkl`
  * (what an author's dashboard evaluates to) and once as a Scala case class in
  * `Dashboard.scala` (what the runtime decodes). Nothing makes them agree.
  *
  * They have been kept in step by the `PklBuildSuite` snapshots, which only
  * notice when an evaluated fixture happens to exercise the field that drifted
  * — and then report it as a confusing JSON diff rather than as "these two
  * definitions disagree". A field added on one side and forgotten on the other
  * decodes to its default and is silently ignored.
  *
  * This asserts the correspondence directly, by REFLECTION on both sides:
  * `pkl:reflect` for the Pkl classes (the same mechanism `components.cardsOf`
  * uses to derive the card registry) and `productElementNames` for the Scala
  * ones. It is the cheap version of the real answer, which is to generate one
  * side from the other — but it turns drift into an immediate, named failure,
  * which is the part that was missing.
  *
  * Deliberately compares NAMES, not types. Pkl's `Listing<String>` and Scala's
  * `List[String]` are the same wire array, and encoding a type correspondence
  * here would be a second model to maintain — which is the problem, not the
  * fix.
  */
class WireShapeSuite extends munit.FunSuite {

  /** Every property `pkl:reflect` reports for a class in `components.pkl`,
    * keyed by class name. `hidden` properties are included by reflection and
    * excluded here: they are authoring inputs that never reach the wire (a
    * card's `entity`, an `If`'s `then`/`else`), and the Scala side has no
    * counterpart for them by design.
    */
  private lazy val pklProperties: Map[String, Set[String]] = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp)
    os.makeDir.all(tmp / "lib")
    os.write(
      tmp / "probe.pkl",
      """module probe
        |import "pkl:reflect"
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-dashboard/hass.pkl"
        |import "@fh-dashboard/core/node.pkl" as nodes
        |import "@fh-dashboard/core/slot.pkl" as slotMod
        |import "@fh-dashboard/core/surface.pkl" as surfaceMod
        |import "@fh-dashboard/core/tap.pkl" as tapMod
        |import "@fh-dashboard/core/predicate.pkl" as pred
        |import "@fh-dashboard/core/access.pkl" as accessMod
        |
        |// The wire shape is spread across the library's modules, and reflection
        |// sees only what a module DECLARES — the facade declares no classes at
        |// all. So merge every module that owns wire classes; that also keeps
        |// "does this module own the ancestor" true across a module boundary
        |// (`SetNode extends LayoutNode` now spans two files).
        |local mods: List<Module> =
        |  List(nodes, slotMod, surfaceMod, tapMod, pred, accessMod) + c.modules
        |local own: Map<String, reflect.Class> =
        |  mods.fold(Map(), (acc, m) -> acc + reflect.Module(m).classes)
        |
        |// INHERITED properties count: `SetNode extends LayoutNode`, and `cell`
        |// is declared on the base. `reflect.Class.properties` reports only what
        |// a class declares, so walk up — stopping at the first ancestor this
        |// module does not own, which is where Pkl's own builtins begin.
        |local function propsOf(cls: reflect.Class): List<String> =
        |  cls.properties.toMap().entries
        |    .filter((e) -> !e.value.modifiers.contains("hidden"))
        |    .map((e) -> e.key)
        |  + (let (s = cls.superclass)
        |      if (s != null && own.containsKey(s.name)) propsOf(s) else List())
        |
        |shapes: Mapping<String, Listing<String>> = new {
        |  for (name, cls in own) {
        |    [name] = new Listing { for (p in propsOf(cls).distinct) { p } }
        |  }
        |}
        |""".stripMargin
    )
    val res = SourceEval
      .eval(tmp, "probe.pkl")
      .fold(e => fail(s"reflect probe failed: $e"), identity)
    res.value.hcursor
      .downField("shapes")
      .as[Map[String, List[String]]]
      .fold(e => fail(s"decode: $e"), _.map { case (k, v) => k -> v.toSet })
  }

  /** The Scala case class's field names. */
  private def scalaFields(p: Product): Set[String] =
    p.productElementNames.toSet

  private def check(
      pklClass: String,
      sample: Product,
      scalaOnly: Set[String] = Set.empty,
      pklOnly: Set[String] = Set.empty
  ): Unit = {
    val pkl = pklProperties.getOrElse(
      pklClass,
      fail(
        s"`components.pkl` has no class '$pklClass'. Known: " +
          pklProperties.keys.toList.sorted.mkString(", ")
      )
    )
    val scala = scalaFields(sample)
    // `kind` is the circe discriminator: carried explicitly in Pkl, supplied by
    // the decoder configuration in Scala, so it is never a Scala field.
    val pklWire = pkl - "kind" -- pklOnly
    val scalaWire = scala -- scalaOnly
    assertEquals(
      pklWire,
      scalaWire,
      clue = s"""'$pklClass' disagrees between the two definitions.
                |  only in components.pkl: ${(pklWire -- scalaWire).toList.sorted
                 .mkString(", ")}
                |  only in Dashboard.scala: ${(scalaWire -- pklWire).toList.sorted
                 .mkString(", ")}
                |A field on one side and not the other decodes to its default and
                |is silently ignored — add it to both, or exclude it here with a
                |reason.""".stripMargin
    )
  }

  test("the component node agrees on both sides") {
    // The biggest wire class, and the one where a rename is cheapest to get
    // half-right: `regions` is emitted by Pkl and decoded here, while the
    // `children` an author writes is `hidden` and stops at the authoring layer.
    // If only one side moved, this says so by name instead of leaving every
    // child of every container to decode to `Map.empty`.
    //
    // `inlineSurfaces` is Pkl-only on purpose: it is a BUILD-PHASE marker that
    // `DashboardBuild.hoistInlineSurfaces` lifts and removes, so the runtime
    // model never sees one.
    check(
      "Node",
      LayoutNode.Component("card"),
      pklOnly = Set("inlineSurfaces")
    )
  }

  test("SetNode / SetMember / SetClause agree on both sides") {
    check("SetNode", LayoutNode.SetNode())
    check("SetMember", LayoutNode.SetMember())
    check("SetClause", LayoutNode.SetClause(node = LayoutNode.SetNode()))
  }

  test("the predicate AST agrees on both sides") {
    check("Cmp", Predicate.Cmp("state", fh.view.model.Op.Eq, Json.Null))
    check("Count", Predicate.Count(op = fh.view.model.Op.Gt, value = Json.Null))
  }

  test("ordering and layout-cell shapes agree on both sides") {
    check("SortTerm", LayoutNode.SortTerm(LayoutNode.SortKey.Prop("x")))
    check("Cell", Cell())
  }

  test("the slot shape agrees, minus what only one side names") {
    // `Slot` in Pkl is `SlotSource` in Scala — the one place the two names
    // differ, kept because "slot" is the authoring word and "source" is the
    // model's.
    //
    // `literal` is Scala-only, and deliberately: Pkl has no such field, because
    // a constant slot is authored as a BARE STRING (`Slot|String`) and the
    // decoder maps that string into `literal`. So the asymmetry is an encoding,
    // not drift — which is exactly the kind of thing this test should force
    // somebody to write down rather than discover.
    check("Slot", SlotSource(), scalaOnly = Set("literal"))
  }

  test("the access rule agrees on both sides") {
    check("Users", Access.Users(Nil))
  }

  /** Structure is not enough for this one. The field NAMES can agree while
    * every `kind` literal disagrees — `check` compares names, and `kind` is the
    * one property it subtracts. A renamed constructor on either side then makes
    * `access` undecodable or absent, and an absent rule takes the site default,
    * which is the direction that fails OPEN for an ACCESS rule. So the literals
    * are pinned by decoding what Pkl actually emits.
    */
  test("every access constructor decodes to the rule the author wrote") {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp)
    os.write(
      tmp / "access-probe.pkl",
      """module accessProbe
        |import "@fh-dashboard/components.pkl" as c
        |import "@fh-dashboard/hass.pkl"
        |
        |// Reached through the FACADE, so this also pins that the re-export
        |// stays wired — an author writes `c.access.admin`, not an import.
        |publicRule = c.access.public
        |authenticatedRule = c.access.authenticated
        |adminRule = c.access.admin
        |usersRule = c.access.users(List(
        |  new hass.User { user_id = "abc123"; user_name = "a"; is_admin = false; is_owner = false },
        |  new hass.User { user_id = "def456"; user_name = "b"; is_admin = false; is_owner = false }
        |))
        |""".stripMargin
    )
    val res = SourceEval
      .eval(tmp, "access-probe.pkl")
      .fold(e => fail(s"access probe failed: $e"), identity)

    def decode(field: String): Access =
      res.value.hcursor
        .downField(field)
        .as[Access]
        .fold(e => fail(s"$field did not decode as an Access: $e"), identity)

    assertEquals(decode("publicRule"), Access.Public)
    assertEquals(decode("authenticatedRule"), Access.Authenticated)
    assertEquals(decode("adminRule"), Access.Admin)
    assertEquals(decode("usersRule"), Access.Users(List("abc123", "def456")))
  }
}
