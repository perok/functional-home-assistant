package fh.view.build

import fh.view.model.{
  Access,
  Cell,
  LayoutNode,
  Predicate,
  SlotQuery,
  SlotSource,
  Theme
}
import fh.view.testkit.PklWorkspace
import io.circe.Json

/** The wire shape is declared twice, a Pkl class in the library and a Scala
  * case class in `Dashboard.scala`, and a field on one side only decodes to its
  * default, silently. The snapshots notice only when a fixture exercises the
  * field, as a confusing JSON diff. This compares both by reflection
  * (`pkl:reflect`, `productElementNames`), turning drift into a named failure.
  *
  * Names, not types: `Listing<String>` and `List[String]` are the same wire
  * array, and a type correspondence would be a second model to maintain.
  */
class WireShapeSuite extends munit.FunSuite {

  /** `hidden` properties are authoring inputs that never reach the wire (a
    * card's `entity`, an `If`'s `then`/`else`), so they are excluded.
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
        |import "@fh-dashboard/theme.pkl" as themeMod
        |
        |// The wire shape is spread across the library's modules, and reflection
        |// sees only what a module DECLARES — the facade declares no classes at
        |// all. So merge every module that owns wire classes; that also keeps
        |// "does this module own the ancestor" true across a module boundary
        |// (`SetNode extends LayoutNode` now spans two files).
        |local mods: List<Module> =
        |  List(nodes, slotMod, surfaceMod, tapMod, pred, accessMod, themeMod) + c.modules
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
    // The circe discriminator: explicit in Pkl, supplied by decoder
    // configuration in Scala.
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
    // `regions` is emitted and decoded, while the authored `children` is
    // `hidden`. If only one side moved, every container's children would decode
    // to `Map.empty`. `inlineSurfaces` is Pkl-only: a build-phase marker
    // `DashboardBuild.hoistInlineSurfaces` lifts and removes.
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
    // `Slot` is `SlotSource` in Scala: "slot" is the authoring word, "source"
    // the model's. `literal` is Scala-only because a constant slot is authored
    // as a bare string (`Slot|String`) that the decoder maps into it: an
    // encoding, not drift. `values` is Scala-only because it is the NODE's
    // expression values, attached at decode to the slots that read them.
    check("Slot", SlotSource(), scalaOnly = Set("literal", "values"))
    // `params` is untyped on the wire, so nothing else would notice the sides
    // disagreeing on what a query is.
    check("Query", SlotQuery("history", Map.empty))
  }

  test("the theme agrees on both sides") {
    check("Theme", Theme())
  }

  test("the access rule agrees on both sides") {
    check("Users", Access.Users(Nil))
  }

  /** `check` subtracts `kind`, so names can agree while every `kind` literal
    * disagrees. A renamed constructor makes `access` undecodable or absent, and
    * an absent rule takes the site default: failing open, for an access rule.
    * So the literals are pinned by decoding what Pkl emits.
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
