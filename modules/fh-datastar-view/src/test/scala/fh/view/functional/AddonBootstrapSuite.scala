package fh.view.functional

import fh.view.build.{
  AddonBootstrap,
  DumpPackage,
  LibPackage,
  Pins,
  PklDump,
  SourceEval
}
import fh.view.testkit.{HouseFixture, PklFixture}

/** The add-on boot contract (ADR 0010, "the add-on workspace"): the bundled
  * library reaches evaluation as a *pre-cached package*, never as files in the
  * user's workspace — and upgrades reconcile instead of freezing at install.
  *
  * The frozen-lib bug shipped precisely because the old seed-layout test pinned
  * the LAYOUT and nothing pinned the UPGRADE semantics; the migration and drift
  * tests here are that missing pin.
  */
class AddonBootstrapSuite extends munit.FunSuite {

  /** The repo's real library — what the Dockerfile bakes into the image. */
  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"

  private val seedDir = os.pwd / "home-addon" / "dashboards-seed"

  private val bundled = LibPackage.build(bundledLib)
  private val libVersion = bundled.version

  private val LoopbackUrl = "http://127.0.0.1:8080"

  private case class Box(ws: os.Path, cache: os.Path)

  private def boot(): (Box, List[String]) = {
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    val log =
      AddonBootstrap.run(box.ws, bundled, seedDir, box.cache, LoopbackUrl)
    (box, log)
  }

  test("first boot: seeds a lib-free workspace that evaluates offline") {
    val (box, _) = boot()

    // The user's dir holds only user files: entries + the manifests. No lib/ —
    // that was the littering the package form exists to remove.
    assert(!os.exists(box.ws / "lib"), clue = os.list(box.ws))
    assert(os.exists(box.ws / "dashboard.pkl"))

    // Ownership splits along the amends chain: the user's PklProject amends the
    // machine-owned .fh/base.pkl and is seeded WITHOUT a dependencies block (no
    // pin of its own — it tracks the base default).
    val consumer = os.read(box.ws / "PklProject")
    assert(consumer.contains("amends \".fh/base.pkl\""), clue = consumer)
    // No ACTIVE dependencies block (the docstring shows a commented example, but
    // no uncommented pin) — the workspace tracks the base default.
    assert(
      !consumer.linesIterator.exists(_.trim == "dependencies {"),
      clue = consumer
    )
    // base.pkl is machine-AGNOSTIC: no path, no URL — it READS both from json
    // siblings (machine.json for the cache + rewrite, pins.json for the pins).
    val base = os.read(box.ws / ".fh" / "base.pkl")
    assert(base.contains("read(\"machine.json\")"), clue = base)
    assert(base.contains("read(\"pins.json\")"), clue = base)
    assert(
      base.contains("machine.instanceUrl + \"/system/pkl/packages/\""),
      clue = base
    )
    assert(!base.contains(box.cache.toString), clue = base)
    // The per-machine values live in machine.json: THIS instance's cache path +
    // its own loopback URL (inert here, a cache hit; copy-usable elsewhere).
    val machine = os.read(box.ws / ".fh" / "machine.json")
    assert(machine.contains(box.cache.toString), clue = machine)
    assert(machine.contains(LoopbackUrl), clue = machine)
    // pins.json is real-or-nothing: a fresh workspace has NONE until the first
    // dump writes all three keys at once. There is no placeholder, no `home/`.
    assert(
      !os.exists(box.ws / ".fh" / "pins.json"),
      clue = os.list(box.ws / ".fh")
    )
    assert(!os.exists(box.ws / "home"), clue = os.list(box.ws))
    // A default .gitignore excludes the per-machine + generated files.
    val gitignore = os.read(box.ws / ".gitignore")
    assert(gitignore.contains(".fh/machine.json"), clue = gitignore)

    // The cache entry is exactly the resolved-package layout pkl expects.
    val entry = LibPackage.cacheEntryDir(box.cache, libVersion)
    assert(os.exists(entry / s"fh-dashboard@$libVersion.zip"))
    assert(os.exists(entry / s"fh-dashboard@$libVersion.json"))

    // Seed a dump (what `prepareDumps` does at startup — it mints pins.json with
    // real values, given the bundled lib pin), then the starter EVALUATES fully
    // offline — PklBuild's resolver uses a dummy http client, so the warm cache
    // is the only source.
    val _ =
      DumpPackage.seedFromText(
        box.ws,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    val result = SourceEval.eval(box.ws, "dashboard.pkl")
    assert(result.isRight, clue = result)
  }

  test("a dump-importing entry typechecks against the packaged schema") {
    // The module-identity constraint under the package form: the dump package's
    // declared `@fh-dashboard` dependency and the entry's
    // `@fh-dashboard/components.pkl` must land on ONE cached artifact, or passing
    // `dump.entities.*` into a card factory is a Pkl type error. This is the
    // packaged twin of ADR 0010's identity table.
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
    // A pre-package-form install: the old seeding copied lib/ into the workspace
    // and wrote a machine-era consumer. We no longer migrate — nothing the user
    // authored is moved or overwritten — so lib/ and the consumer stay put (that
    // consumer keeps resolving path-form against its own lib/). Only the
    // generated lockfile is removed. To adopt the package-form wiring the user
    // deletes the consumer, opting into a fresh re-seed.
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

    val _ =
      AddonBootstrap.run(box.ws, bundled, seedDir, box.cache, LoopbackUrl)

    // Nothing user-authored is touched: lib/, the consumer + entry all stay, and
    // no backup is made. The stale lockfile IS removed (generated artifact), and
    // there is no starter seeding (the user HAS an entry).
    assert(os.exists(box.ws / "lib"))
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
    assertEquals(os.read(box.ws / "PklProject"), oldConsumer)
    assertEquals(os.read(box.ws / "mine.pkl"), "// the user's own entry\n")
    assert(!os.exists(box.ws / "PklProject.deps.json"))
    assert(!os.exists(box.ws / "dashboard.pkl"))

    // Recovery: deleting the machine-era consumer opts into a fresh, package-form
    // re-seed — then it evaluates.
    os.remove(box.ws / "PklProject")
    val _ =
      AddonBootstrap.run(box.ws, bundled, seedDir, box.cache, LoopbackUrl)
    val _ = DumpPackage.seedFromText(
      box.ws,
      PklDump.render(HouseFixture.transformedDump),
      Some(bundled)
    )
    os.write.over(box.ws / "mine.pkl", os.read(seedDir / "dashboard.pkl"))
    assert(SourceEval.eval(box.ws, "mine.pkl").isRight)
  }

  test("a user-customized manifest is never rewritten") {
    val (box, _) = boot()
    val customized =
      os.read(box.ws / "PklProject") +
        "\n// user added a third-party card package here\n"
    os.write.over(box.ws / "PklProject", customized)

    val _ =
      AddonBootstrap.run(box.ws, bundled, seedDir, box.cache, LoopbackUrl)

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
      AddonBootstrap.run(box.ws, bundled, seedDir, box.cache, LoopbackUrl)
    assert(log.isEmpty, clue = log)
    assertEquals(os.mtime(zipPath), mtime)
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
  }

  test("a changed lib mints a NEW content version; the old entry survives") {
    // Content-derived versions make drift-under-an-unchanged-version
    // impossible: changed lib bytes hash to a new version, the bootstrap seeds
    // a fresh immutable cache entry and moves the pins.json dashboardUri to
    // it, and the previous entry stays resolvable for anything pinned to it.
    val root = os.temp.dir()
    val editableLib = root / "lib"
    os.copy(bundledLib, editableLib)
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    val v1 = LibPackage.version(editableLib)
    val bundledV1 = LibPackage.build(editableLib)
    val _ =
      AddonBootstrap.run(box.ws, bundledV1, seedDir, box.cache, LoopbackUrl)
    // Seed a dump so pins.json exists at v1 — only then does the bootstrap's
    // dashboardUri refresh have a file to move (real-or-nothing pins).
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
      AddonBootstrap.run(box.ws, bundledV2, seedDir, box.cache, LoopbackUrl)

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
