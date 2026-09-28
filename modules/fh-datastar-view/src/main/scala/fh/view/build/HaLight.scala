package fh.view.build

/** HA's `light` domain constants, a Scala copy of `lib/hass/light.pkl` that
  * `HaLightSuite` holds the Pkl one to. Nothing in main reads it.
  *
  * Source: `homeassistant/components/light/const.py`. Vendoring is safe because
  * `*EntityFeature` bits are append-only and never renumbered; 1 and 2 are the
  * removed `SUPPORT_BRIGHTNESS`/`SUPPORT_COLOR_TEMP`. Checked against 48 live
  * lights: every `supported_features` value decoded with no unknown bits.
  */
object HaLight {

  val ColorModes: List[String] = List(
    "unknown",
    "onoff",
    "brightness",
    "color_temp",
    "hs",
    "xy",
    "rgb",
    "rgbw",
    "rgbww",
    "white"
  )

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
