package fh.view.build

/** HA's `light` constants that `lib/hass/light.pkl` vendors and the generator
  * never needs, written down again so `HaLightSuite` can pin them. The mode
  * union the generator filters by is [[HassVocabulary.ColorModes]].
  *
  * Source: `homeassistant/components/light/const.py`. Vendoring is safe because
  * `*EntityFeature` bits are append-only and never renumbered; 1 and 2 are the
  * removed `SUPPORT_BRIGHTNESS`/`SUPPORT_COLOR_TEMP`. Checked against 48 live
  * lights: every `supported_features` value decoded with no unknown bits.
  */
object HaLight {

  val ColourModes: Set[String] = Set("hs", "xy", "rgb", "rgbw", "rgbww")

  // `unknown` is HA's "not reported yet".
  val DimmableModes: Set[String] =
    Set("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white")

  val Effect: Int = 4
  val Flash: Int = 8
  val Transition: Int = 32

  def supports(supportedFeatures: Int, flag: Int): Boolean =
    (supportedFeatures & flag) != 0
}
