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

  private val bundled = LibPackage.build(bundledLib)
  private val libVersion = bundled.version

  private case class Box(ws: os.Path, cache: os.Path)

  private def boot(): (Box, List[String]) = {
    val root = os.temp.dir()
    val box = Box(root / "fh-dashboards", root / "pkl-cache")
    // `bootstrapInto` = the instance's own boot, plus the `machine.json` a
    // reader writes for itself. The instance writes none (that is the point —
    // one workspace, many machines), and a test cannot set an env var per case.
    val log = PklWorkspace.bootstrapInto(box.ws, bundled, box.cache)
    (box, log)
  }

  test("first boot: seeds a lib-free workspace that evaluates offline") {
    val (box, _) = boot()

    // The user's dir holds only user files: entries + the manifests. No lib/ —
    // that was the littering the package form exists to remove.
    assert(!os.exists(box.ws / "lib"), clue = os.list(box.ws))
    assert(os.exists(box.ws / "site.pkl"))

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
    // base.pkl is machine-AGNOSTIC: no path, no URL — it READS the per-reader
    // values from this process's environment first, `machine.json` second, and
    // the pins from their own sibling.
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
    val result = SourceEval.eval(box.ws, "site.pkl")
    assert(result.isRight, clue = result)
  }

  test("the instance imposes none of its own paths on a shared workspace") {
    // One dashboards directory is meant to be usable from an HA device, the
    // author's laptop and a dev container at once. The add-on used to write its
    // cache path and loopback URL into `.fh/machine.json` at every start, so
    // whichever machine booted last decided for the others — and a container
    // handed a path outside it failed EVERY dashboard with pkl's own
    // "AccessDeniedException". So: it writes no per-machine file at all, and a
    // reader's own file survives a boot byte-for-byte.
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val laptop = AddonBootstrap.machineFileJson(
      cacheDir = Some(os.root / "home" / "someone" / ".pkl" / "cache"),
      instanceUrl = Some("http://ha.local:8123")
    )
    os.write(ws / ".fh" / "machine.json", laptop, createFolders = true)

    val _ = AddonBootstrap.run(ws, bundled, root / "pkl-cache")

    assertEquals(os.read(ws / ".fh" / "machine.json"), laptop)
    // …and on a workspace that has none, the boot does not invent one.
    val fresh = os.temp.dir() / "fh-dashboards"
    val _ = AddonBootstrap.run(fresh, bundled, root / "pkl-cache")
    assert(
      !os.exists(fresh / ".fh" / "machine.json"),
      clue = os.list(fresh / ".fh")
    )
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

    val bootLog =
      AddonBootstrap.run(box.ws, bundled, box.cache)

    // Nothing user-authored is touched: lib/, the consumer + their module all
    // stay, and no backup is made. The stale lockfile IS removed (generated
    // artifact). A starter entrypoint IS seeded, because this workspace has
    // none — a loose `*.pkl` is an ordinary module now, not a dashboard (ADR
    // 0021), so it cannot stand in for one.
    assert(os.exists(box.ws / "lib"))
    assert(!os.list(box.ws).exists(_.last.contains(".backup.")))
    assertEquals(os.read(box.ws / "PklProject"), oldConsumer)
    assertEquals(os.read(box.ws / "mine.pkl"), "// the user's own entry\n")
    assert(!os.exists(box.ws / "PklProject.deps.json"))
    assertEquals(
      os.read(box.ws / Site.EntryFile),
      AddonBootstrap.starterSite
    )
    // ...and the boot SAYS what became of the user's old entries, naming them.
    // This is the whole upgrade path (ADR 0021): nothing is moved or rewritten,
    // their old files are modules now, and serving one is a key away — which
    // they would otherwise have to infer from an instance that looks empty.
    assert(
      bootLog.exists(l =>
        l.contains("mine.pkl") && l.contains("ordinary modules")
      ),
      clue = bootLog
    )
    // Said once, on the boot that seeded the entrypoint — not every start.
    val second = AddonBootstrap.run(box.ws, bundled, box.cache)
    assert(!second.exists(_.contains("mine.pkl")), clue = second)

    // Recovery: deleting the machine-era consumer opts into a fresh, package-form
    // re-seed — then it evaluates. `bootstrapInto` because evaluating needs the
    // reader's own cache dir, which this workspace (built by hand above, never
    // through `boot()`) has not been given.
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

    // A regular file where the cache dir has to be. Unusable on every platform,
    // and unlike a chmod it still holds when the suite runs as root.
    val blocker = os.temp.dir() / "not-a-dir"
    os.write(blocker, "")
    val unusable = blocker / "pkl-cache"

    val e = intercept[RuntimeException](
      AddonBootstrap.run(box.ws, bundled, unusable)
    )
    assert(e.getMessage.contains(unusable.toString), clue = e.getMessage)
    assert(e.getMessage.contains("FH_PKL_CACHE_DIR"), clue = e.getMessage)

    // And `machine.json` still names the cache the last SUCCESSFUL boot used.
    // Seeding BEFORE that write is what made this go wrong quietly: the seed
    // threw, a path from an earlier run survived in the workspace, and the
    // failure resurfaced as pkl's own I/O error once per dashboard.
    assertEquals(os.read(box.ws / ".fh" / "machine.json"), before)
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
      AddonBootstrap.run(box.ws, bundledV1, box.cache)
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
