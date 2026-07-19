package fh.view.build

/** `.fh/pins.json` is machine data, born REAL-OR-NOTHING: it does not exist
  * until the first dump ([[Pins.writeHome]]) writes all three keys at once, and
  * from then on every real pin change keeps the previous file recoverable in a
  * DATED backup (`pins.json.backup.<stamp>`), pruned to the newest
  * [[Pins.MaxBackups]] so the dump refresh — which rewrites the pin constantly
  * — can't grow an unbounded trail. See [[Pins]].
  */
class PinsSuite extends munit.FunSuite {

  private val dash = LibPackage.packageUri("1.0.0")

  test("first writeHome mints real pins; later changes back up, no-ops don't") {
    val dir = os.temp.dir()

    // First write: no file yet, so nothing to back up — pins.json is born with
    // all three keys real (the passed dashboardUri seeds the lib pin).
    val first =
      Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri("1.0.0-gdeadbeef00"),
        "a" * 64
      )
    assert(first.nonEmpty, clue = first)
    assertEquals(Pins.backups(dir), Nil)
    assertEquals(Pins.dashboardVersion(dir), Some("1.0.0"))
    assertEquals(Pins.homeVersion(dir), Some("1.0.0-gdeadbeef00"))
    val born = os.read(Pins.path(dir))

    // A real change (the home pin moves) copies the prior file to a dated backup
    // verbatim and writes the new one. The dashboardUri is preserved (the passed
    // one is ignored once a file exists).
    val log =
      Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri("1.0.0-gcafebabe11"),
        "b" * 64
      )
    assert(log.nonEmpty, clue = log)
    assertEquals(Pins.backups(dir).map(os.read(_)), List(born))
    assert(os.read(Pins.path(dir)).contains("1.0.0-gcafebabe11"))

    // A no-op write neither rewrites the pin nor adds a backup.
    val log2 =
      Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri("1.0.0-gcafebabe11"),
        "b" * 64
      )
    assertEquals(log2, Nil)
    assertEquals(Pins.backups(dir).size, 1)

    // The next real change appends another dated backup (oldest first) holding
    // the file it just replaced.
    val current = os.read(Pins.path(dir))
    val _ =
      Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri("1.0.0-gfeedface22"),
        "c" * 64
      )
    val trail = Pins.backups(dir)
    assertEquals(trail.size, 2)
    assertEquals(os.read(trail.last), current)
  }

  test("the dated backup trail is pruned to the newest MaxBackups") {
    val dir = os.temp.dir()

    // Write 0 mints the file (no backup); writes 1..MaxBackups+5 each roll the
    // previous file into a dated backup, and pruning keeps only the newest
    // MaxBackups.
    (0 to Pins.MaxBackups + 5).foreach { i =>
      val _ = Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri(f"1.0.0-g${i}%010d"),
        (i % 10).toString * 64
      )
    }

    val trail = Pins.backups(dir)
    assertEquals(trail.size, Pins.MaxBackups)
    // Each backup holds the file REPLACED by that change: the backup from change
    // #k carries change #(k-1)'s pin. 55 changes make 55 backups (the first
    // carries write #0); pruning to the newest 50 drops the first 5, so the
    // retained window runs from write #5's pin to write #(MaxBackups+4)'s.
    assert(os.read(trail.head).contains(f"1.0.0-g${5}%010d"), clue = trail.head)
    assert(os.read(trail.last).contains(f"1.0.0-g${Pins.MaxBackups + 4}%010d"))
  }

  test("seedBootstrap: no-op without pins, refreshes dashboardUri with them") {
    val dir = os.temp.dir()

    // No pins.json yet: bootstrap writes nothing (real-or-nothing — a fresh
    // workspace has no dump to pin, and a partial pins.json can't be loaded).
    Pins.seedBootstrap(dir, LibPackage.packageUri("1.0.0"))
    assert(!os.exists(Pins.path(dir)), clue = os.list(dir))

    // Once the first dump has minted pins.json, a later bootstrap refreshes the
    // lib pin to the bundled version and preserves the home fields.
    val _ = Pins.writeHome(
      dir,
      LibPackage.packageUri("1.0.0"),
      DumpPackage.packageUri("1.0.0-gaaaaaaaaaa"),
      "a" * 64
    )
    Pins.seedBootstrap(dir, LibPackage.packageUri("2.0.0"))
    assertEquals(Pins.dashboardVersion(dir), Some("2.0.0"))
    assertEquals(Pins.homeVersion(dir), Some("1.0.0-gaaaaaaaaaa"))
  }
}
