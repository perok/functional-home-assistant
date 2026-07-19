package fh.view.build

import fh.view.testkit.HouseFixture
import io.circe.parser.parse

/** The content-versioned dump package (ADR 0010, "resolved by content-derived
  * versions"): the instance packages every dump it renders — immutably per
  * version — into the same cache the packages route serves and its own eval
  * resolves `@fh-home` from, and records the pin in `.fh/pins.json`. There is
  * no loose `home/dump.pkl`.
  */
class DumpPackageSuite extends munit.FunSuite {

  private val bundledLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"
  private val seedDir = os.pwd / "home-addon" / "dashboards-seed"
  private val bundled = LibPackage.build(bundledLib)
  private val libVersion = bundled.version

  private val dump = PklDump.render(HouseFixture.transformedDump)

  /** A bootstrapped package-form workspace (lib package seeded, static
    * base.pkl, NO pins.json yet) — the state right before the first
    * `prepareDumps`.
    */
  private def stage(): (os.Path, os.Path) = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val cache = root / "pkl-cache"
    val _ = AddonBootstrap.run(
      ws,
      bundled,
      seedDir,
      cache,
      loopbackUrl = "http://127.0.0.1:8080"
    )
    (ws, cache)
  }

  private def libMetaSha(cache: os.Path): String =
    LibPackage.sha256(
      os.read.bytes(
        LibPackage.cacheEntryDir(cache, libVersion) /
          s"fh-dashboard@$libVersion.json"
      )
    )

  test("seeding writes an immutable, dependency-carrying package + pin") {
    val (ws, cache) = stage()
    val log = DumpPackage.seedFromText(ws, dump, Some(bundled))
    assert(log.exists(_.contains("seeded dump package")), clue = log)
    assert(log.exists(_.contains("pinned @fh-home")), clue = log)

    val version = DumpPackage.build(dump, libVersion, libMetaSha(cache)).version
    assert(version.startsWith("1.0.0-g"), clue = version)

    // The pin file names this version.
    assertEquals(Pins.homeVersion(ws), Some(version))

    val entry = DumpPackage.cacheEntryDir(cache, version)
    val meta = parse(os.read(entry / s"fh-home@$version.json"))
      .fold(err => fail(s"metadata not JSON: $err"), identity)

    // The metadata carries the @fh-dashboard dependency (uri + the cached lib
    // metadata's sha) — what makes the dump's schema import resolve onto the
    // same artifact the entry's alias uses (module identity, spike-verified).
    val dep = meta.hcursor.downField("dependencies").downField("fh-dashboard")
    assertEquals(
      dep.get[String]("uri").toOption,
      Some(LibPackage.packageUri(libVersion))
    )
    assertEquals(
      dep.downField("checksums").get[String]("sha256").toOption,
      Some(libMetaSha(cache))
    )
    // And the zip checksum in the metadata matches the seeded zip.
    assertEquals(
      meta.hcursor
        .downField("packageZipChecksums")
        .get[String]("sha256")
        .toOption,
      Some(LibPackage.sha256(os.read.bytes(entry / s"fh-home@$version.zip")))
    )

    // Idempotent: same dump, same version, nothing to seed and no pin to move.
    assertEquals(DumpPackage.seedFromText(ws, dump), Nil)
  }

  test("a changed dump mints a NEW version; the old snapshot stays") {
    val (ws, cache) = stage()
    val _ = DumpPackage.seedFromText(ws, dump, Some(bundled))
    val before = os
      .list(cache / "package-2" / LibPackage.Host)
      .filter(_.last.startsWith("fh-home@"))

    val log = DumpPackage.seedFromText(ws, dump + "\n// a new device\n")

    val after = os
      .list(cache / "package-2" / LibPackage.Host)
      .filter(_.last.startsWith("fh-home@"))
    assertEquals(before.size, 1)
    assertEquals(after.size, 2, clue = after)
    assert(log.exists(_.contains("seeded dump package")), clue = log)
    // The original snapshot is untouched — a laptop pinned to it keeps
    // resolving.
    assert(before.toSet.subsetOf(after.toSet))
  }

  test("the version hashes the lib dependency too, not just the dump") {
    // A lib pin bump under an unchanged dump must mint a new version — the
    // metadata (which carries the dependency) is immutable per version, or a
    // laptop cache holding the old bytes is stranded (the spike-9 trap).
    val d = "// same dump\n"
    val a = DumpPackage.build(d, "0.1.0", "a" * 64)
    val b = DumpPackage.build(d, "0.2.0", "b" * 64)
    assertNotEquals(a.version, b.version)
    assertEquals(a.sha256, b.sha256) // same zip — only the identity moved
  }

  test("the discovery index names exactly what a pull would pin") {
    val (ws, cache) = stage()
    val _ = DumpPackage.seedFromText(ws, dump, Some(bundled))
    val index = DumpPackage
      .index(ws)
      .map(parse(_).fold(err => fail(s"index not JSON: $err"), identity))
      .getOrElse(fail("no index on a packaged workspace"))

    val libEntry = LibPackage.cacheEntryDir(cache, libVersion)
    assertEquals(
      index.hcursor.downField("fh-dashboard").get[String]("version").toOption,
      Some(libVersion)
    )
    assertEquals(
      index.hcursor.downField("fh-dashboard").get[String]("sha256").toOption,
      Some(
        LibPackage.sha256(
          os.read.bytes(libEntry / s"fh-dashboard@$libVersion.json")
        )
      )
    )
    val homeVersion = index.hcursor
      .downField("fh-home")
      .get[String]("version")
      .toOption
      .getOrElse(fail("no fh-home version"))
    // The index's sha is the seeded metadata's — the artifact a manifest
    // checksum pins.
    assertEquals(
      index.hcursor.downField("fh-home").get[String]("sha256").toOption,
      Some(
        LibPackage.sha256(
          os.read.bytes(
            DumpPackage.cacheEntryDir(cache, homeVersion) /
              s"fh-home@$homeVersion.json"
          )
        )
      )
    )
  }

  test("a workspace with no @fh-dashboard pin neither seeds nor indexes") {
    // The None guard: without a package pin (and its metadata in the cache) a
    // workspace cannot build the dependency-carrying dump package.
    val ws = os.temp.dir()
    os.write(
      ws / "PklProject",
      """amends "pkl:Project"
        |dependencies {
        |  ["fh-dashboard"] = import("./lib/PklProject")
        |}
        |""".stripMargin
    )
    assertEquals(DumpPackage.seedFromText(ws, "// dump\n"), Nil)
    assertEquals(DumpPackage.index(ws), None)
  }
}
