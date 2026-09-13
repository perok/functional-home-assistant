package fh.view.build

/** [[HassVocabulary]] mirrors a `typealias` union in each vendored `hass/`
  * module, and nothing but this suite makes the two agree. Re-syncing one
  * against a newer HA release without the other is the failure it exists for: a
  * class present only in the Pkl union types fine and never gets assigned, and
  * one present only in Scala gets assigned and then fails the dashboard's eval
  * — which is exactly the first-boot breakage the drop-to-comment behaviour is
  * meant to prevent.
  */
class HassVocabularySuite extends munit.FunSuite {

  /** The members of `typealias <name> = "a"|"b"|…`, read out of the vendored
    * module. Deliberately a dumb string scan and not a Pkl evaluation: the
    * point is to read what the SOURCE says, so a test that passes proves the
    * checked-in bytes agree rather than that two evaluators agree.
    */
  private def unionMembers(module: String, name: String): Set[String] = {
    val src = BundledLib
      .entries()
      .collectFirst {
        case (n, bytes) if n == module => new String(bytes, "UTF-8")
      }
      .getOrElse(fail(s"$module is not in the bundled lib"))
    val decl = s"typealias $name ="
    val start = src.indexOf(decl)
    assert(start >= 0, s"$module declares no $decl")
    // The union runs to the first blank line after it — every member sits on a
    // continuation line, and the declarations here are separated by a doc
    // comment or a blank.
    val body = src
      .substring(start + decl.length)
      .linesIterator
      // The first line is the remainder of the `typealias` line itself, which
      // is empty when the union wraps — so skip blanks BEFORE taking until one.
      .dropWhile(_.trim.isEmpty)
      .takeWhile(_.trim.nonEmpty)
      .mkString
    val members = """"([a-z0-9_]+)"""".r
      .findAllMatchIn(body)
      .map(_.group(1))
      .toList
    assert(members.nonEmpty, s"parsed no members out of $module's $name")
    members.toSet
  }

  test("SensorDeviceClass agrees with the vendored union") {
    assertEquals(
      HassVocabulary.SensorDeviceClasses,
      unionMembers("hass/sensor.pkl", "SensorDeviceClass")
    )
  }

  test("BinarySensorDeviceClass agrees with the vendored union") {
    assertEquals(
      HassVocabulary.BinarySensorDeviceClasses,
      unionMembers("hass/binary_sensor.pkl", "BinarySensorDeviceClass")
    )
  }

  test("ON_MEANS covers every binary sensor device class") {
    // Pkl types the Mapping's KEYS, so it already rejects a made-up one. What
    // it cannot see is a MISSING one — that reads back as null, and a card then
    // silently falls through to saying "on", which is true and useless. Adding
    // a device class to the union without its reading is the drift this
    // catches.
    val src = BundledLib
      .entries()
      .collectFirst {
        case (n, b) if n == "hass/binary_sensor.pkl" => new String(b, "UTF-8")
      }
      .getOrElse(fail("hass/binary_sensor.pkl is not in the bundled lib"))
    val keys = """\["([a-z0-9_]+)"\]""".r
      .findAllMatchIn(src)
      .map(_.group(1))
      .toSet
    assertEquals(keys, HassVocabulary.BinarySensorDeviceClasses)
  }
}
