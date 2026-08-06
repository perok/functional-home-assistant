package fh.view.build

import fh.view.testkit.PklWorkspace

/** [[HaLight]] and `lib/hass-light.pkl` are two copies of HA's light constants —
  * the generator's and the author's. This suite is what stops them drifting:
  * every value is read back out of the Pkl source and compared.
  */
class HaLightSuite extends munit.FunSuite {

  private lazy val source: String =
    os.read(PklWorkspace.resourcesLib / "hass-light.pkl")

  /** The string literals of a `typealias X = "a"|"b"|...` declaration. */
  private def union(name: String): List[String] =
    """"([a-z_]+)"""".r
      .findAllMatchIn(
        source.linesIterator
          .dropWhile(!_.startsWith(s"typealias $name"))
          .takeWhile(!_.trim.endsWith("\"white\"") || true)
          .take(3)
          .mkString(" ")
      )
      .map(_.group(1))
      .toList

  /** The `List(...)` members of a `const NAME: List<ColorMode> = List(...)`. */
  private def constList(name: String): Set[String] =
    source.linesIterator
      .dropWhile(!_.startsWith(s"const $name"))
      .take(3)
      .mkString(" ")
      .split("List\\(")
      .lift(1)
      .map(s => """"([a-z_]+)"""".r.findAllMatchIn(s).map(_.group(1)).toSet)
      .getOrElse(Set.empty)

  private def constInt(name: String): Int =
    s"""const $name: Int = (\\d+)""".r
      .findFirstMatchIn(source)
      .map(_.group(1).toInt)
      .getOrElse(fail(s"no `const $name` in hass-light.pkl"))

  test("ColorMode union matches HaLight.ColorModes") {
    assertEquals(union("ColorMode"), HaLight.ColorModes)
  }

  test("COLOUR_MODES and DIMMABLE_MODES match") {
    assertEquals(constList("COLOUR_MODES"), HaLight.ColourModes)
    assertEquals(constList("DIMMABLE_MODES"), HaLight.DimmableModes)
  }

  test("LightEntityFeature bits match") {
    assertEquals(constInt("EFFECT"), HaLight.Effect)
    assertEquals(constInt("FLASH"), HaLight.Flash)
    assertEquals(constInt("TRANSITION"), HaLight.Transition)
  }

  test("the derived mode sets are subsets of the enum") {
    assert(HaLight.ColourModes.subsetOf(HaLight.ColorModes.toSet))
    assert(HaLight.DimmableModes.subsetOf(HaLight.ColorModes.toSet))
  }

  test("supports decodes the values observed on a live instance") {
    // 0, 4, 40, 44 were every value seen across 48 lights; each must decode
    // with no bits left over.
    val all = HaLight.Effect | HaLight.Flash | HaLight.Transition
    List(0, 4, 40, 44).foreach(v => assertEquals(v & ~all, 0, clue = v))
    assert(HaLight.supports(44, HaLight.Effect))
    assert(!HaLight.supports(40, HaLight.Effect))
    assert(HaLight.supports(40, HaLight.Transition))
  }
}
