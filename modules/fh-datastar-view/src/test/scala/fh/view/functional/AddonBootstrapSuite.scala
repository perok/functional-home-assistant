package fh.view.functional

import fh.view.build.{
  AddonBootstrap,
  DumpPackage,
  LibPackage,
  Pins,
  PklDump,
  Site,
  SourceEval
}
import fh.view.testkit.{HouseFixture, PklFixture, PklWorkspace}

/** The add-on boot contract (ADR 0010): the bundled library reaches evaluation
  * as a pre-cached package, never as workspace files, and upgrades reconcile
  * instead of freezing. A frozen-lib bug shipped because a layout test pinned
  * nothing about upgrades.
  */
class AddonBootstrapSuite extends munit.FunSuite {

  /** What the Dockerfile bakes into the image. */
  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"

  private val bundled = LibPackage.build(bundledLib)
  private val libVersion = bundled.version

  private case class Box(ws: os.Path, cache: os.Path)

  private def boot(): (Box, List[String]) = {
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    // The instance's boot plus the `machine.json` a reader writes for itself;
    // the instance writes none, and a test cannot set an env var per case.
    val log = PklWorkspace.bootstrapInto(box.ws, bundled, box.cache)
    (box, log)
  }

  test("first boot: seeds a lib-free workspace that evaluates offline") {
    val (box, _) = boot()

    // No lib/: the littering the package form exists to remove.
    assert(!os.exists(box.ws / "lib"), clue = os.list(box.ws))
    assert(os.exists(box.ws / "site.pkl"))

    // The user's PklProject amends the machine-owned .fh/base.pkl, with no pin
    // of its own.
    val consumer = os.read(box.ws / "PklProject")
    assert(consumer.contains("amends \".fh/base.pkl\""), clue = consumer)
    // The docstring shows a commented example; no uncommented pin.
    assert(
      !consumer.linesIterator.exists(_.trim == "dependencies {"),
      clue = consumer
    )
    // Machine-agnostic: per-reader values come from the environment first,
    // `machine.json` second.
    val base = os.read(box.ws / ".fh" / "base.pkl")
    assert(base.contains("read?(\"env:FH_PKL_CACHE_DIR\")"), clue = base)
    assert(base.contains("read?(\"env:FH_INSTANCE_URL\")"), clue = base)
    assert(base.contains("read?(\"machine.json\")"), clue = base)
    assert(base.contains("read(\"pins.json\")"), clue = base)
    assert(
      base.contains("instanceUrl!! + \"/system/pkl/packages/\""),
      clue = base
    )
    assert(!base.contains(box.cache.toString), clue = base)
    // Real-or-nothing: no pins.json until the first dump writes all three keys.
    assert(
      !os.exists(box.ws / ".fh" / "pins.json"),
      clue = os.list(box.ws / ".fh")
    )
    assert(!os.exists(box.ws / "home"), clue = os.list(box.ws))
    val gitignore = os.read(box.ws / ".gitignore")
    assert(gitignore.contains(".fh/machine.json"), clue = gitignore)

    val entry = LibPackage.cacheEntryDir(box.cache, libVersion)
    assert(os.exists(entry / s"fh-dashboard@$libVersion.zip"))
    assert(os.exists(entry / s"fh-dashboard@$libVersion.json"))

    // As `prepareDumps` does at startup, then evaluate offline: PklBuild's
    // resolver uses a dummy http client, so the warm cache is the only source.
    val _ =
      DumpPackage.seedFromText(
        box.ws,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    val result = SourceEval.eval(box.ws, "site.pkl")
    assert(result.isRight, clue = result)
  }

  test("the instance imposes none of its own paths on a shared workspace") {
    // One dashboards directory serves an HA device, a laptop and a container at
    // once. When the add-on wrote its paths into `.fh/machine.json` on every
    // start, the last machine to boot decided for the others, and a container
    // handed a path outside it failed every dashboard with
    // "AccessDeniedException".
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val laptop = AddonBootstrap.machineFileJson(
      cacheDir = Some(os.root / "home" / "someone" / ".pkl" / "cache"),
      instanceUrl = Some("http://ha.local:8123")
    )
    os.write(ws / ".fh" / "machine.json", laptop, createFolders = true)

    val _ = AddonBootstrap.run(ws, bundled, root / "pkl-cache")

    assertEquals(os.read(ws / ".fh" / "machine.json"), laptop)
    val fresh = os.temp.dir() / "fh-dashboards"
    val _ = AddonBootstrap.run(fresh, bundled, root / "pkl-cache")
    assert(
      !os.exists(fresh / ".fh" / "machine.json"),
      clue = os.list(fresh / ".fh")
    )
  }

  test("a dump-importing entry typechecks against the packaged schema") {
    // The dump package's `@fh-dashboard` and the entry's
    // `@fh-dashboard/components.pkl` must land on one cached artifact, or
    // passing `dump.entities.*` to a card factory is a Pkl type error (ADR
    // 0010's identity table).
    val (box, _) = boot()
    val _ =
      DumpPackage.seedFromText(
        box.ws,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    os.write(
      box.ws / "mine.pkl",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-home/dump.pkl" as dump
         |import "@fh-dashboard/theme.pkl" as th
         |
         |theme = ${PklFixture.dummyTheme}
         |
         |card = (c.column) {
         |  children {
         |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
         |  }
         |}
         |""".stripMargin
    )
    val result = SourceEval.eval(box.ws, "mine.pkl")
    assert(result.isRight, clue = result)
  }

  test("upgrade: user files are left untouched; delete-to-reseed recovers") {
    // A pre-package-form install. Nothing user-authored is moved, so lib/ and
    // the consumer stay and keep resolving path-form; only the lockfile goes.
    // Deleting the consumer opts into a fresh re-seed.
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    os.makeDir.all(box.ws)
    os.copy(bundledLib, box.ws / "lib")
    val oldConsumer =
      """amends "pkl:Project"
        |dependencies {
        |  ["fh-dashboard"] = import("./lib/PklProject")
        |}
        |""".stripMargin
    os.write(box.ws / "PklProject", oldConsumer)
    os.write(box.ws / "PklProject.deps.json", """{"stale": true}""")
    os.write(box.ws / "mine.pkl", "// the user's own entry\n")

    val bootLog =
      AddonBootstrap.run(box.ws, bundled, box.cache)

    // A starter entrypoint is seeded, since a loose `*.pkl` is an ordinary
    // module (ADR 0021) and cannot stand in for one.
    assert(os.exists(box.ws / "lib"))
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
    assertEquals(os.read(box.ws / "PklProject"), oldConsumer)
    assertEquals(os.read(box.ws / "mine.pkl"), "// the user's own entry\n")
    assert(!os.exists(box.ws / "PklProject.deps.json"))
    assertEquals(
      os.read(box.ws / Site.EntryFile),
      AddonBootstrap.starterSite
    )
    // The boot names the old entries, which are modules now and a key away from
    // serving; otherwise the user infers it from an instance that looks empty.
    assert(
      bootLog.exists(l =>
        l.contains("mine.pkl") && l.contains("ordinary modules")
      ),
      clue = bootLog
    )
    // Said once, on the boot that seeded the entrypoint.
    val second = AddonBootstrap.run(box.ws, bundled, box.cache)
    assert(!second.exists(_.contains("mine.pkl")), clue = second)

    // `bootstrapInto`, since evaluating needs the reader's cache dir and this
    // hand-built workspace has none.
    val _ = os.remove(box.ws / "PklProject")
    val _ = PklWorkspace.bootstrapInto(box.ws, bundled, box.cache)
    val _ = DumpPackage.seedFromText(
      box.ws,
      PklDump.render(HouseFixture.transformedDump),
      Some(bundled)
    )
    os.write.over(box.ws / Site.EntryFile, AddonBootstrap.starterSite)
    assert(SourceEval.eval(box.ws, Site.EntryFile).isRight)
  }

  test("a user-customized manifest is never rewritten") {
    val (box, _) = boot()
    val customized =
      os.read(box.ws / "PklProject") +
        "\n// user added a third-party card package here\n"
    os.write.over(box.ws / "PklProject", customized)

    val _ =
      AddonBootstrap.run(box.ws, bundled, box.cache)

    assertEquals(os.read(box.ws / "PklProject"), customized)
    assert(!os.list(box.ws).exists(_.last.startsWith("PklProject.backup.")))
  }

  test("second boot is quiet: no new backups, no re-seeding, cache untouched") {
    val (box, _) = boot()
    val zipPath =
      LibPackage.cacheEntryDir(box.cache, libVersion) /
        s"fh-dashboard@$libVersion.zip"
    val mtime = os.mtime(zipPath)
    val log =
      AddonBootstrap.run(box.ws, bundled, box.cache)
    assert(log.isEmpty, clue = log)
    assertEquals(os.mtime(zipPath), mtime)
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
  }

  test("an unusable cache dir fails the boot instead of every dashboard") {
    val (box, _) = boot()
    val before = os.read(box.ws / ".fh" / "machine.json")

    // Unlike a chmod, a file in the way still holds when the suite runs as
    // root.
    val blocker = os.temp.dir() / "not-a-dir"
    os.write(blocker, "")
    val unusable = blocker / "pkl-cache"

    val e = intercept[RuntimeException](
      AddonBootstrap.run(box.ws, bundled, unusable)
    )
    assert(e.getMessage.contains(unusable.toString), clue = e.getMessage)
    assert(e.getMessage.contains("FH_PKL_CACHE_DIR"), clue = e.getMessage)

    // Seeding before that write went wrong quietly: the seed threw, an earlier
    // run's path survived, and it resurfaced as pkl's I/O error per dashboard.
    assertEquals(os.read(box.ws / ".fh" / "machine.json"), before)
  }

  test("a changed lib mints a NEW content version; the old entry survives") {
    // Changed lib bytes hash to a new version: a fresh cache entry, pins.json's
    // dashboardUri moved to it, and the previous entry still resolvable.
    val root = os.temp.dir()
    val editableLib = root / "lib"
    os.copy(bundledLib, editableLib)
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    val v1 = LibPackage.version(editableLib)
    val bundledV1 = LibPackage.build(editableLib)
    val _ =
      AddonBootstrap.run(box.ws, bundledV1, box.cache)
    // pins.json must exist for the refresh to move it.
    val _ = DumpPackage.seedFromText(
      box.ws,
      PklDump.render(HouseFixture.transformedDump),
      Some(bundledV1)
    )
    assertEquals(Pins.dashboardVersion(box.ws), Some(v1))

    os.write.append(editableLib / "tokens.pkl", "\n// changed\n")
    val v2 = LibPackage.version(editableLib)
    val bundledV2 = LibPackage.build(editableLib)
    val log =
      AddonBootstrap.run(box.ws, bundledV2, box.cache)

    assertNotEquals(v1, v2)
    assert(log.exists(_.contains(s"fh-dashboard@$v2")), clue = log)
    for (v <- List(v1, v2)) {
      val entry = LibPackage.cacheEntryDir(box.cache, v)
      assert(os.exists(entry / s"fh-dashboard@$v.zip"), clue = v)
    }
    assertEquals(
      LibPackage.sha256(
        os.read.bytes(
          LibPackage.cacheEntryDir(box.cache, v2) / s"fh-dashboard@$v2.zip"
        )
      ),
      LibPackage.sha256(LibPackage.zipBytes(editableLib))
    )
    assertEquals(Pins.dashboardVersion(box.ws), Some(v2))
  }
}
