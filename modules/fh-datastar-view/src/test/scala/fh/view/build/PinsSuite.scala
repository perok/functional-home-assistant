package fh.view.build

/** `.fh/pins.json` is machine data, but a real pin change keeps the previous
  * file recoverable in a SINGLE rolling `.backup` (not a dated, accumulating
  * trail — the dump refresh rewrites the pin constantly). See [[Pins]].
  */
class PinsSuite extends munit.FunSuite {

  test("a real pin change rolls the previous pins.json into .backup; no-ops don't") {
    val dir = os.temp.dir()

    // First write: no existing file, so there is nothing to back up.
    Pins.seedBootstrap(dir, LibPackage.packageUri("1.0.0"))
    assert(!os.exists(Pins.backupPath(dir)))
    val placeholder = os.read(Pins.path(dir))

    // A real change (the home pin moves off the placeholder) rolls the prior file
    // in verbatim and writes the new one.
    val log =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gdeadbeef00"), "a" * 64)
    assert(log.nonEmpty, clue = log)
    assertEquals(os.read(Pins.backupPath(dir)), placeholder)
    assert(os.read(Pins.path(dir)).contains("1.0.0-gdeadbeef00"))

    // A no-op write neither rewrites the pin nor churns the backup.
    val backupBefore = os.read(Pins.backupPath(dir))
    val log2 =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gdeadbeef00"), "a" * 64)
    assertEquals(log2, Nil)
    assertEquals(os.read(Pins.backupPath(dir)), backupBefore)

    // The next real change overwrites the single slot with the CURRENT file.
    val current = os.read(Pins.path(dir))
    val _ =
      Pins.writeHome(dir, DumpPackage.packageUri("1.0.0-gcafebabe11"), "b" * 64)
    assertEquals(os.read(Pins.backupPath(dir)), current)
  }
}
