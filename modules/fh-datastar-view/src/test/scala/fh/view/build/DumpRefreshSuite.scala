package fh.view.build

import fh.view.testkit.{FixtureEntity, HouseFixture}
import io.circe.Json

/** Validate-then-swap for the regenerated dump ([[DumpRefresh]]): a changed
  * home swaps in only when every dashboard that builds today still builds, the
  * replaced dump is kept as a dated backup, and a rejected swap leaves the
  * workspace untouched.
  */
class DumpRefreshSuite extends munit.CatsEffectSuite {

  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"
  private val seedDir = os.pwd / "home-addon" / "dashboards-seed"

  /** An entry pinned to a concrete dump entity — it builds while
    * `light.kitchen` is in the dump and breaks when it isn't.
    */
  private val kitchenEntry =
    """amends "@fh-dashboard/entry.pkl"
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-home/dump.pkl" as dump
      |title = "Kitchen"
      |card = (c.grid) {
      |  children {
      |    c.title(dump.entities.light_kitchen.entity_id)
      |  }
      |}
      |""".stripMargin

  private def dumpText(entities: List[FixtureEntity]): String =
    PklDump.render(
      Json.obj(
        "areas" -> Json.obj(),
        "floors" -> Json.obj(),
        "entities" -> Json.fromFields(entities.map(_.toDumpEntry))
      )
    )

  private val currentDump = dumpText(HouseFixture.all)

  /** A package-form workspace (the add-on shape) with one dump-referencing
    * entry and the fixture dump written — the state after a boot's
    * `prepareDumps`. The entry is written BEFORE bootstrap so the starter
    * dashboards are not seeded (one entry keeps the evals fast and the
    * assertions specific).
    */
  private def stage(): os.Path = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    os.write(ws / "dash.pkl", kitchenEntry, createFolders = true)
    val _ = AddonBootstrap.run(ws, bundledLib, seedDir, root / "pkl-cache")
    os.write(DashboardBuild.dumpPath(ws), currentDump, createFolders = true)
    val _ = DumpPackage.seedFromWorkspace(ws)
    ws
  }

  private val entries = List("dash" -> "dash.pkl")

  private def backups(ws: os.Path): Seq[os.Path] =
    os.list(ws / "home").filter(_.last.startsWith("dump.pkl.backup."))

  test("a byte-identical dump is a no-op") {
    val ws = stage()
    DumpRefresh.refresh(currentDump, ws, entries).map { result =>
      assertEquals(result, DumpRefresh.Unchanged)
      assertEquals(os.read(DashboardBuild.dumpPath(ws)), currentDump)
      assertEquals(backups(ws), Seq.empty)
    }
  }

  test("a green change swaps in, keeping the old dump as a dated backup") {
    val ws = stage()
    // The TV leaves the home — no dashboard references it, so all stay green.
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    DumpRefresh.refresh(next, ws, entries).map {
      case DumpRefresh.Swapped(backup, seedLog) =>
        assertEquals(os.read(DashboardBuild.dumpPath(ws)), next)
        // The replaced dump was renamed, not deleted, and keeps its bytes.
        val kept = backup.getOrElse(fail("no backup of the previous dump"))
        assertEquals(backups(ws), Seq(kept))
        assertEquals(os.read(kept), currentDump)
        // The new snapshot was packaged (a laptop pull sees the new version).
        assert(
          seedLog.exists(_.contains("seeded dump package")),
          clue = seedLog
        )
      case other => fail(s"expected Swapped, got $other")
    }
  }

  test("a dump that breaks a building dashboard is rejected; nothing moves") {
    val ws = stage()
    val next =
      dumpText(HouseFixture.all.filterNot(_ == HouseFixture.kitchenLight))
    DumpRefresh.refresh(next, ws, entries).map {
      case DumpRefresh.Rejected(errors) =>
        assertEquals(errors.map(_._1), List("dash"))
        assert(
          errors.head._2.contains("light_kitchen"),
          clue = errors.head._2
        )
        // The workspace is untouched: current dump still in place, no backup.
        assertEquals(os.read(DashboardBuild.dumpPath(ws)), currentDump)
        assertEquals(backups(ws), Seq.empty)
      case other => fail(s"expected Rejected, got $other")
    }
  }

  test("an entry that is already broken does not veto a green change") {
    val ws = stage()
    // Broken under ANY dump (bad reference) — a user mid-edit must not block
    // registry changes forever.
    os.write(
      ws / "broken.pkl",
      """amends "@fh-dashboard/entry.pkl"
        |card = noSuchThing
        |""".stripMargin
    )
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    val both = entries :+ ("broken" -> "broken.pkl")
    DumpRefresh.refresh(next, ws, both).map {
      case DumpRefresh.Swapped(_, _) =>
        assertEquals(os.read(DashboardBuild.dumpPath(ws)), next)
      case other => fail(s"expected Swapped, got $other")
    }
  }
}
