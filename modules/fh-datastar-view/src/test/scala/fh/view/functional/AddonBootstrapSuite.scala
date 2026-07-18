package fh.view.functional

import fh.view.build.{
  AddonBootstrap,
  DumpPackage,
  LibPackage,
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

  private val libVersion = LibPackage.version(bundledLib)

  private case class Box(ws: os.Path, cache: os.Path)

  private def boot(): (Box, List[String]) = {
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)
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
    // pin of its own — it tracks the base default). The base points every pkl
    // tool at the persistent cache and reads both pins from pins.json.
    val consumer = os.read(box.ws / "PklProject")
    assert(consumer.contains("amends \".fh/base.pkl\""), clue = consumer)
    // No ACTIVE dependencies block (the docstring shows a commented example, but
    // no uncommented pin) — the workspace tracks the base default.
    assert(
      !consumer.linesIterator.exists(_.trim == "dependencies {"),
      clue = consumer
    )
    val base = os.read(box.ws / ".fh" / "base.pkl")
    assert(base.contains(box.cache.toString), clue = base)
    assert(base.contains("read(\"pins.json\")"), clue = base)
    // Both pins are DATA in pins.json: the lib pin at the bundled version, the
    // home pin a placeholder until the first dump. There is no `home/` folder.
    val pins = os.read(box.ws / ".fh" / "pins.json")
    assert(pins.contains(LibPackage.packageUri(libVersion)), clue = pins)
    assert(!os.exists(box.ws / "home"), clue = os.list(box.ws))

    // The cache entry is exactly the resolved-package layout pkl expects.
    val entry = LibPackage.cacheEntryDir(box.cache, libVersion)
    assert(os.exists(entry / s"fh-dashboard@$libVersion.zip"))
    assert(os.exists(entry / s"fh-dashboard@$libVersion.json"))

    // Seed a dump (what `prepareDumps` does at startup, moving the pin off the
    // placeholder), then the starter EVALUATES fully offline — PklBuild's
    // resolver uses a dummy http client, so the warm cache is the only source.
    val _ =
      DumpPackage.seedFromText(
        box.ws,
        PklDump.render(HouseFixture.transformedDump)
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
        PklDump.render(HouseFixture.transformedDump)
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

  test(
    "upgrade: lib/ litter is backed up; the user's manifest is left untouched"
  ) {
    // An existing install: the old seeding copied lib/ into the workspace and
    // wrote a machine-era consumer plus a loose home dump. Under write-once the
    // consumer is the user's — never rewritten — while the lib/ litter and the
    // pre-package home dump are backed up (never deleted).
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    os.makeDir.all(box.ws / "home")
    os.copy(bundledLib, box.ws / "lib")
    val oldConsumer =
      """amends "pkl:Project"
        |dependencies {
        |  ["fh-dashboard"] = import("./lib/PklProject")
        |}
        |""".stripMargin
    os.write(box.ws / "PklProject", oldConsumer)
    os.write(box.ws / "home" / "dump.pkl", "// old dump\n")
    os.write(box.ws / "PklProject.deps.json", """{"stale": true}""")
    os.write(box.ws / "mine.pkl", "// the user's own entry\n")

    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)

    // lib/ and the loose home dump leave as dated backups, never deleted.
    assert(!os.exists(box.ws / "lib"))
    assertEquals(
      os.list(box.ws).count(_.last.startsWith("lib.backup.")),
      1,
      clue = os.list(box.ws)
    )
    assert(
      os.list(box.ws / "home").exists(_.last.startsWith("dump.pkl.backup.")),
      clue = os.list(box.ws / "home")
    )

    // Write-once: the user's consumer + entry are untouched, no consumer backup;
    // the stale lockfile is gone; no starter seeding (the user HAS an entry).
    assertEquals(os.read(box.ws / "PklProject"), oldConsumer)
    assert(!os.list(box.ws).exists(_.last.startsWith("PklProject.backup.")))
    assertEquals(os.read(box.ws / "mine.pkl"), "// the user's own entry\n")
    assert(!os.exists(box.ws / "PklProject.deps.json"))
    assert(!os.exists(box.ws / "dashboard.pkl"))
    assert(log.exists(_.contains("migrated")), clue = log)

    // Recovery: the old consumer's `./lib` dep now dangles (lib/ moved).
    // Deleting it opts into a fresh, package-form re-seed — then it evaluates.
    os.remove(box.ws / "PklProject")
    val _ = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)
    val _ = DumpPackage.seedFromText(
      box.ws,
      PklDump.render(HouseFixture.transformedDump)
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

    val _ = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)

    assertEquals(os.read(box.ws / "PklProject"), customized)
    assert(!os.list(box.ws).exists(_.last.startsWith("PklProject.backup.")))
  }

  test("second boot is quiet: no new backups, no re-seeding, cache untouched") {
    val (box, _) = boot()
    val zipPath =
      LibPackage.cacheEntryDir(box.cache, libVersion) /
        s"fh-dashboard@$libVersion.zip"
    val mtime = os.mtime(zipPath)
    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)
    assert(log.isEmpty, clue = log)
    assertEquals(os.mtime(zipPath), mtime)
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
  }

  test("lib drift under an unchanged version overwrites the cache, loudly") {
    // The release-discipline violation: lib bytes change, version doesn't
    // (e.g. a dev image rebuild). The instance must run what it ships — the
    // cache entry is overwritten — but the log names the violation, because
    // any laptop that pinned the old checksum will now fail resolution.
    val root = os.temp.dir()
    val editableLib = root / "lib"
    os.copy(bundledLib, editableLib)
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    val _ = AddonBootstrap.run(box.ws, editableLib, seedDir, box.cache)

    os.write.append(editableLib / "tokens.pkl", "\n// drifted\n")
    val log = AddonBootstrap.run(box.ws, editableLib, seedDir, box.cache)

    assert(log.exists(_.contains("WARNING")), clue = log)
    val zip = LibPackage.cacheEntryDir(box.cache, libVersion) /
      s"fh-dashboard@$libVersion.zip"
    assertEquals(
      LibPackage.sha256(os.read.bytes(zip)),
      LibPackage.sha256(LibPackage.zipBytes(editableLib))
    )
  }
}
