package fh.view.build

/** `.fh/pins.json` is born real-or-nothing: absent until the first dump
  * ([[Pins.writeHome]]) writes all three keys, then every real change keeps the
  * previous file in a dated backup, pruned to [[Pins.MaxBackups]] since the
  * dump refresh rewrites the pin constantly.
  */
class PinsSuite extends munit.FunSuite {

  private val dash = LibPackage.packageUri("1.0.0")

  test("first writeHome mints real pins; later changes back up, no-ops don't") {
    val dir = os.temp.dir()

    // The passed dashboardUri seeds the lib pin.
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

    // The passed dashboardUri is ignored once a file exists.
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

    val log2 =
      Pins.writeHome(
        dir,
        dash,
        DumpPackage.packageUri("1.0.0-gcafebabe11"),
        "b" * 64
      )
    assertEquals(log2, Nil)
    assertEquals(Pins.backups(dir).size, 1)

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
    // A backup holds the file its change replaced. 55 changes make 55 backups;
    // pruning to the newest 50 keeps write #5's pin through #(MaxBackups+4)'s.
    assert(os.read(trail.head).contains(f"1.0.0-g${5}%010d"), clue = trail.head)
    assert(os.read(trail.last).contains(f"1.0.0-g${Pins.MaxBackups + 4}%010d"))
  }

  test("seedBootstrap: no-op without pins, refreshes dashboardUri with them") {
    val dir = os.temp.dir()

    // A fresh workspace has no dump to pin, and a partial pins.json cannot
    // load.
    Pins.seedBootstrap(dir, LibPackage.packageUri("1.0.0"))
    assert(!os.exists(Pins.path(dir)), clue = os.list(dir))

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
