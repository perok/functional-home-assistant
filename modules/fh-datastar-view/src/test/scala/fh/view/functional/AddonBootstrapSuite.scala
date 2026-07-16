package fh.view.functional

import fh.view.build.{AddonBootstrap, LibPackage, PklDump, SourceEval}
import fh.view.testkit.{HouseFixture, PklFixture}

/** The add-on boot contract (ADR 0010, "the add-on workspace"): the bundled
  * library reaches evaluation as a *pre-cached package*, never as files in the
  * user's workspace — and upgrades reconcile instead of freezing at install.
  *
  * The frozen-lib bug shipped precisely because the old seed-layout test
  * pinned the LAYOUT and nothing pinned the UPGRADE semantics; the migration
  * and drift tests here are that missing pin.
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

    // Ownership splits along the amends chain: the user's PklProject amends
    // the machine-owned .fh/base.pkl and holds the pin; the base points every
    // pkl tool (the server, pkl-lsp) at the same persistent cache and carries
    // the bundled-version default.
    val consumer = os.read(box.ws / "PklProject")
    assert(consumer.contains("amends \".fh/base.pkl\""), clue = consumer)
    assert(consumer.contains(LibPackage.packageUri(libVersion)), clue = consumer)
    val base = os.read(box.ws / ".fh" / "base.pkl")
    assert(base.contains(box.cache.toString), clue = base)
    assert(base.contains(LibPackage.packageUri(libVersion)), clue = base)
    assert(
      os.read(box.ws / "home" / "PklProject")
        .contains(LibPackage.packageUri(libVersion))
    )

    // The cache entry is exactly the resolved-package layout pkl expects.
    val entry = LibPackage.cacheEntryDir(box.cache, libVersion)
    assert(os.exists(entry / s"fh-dashboard@$libVersion.zip"))
    assert(os.exists(entry / s"fh-dashboard@$libVersion.json"))

    // And the starter EVALUATES, fully offline (PklBuild's resolver uses a
    // dummy http client — the warm cache is the only possible source), before
    // `prepareDumps` has ever run — so with the `@fh-home` package still empty.
    val result = SourceEval.eval(box.ws, "dashboard.pkl")
    assert(result.isRight, clue = result)
  }

  test("a dump-importing entry typechecks against the packaged schema") {
    // The module-identity constraint under the package form: the dump's
    // `import "@fh-dashboard/hass.pkl"` (via home/PklProject's REMOTE dep) and
    // the entry's `@fh-dashboard/components.pkl` (via the consumer's) must
    // land on ONE cached artifact, or passing `dump.entities.*` into a card
    // factory is a Pkl type error. This is the packaged twin of ADR 0010's
    // identity table.
    val (box, _) = boot()
    os.write(
      box.ws / "home" / "dump.pkl",
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

  test("upgrade from the copy-if-empty layout migrates with dated backups") {
    // Stage what an existing install looks like: the old seeding copied lib/
    // INTO the workspace, wrote path-form manifests, and a lockfile pinned it
    // all at install time. The user has an entry of their own.
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    os.makeDir.all(box.ws / "home")
    os.copy(bundledLib, box.ws / "lib")
    os.write(
      box.ws / "PklProject",
      """amends "pkl:Project"
        |dependencies {
        |  ["fh-dashboard"] = import("./lib/PklProject")
        |  ["fh-home"] = import("./home/PklProject")
        |}
        |""".stripMargin
    )
    os.write(
      box.ws / "home" / "PklProject",
      """amends "pkl:Project"
        |package {
        |  name = "fh-home"
        |  baseUri = "package://fh.invalid/fh-home"
        |  version = "1.0.0"
        |  packageZipUrl = "https://fh.invalid/fh-home@\(version).zip"
        |}
        |dependencies {
        |  ["fh-dashboard"] = import("../lib/PklProject")
        |}
        |""".stripMargin
    )
    os.write(box.ws / "PklProject.deps.json", """{"stale": true}""")
    os.write(box.ws / "mine.pkl", "// the user's own entry\n")

    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)

    // lib/ left the workspace — as a rename, never a delete (backup rule).
    assert(!os.exists(box.ws / "lib"))
    val backups = os.list(box.ws).filter(_.last.startsWith("lib.backup."))
    assertEquals(backups.size, 1, clue = os.list(box.ws))

    // Both old-form manifests were recognized, backed up, and rewritten.
    assert(os.read(box.ws / "PklProject").contains(LibPackage.packageUri(libVersion)))
    assert(
      os.list(box.ws).exists(_.last.startsWith("PklProject.backup.")),
      clue = os.list(box.ws)
    )
    assert(
      os.list(box.ws / "home").exists(_.last.startsWith("PklProject.backup.")),
      clue = os.list(box.ws / "home")
    )

    // The user's entry is untouched; the stale lockfile is gone; no starter
    // seeding happened (the user HAS entries).
    assertEquals(os.read(box.ws / "mine.pkl"), "// the user's own entry\n")
    assert(!os.exists(box.ws / "PklProject.deps.json"))
    assert(!os.exists(box.ws / "dashboard.pkl"))
    assert(log.exists(_.contains("migrated")), clue = log)

    // And the migrated workspace evaluates — the whole point of the exercise.
    os.write.over(box.ws / "mine.pkl", os.read(seedDir / "dashboard.pkl"))
    val result = SourceEval.eval(box.ws, "mine.pkl")
    assert(result.isRight, clue = result)
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

  test("a pin bump in the user's manifest syncs the home manifest on boot") {
    // The user's PklProject is the ONE place the pin lives; the machine-owned
    // home manifest follows it (module identity: the dump and the entries must
    // reach the schema through the same cached artifact).
    val (box, _) = boot()
    os.write.over(
      box.ws / "PklProject",
      os.read(box.ws / "PklProject")
        .replace(LibPackage.packageUri(libVersion), LibPackage.packageUri("9.9.9"))
    )
    val bumped = os.read(box.ws / "PklProject")

    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)

    assertEquals(os.read(box.ws / "PklProject"), bumped)
    assert(
      os.read(box.ws / "home" / "PklProject")
        .contains(LibPackage.packageUri("9.9.9"))
    )
    // Machine-owned files never leave backups behind — that noise is reserved
    // for files the user might have authored.
    assert(!os.list(box.ws / "home").exists(_.last.contains(".backup.")))
    assert(log.nonEmpty, clue = log)
  }

  test("the interim single-file package form migrates, preserving its pin") {
    // The form the first package-form bootstrap generated: one PklProject
    // amending pkl:Project directly, cache dir and pin inline. A user may have
    // bumped its pin — migration to the amends-the-base shape keeps it.
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    os.makeDir.all(box.ws / "home")
    os.write(
      box.ws / "PklProject",
      s"""amends "pkl:Project"
         |evaluatorSettings { moduleCacheDir = "${box.cache}" }
         |dependencies {
         |  ["fh-dashboard"] { uri = "${LibPackage.packageUri("0.0.9")}" }
         |  ["fh-home"] = import("./home/PklProject")
         |}
         |""".stripMargin
    )
    os.write(box.ws / "mine.pkl", "// the user's own entry\n")

    val log = AddonBootstrap.run(box.ws, bundledLib, seedDir, box.cache)

    val consumer = os.read(box.ws / "PklProject")
    assert(consumer.contains("amends \".fh/base.pkl\""), clue = consumer)
    assert(consumer.contains(LibPackage.packageUri("0.0.9")), clue = consumer)
    assert(
      os.list(box.ws).exists(_.last.startsWith("PklProject.backup.")),
      clue = os.list(box.ws)
    )
    assert(
      os.read(box.ws / "home" / "PklProject")
        .contains(LibPackage.packageUri("0.0.9"))
    )
    assert(log.exists(_.contains("migrated")), clue = log)
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
