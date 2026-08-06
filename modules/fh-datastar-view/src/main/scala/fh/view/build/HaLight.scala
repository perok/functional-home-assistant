package fh.view.build

/** Home Assistant's `light` domain constants, vendored — the generator's copy of
  * what `lib/hass-light.pkl` exposes to dashboard authors.
  *
  * Two copies exist because the two sides need them at different times: this one
  * derives the capability predicates during codegen, the Pkl one lets an author
  * name a colour mode or test a feature bit in their own expressions.
  * `HaLightSuite` asserts they agree, so the pair cannot drift apart silently.
  *
  * Source: `homeassistant/components/light/const.py`
  * (https://developers.home-assistant.io/docs/core/entity/light).
  *
  * Vendoring is safe because HA's `*EntityFeature` IntFlags are APPEND-ONLY: a
  * new feature takes a new bit, a removed one leaves its bit vacant, and values
  * are never renumbered — they are persisted in entity state attributes and read
  * by the frontend. The vacant 1 and 2 are the removed `SUPPORT_BRIGHTNESS` and
  * `SUPPORT_COLOR_TEMP`, dropped when colour modes replaced them.
  *
  * Validated against a live instance (48 lights): the `Effect` bit agreed with
  * `effect_list` presence 48/48, and every observed `supported_features` value
  * (0, 4, 40, 44) decoded with no unaccounted bits.
  */
object HaLight {

  /** `ColorMode` — every value HA may report in `supported_color_modes`. */
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

  /** Modes meaning the light can show an actual COLOUR, as opposed to on/off,
    * plain dimming, or tunable white.
    */
  val ColourModes: Set[String] = Set("hs", "xy", "rgb", "rgbw", "rgbww")

  /** Modes implying settable brightness — everything except a light that can
    * only be switched. `unknown` is HA's "not reported yet" placeholder and is
    * deliberately excluded.
    */
  val DimmableModes: Set[String] =
    Set("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white")

  // LightEntityFeature bits. 1 and 2 are vacant (see the class doc).
  val Effect: Int = 4
  val Flash: Int = 8
  val Transition: Int = 32

  def supports(supportedFeatures: Int, flag: Int): Boolean =
    (supportedFeatures & flag) != 0
}
