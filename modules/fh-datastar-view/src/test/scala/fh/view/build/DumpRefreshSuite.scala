package fh.view.build

import fh.view.testkit.{FixtureEntity, HouseFixture, PklWorkspace}
import io.circe.Json

/** Validate-then-swap ([[DumpRefresh]]): a changed home swaps in only when
  * every dashboard that builds today still builds. The swap moves the
  * `.fh/pins.json` pin to the new snapshot, the previous stays cached as the
  * trail, and a rejection leaves the pin (ADR 0010).
  */
class DumpRefreshSuite extends munit.CatsEffectSuite {

  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"
  private val bundled = LibPackage.build(bundledLib)

  /** Builds while `light.kitchen` is in the dump and breaks without it. */
  private def siteWith(extra: String = "") =
    s"""amends "@fh-dashboard/site.pkl"
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |dashboards {
       |  ["dash"] {
       |    title = "Kitchen"
       |    card = (c.grid) {
       |      children {
       |        c.title(dump.entities.light_kitchen.entity_id)
       |      }
       |    }
       |  }
       |$extra
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

  /** The state after a boot's `prepareDumps`. The entrypoint is written before
    * bootstrap so the starter is not seeded over it.
    */
  private def stage(entrypoint: String = siteWith()): os.Path = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    os.write(ws / Site.EntryFile, entrypoint, createFolders = true)
    val _ = PklWorkspace.bootstrapInto(ws, bundled, root / "pkl-cache")
    val _ = DumpPackage.seedFromText(ws, currentDump, Some(bundled))
    ws
  }

  private def cacheOf(ws: os.Path): os.Path = ws / os.up / "pkl-cache"
  private def homeVersions(ws: os.Path): Set[String] =
    os.list(cacheOf(ws) / "package-2" / LibPackage.Host)
      .filter(_.last.startsWith("fh-home@"))
      .map(_.last)
      .toSet

  test("a byte-identical dump is a no-op") {
    val ws = stage()
    val before = Pins.homeVersion(ws)
    DumpRefresh.refresh(currentDump, ws).map { result =>
      assertEquals(result, DumpRefresh.Unchanged)
      assertEquals(Pins.homeVersion(ws), before)
    }
  }

  test("a green change swaps the pin; the old snapshot stays in the cache") {
    val ws = stage()
    val before = Pins.homeVersion(ws).getOrElse(fail("no initial pin"))
    // No dashboard references the TV.
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    DumpRefresh.refresh(next, ws).map {
      case DumpRefresh.Swapped(version, seedLog) =>
        assertNotEquals(version, before)
        assertEquals(Pins.homeVersion(ws), Some(version))
        // A laptop pinned to the previous snapshot keeps resolving.
        assert(
          homeVersions(ws).contains(s"fh-home@$before"),
          clue = homeVersions(ws)
        )
        // Seeded into the shared cache during validation, so the swap only
        // moves the pin.
        assert(
          seedLog.exists(_.contains("pinned @fh-home")),
          clue = seedLog
        )
        assert(
          homeVersions(ws).contains(s"fh-home@$version"),
          clue = homeVersions(ws)
        )
      case other => fail(s"expected Swapped, got $other")
    }
  }

  test("a dump that breaks a building dashboard is rejected; the pin holds") {
    val ws = stage()
    val before = Pins.homeVersion(ws)
    val next =
      dumpText(HouseFixture.all.filterNot(_ == HouseFixture.kitchenLight))
    DumpRefresh.refresh(next, ws).map {
      case DumpRefresh.Rejected(errors) =>
        // Losing a named entity fails the entrypoint outright, so the rejection
        // is reported against it.
        assertEquals(errors.map(_._1), List(Site.EntryFile))
        assert(
          errors.head._2.contains("light_kitchen"),
          clue = errors.head._2
        )
        assertEquals(Pins.homeVersion(ws), before)
      case other => fail(s"expected Rejected, got $other")
    }
  }

  test("the reload after a green change reads the staged evaluation") {
    val ws = stage(
      siteWith(
        """  ["count"] {
          |    card = (c.grid) { children { c.title("n=\(dump.all.length)") } }
          |  }""".stripMargin
      )
    )
    def eval() =
      SourceEval.eval(ws, Site.EntryFile).fold(e => fail(e), identity)
    val before = eval()
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    DumpRefresh.refresh(next, ws).map {
      case DumpRefresh.Swapped(_, _) =>
        val reload = eval()
        assert(reload.fromCache)
        assertNotEquals(reload.value, before.value)
      case other => fail(s"expected Swapped, got $other")
    }
  }

  test("a dashboard that is already broken does not veto a green change") {
    // Broken under any dump: a user mid-edit must not block registry changes
    // forever.
    val ws = stage(
      siteWith(
        """  ["broken"] {
          |    card = (c.entityCard(dump.entities.light_kitchen)) {
          |      label = c.expr("(((")
          |    }
          |  }""".stripMargin
      )
    )
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    DumpRefresh.refresh(next, ws).map {
      case DumpRefresh.Swapped(version, _) =>
        assertEquals(Pins.homeVersion(ws), Some(version))
      case other => fail(s"expected Swapped, got $other")
    }
  }
}
