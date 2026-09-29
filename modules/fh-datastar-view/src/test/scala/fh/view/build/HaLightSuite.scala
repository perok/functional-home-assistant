package fh.view.build

import fh.view.testkit.PklWorkspace

/** Pins `lib/hass/light.pkl`'s derived mode sets and feature bits to HA's
  * values ([[HaLight]]): every value is read back out of the Pkl source.
  */
class HaLightSuite extends munit.FunSuite {

  private lazy val source: String =
    os.read(PklWorkspace.resourcesLib / "hass" / "light.pkl")

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
      .getOrElse(fail(s"no `const $name` in hass/light.pkl"))

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
    assert(HaLight.ColourModes.subsetOf(HassVocabulary.ColorModes))
    assert(HaLight.DimmableModes.subsetOf(HassVocabulary.ColorModes))
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
