package fh.view.build

/** [[HassVocabulary]] mirrors a `typealias` union in each vendored `hass/`
  * module, and only this suite makes them agree. A class only in Pkl is never
  * assigned; one only in Scala is assigned and fails the dashboard's eval, the
  * first-boot breakage the drop-to-comment behaviour exists to prevent.
  */
class HassVocabularySuite extends munit.FunSuite {

  /** A string scan, not a Pkl evaluation, so a pass proves the checked-in bytes
    * agree.
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

  test("ColorMode agrees with the vendored union") {
    assertEquals(
      HassVocabulary.ColorModes,
      unionMembers("hass/light.pkl", "ColorMode")
    )
  }

  test("SensorStateClass agrees with the vendored union") {
    assertEquals(
      HassVocabulary.SensorStateClasses,
      unionMembers("hass/sensor.pkl", "SensorStateClass")
    )
  }

  /** The tripwire for the next such field: typing one by a vendored union
    * without filtering it in `PklDump` is the breakage ADR 0013 describes, and
    * nothing fails until a newer HA reports a value the union lacks.
    */
  test("every schema field typed by a vendored union has a vocabulary") {
    val hass = BundledLib
      .entries()
      .collectFirst { case (n, b) if n == "hass.pkl" => new String(b, "UTF-8") }
      .getOrElse(fail("hass.pkl is not in the bundled lib"))
    val vendored = """(?m)^typealias (\w+) = hass\w+\.\w+""".r
      .findAllMatchIn(hass)
      .map(_.group(1))
      .toSet
    val typedBy =
      """(?m)^\s*(?:hidden\s+)?\w+:\s*(?:Listing<)?(\w+)>?\??\s*=""".r
        .findAllMatchIn(hass)
        .map(_.group(1))
        .filter(vendored)
        .toSet
    assert(vendored.nonEmpty && typedBy.nonEmpty, clue = (vendored, typedBy))
    assertEquals(
      typedBy,
      Set(
        "ColorMode",
        "SensorDeviceClass",
        "SensorStateClass",
        "BinarySensorDeviceClass"
      ),
      clue = "filter the new field through HassVocabulary in PklDump"
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
