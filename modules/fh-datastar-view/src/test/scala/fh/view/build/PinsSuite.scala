package fh.view.build

/** `.fh/pins.json` is machine data, but a real pin change keeps the previous
  * file recoverable in a DATED backup (`pins.json.backup.<stamp>`), pruned to
  * the newest [[Pins.MaxBackups]] so the dump refresh — which rewrites the pin
  * constantly — can't grow an unbounded trail. See [[Pins]].
  */
class PinsSuite extends munit.FunSuite {

  test("a real pin change makes a dated backup; no-ops don't") {
    val dir = os.temp.dir()

    // First write: no existing file, so there is nothing to back up.
    Pins.seedBootstrap(dir, LibPackage.packageUri("1.0.0"))
    assertEquals(Pins.backups(dir), Nil)
    val placeholder = os.read(Pins.path(dir))

    // A real change (the home pin moves off the placeholder) copies the prior
    // file to a dated backup verbatim and writes the new one.
    val log =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gdeadbeef00"), "a" * 64)
    assert(log.nonEmpty, clue = log)
    assertEquals(Pins.backups(dir).map(os.read(_)), List(placeholder))
    assert(os.read(Pins.path(dir)).contains("1.0.0-gdeadbeef00"))

    // A no-op write neither rewrites the pin nor adds a backup.
    val log2 =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gdeadbeef00"), "a" * 64)
    assertEquals(log2, Nil)
    assertEquals(Pins.backups(dir).size, 1)

    // The next real change appends another dated backup (oldest first) holding
    // the file it just replaced.
    val current = os.read(Pins.path(dir))
    val _ =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gcafebabe11"), "b" * 64)
    val trail = Pins.backups(dir)
    assertEquals(trail.size, 2)
    assertEquals(os.read(trail.last), current)
  }

  test("the dated backup trail is pruned to the newest MaxBackups") {
    val dir = os.temp.dir()
    Pins.seedBootstrap(dir, LibPackage.packageUri("1.0.0"))

    // MaxBackups + 5 real changes: each rolls the previous file into a dated
    // backup, and pruning keeps only the newest MaxBackups.
    (1 to Pins.MaxBackups + 5).foreach { i =>
      val _ = Pins.writeHome(
        dir,
        DumpPackage.packageUri(f"1.0.0-g${i}%010d"),
        (i % 10).toString * 64
      )
    }

    val trail = Pins.backups(dir)
    assertEquals(trail.size, Pins.MaxBackups)
    // Each backup holds the file REPLACED by that change: backup #k carries the
    // pin from change #(k-1). 55 changes make 55 backups (the first carries the
    // seed); pruning to the newest 50 drops the first 5, so the retained window
    // runs from change #5's pin to change #54's.
    assert(os.read(trail.head).contains(f"1.0.0-g${5}%010d"), clue = trail.head)
    assert(os.read(trail.last).contains(f"1.0.0-g${Pins.MaxBackups + 4}%010d"))
  }
}
