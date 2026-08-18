package fh.view.build

import fh.view.testkit.{FixtureEntity, HouseFixture}
import io.circe.Json

/** Validate-then-swap for the regenerated dump ([[DumpRefresh]]): a changed
  * home swaps in only when every dashboard that builds today still builds. In
  * package terms (ADR 0010, no loose `home/dump.pkl`): the swap moves the
  * `.fh/pins.json` pin to the new content-versioned snapshot, the PREVIOUS
  * version stays in the cache as the trail, and a rejected swap leaves the pin
  * untouched.
  */
class DumpRefreshSuite extends munit.CatsEffectSuite {

  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"
  private val bundled = LibPackage.build(bundledLib)

  /** A site with one dashboard pinned to a concrete dump entity — it builds
    * while `light.kitchen` is in the dump and breaks when it isn't. `extra` is
    * spliced in as further keys by the test that needs a second dashboard.
    */
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

  /** A package-form workspace (the add-on shape) with one dump-referencing
    * dashboard and the fixture dump seeded as its `@fh-home` package — the
    * state after a boot's `prepareDumps`. The entrypoint is written BEFORE
    * bootstrap so the starter site is not seeded over it (one small site keeps
    * the evals fast and the assertions specific).
    */
  private def stage(entrypoint: String = siteWith()): os.Path = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    os.write(ws / Site.EntryFile, entrypoint, createFolders = true)
    val _ = AddonBootstrap.run(
      ws,
      bundled,
      root / "pkl-cache",
      loopbackUrl = "http://127.0.0.1:8080"
    )
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
    // The TV leaves the home — no dashboard references it, so all stay green.
    val next = dumpText(HouseFixture.all.filterNot(_ == HouseFixture.tv))
    DumpRefresh.refresh(next, ws).map {
      case DumpRefresh.Swapped(version, seedLog) =>
        assertNotEquals(version, before)
        assertEquals(Pins.homeVersion(ws), Some(version))
        // The previous snapshot is untouched — a laptop pinned to it keeps
        // resolving.
        assert(
          homeVersions(ws).contains(s"fh-home@$before"),
          clue = homeVersions(ws)
        )
        // The package was already seeded into the shared cache during
        // validation, so the swap's log just records the pin move.
        assert(
          seedLog.exists(_.contains("pinned @fh-home")),
          clue = seedLog
        )
        // Both snapshots now live in the cache.
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
        // The dashboard NAMES the entity, so losing it fails the entrypoint's
        // evaluation outright — nothing can be attributed to a slug, and the
        // rejection is reported against the entrypoint itself.
        assertEquals(errors.map(_._1), List(Site.EntryFile))
        assert(
          errors.head._2.contains("light_kitchen"),
          clue = errors.head._2
        )
        // The pin is untouched: still the current snapshot.
        assertEquals(Pins.homeVersion(ws), before)
      case other => fail(s"expected Rejected, got $other")
    }
  }

  test("a dashboard that is already broken does not veto a green change") {
    // Broken under ANY dump (an unknown card) — a user mid-edit must not block
    // registry changes forever. It is a second KEY now, not a second file.
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
