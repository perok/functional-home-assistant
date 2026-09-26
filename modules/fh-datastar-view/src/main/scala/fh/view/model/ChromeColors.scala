package fh.view.model

import cats.syntax.apply.*

/** The browser's own chrome colour (URL bar, PWA status bar), not
  * [[Theme.chrome]]; see `docs/terminology.md`. The dashboard's background, so
  * the bar reads as the page's top. Consumers take the pair: the metas and the
  * manifest reach different surfaces.
  */
final case class ChromeColors(light: String, dark: String) {

  /** For a surface that cannot ask for a scheme. Dark, since a light value is a
    * white stripe on a dark phone. Becomes `light` once Chrome ships
    * `color_scheme_dark` (crbug.com/383165202), along with the manifest test.
    * Costs Safari, which already has it, a dark bar in light mode.
    */
  def base: String = dark
}

object ChromeColors {

  private val Token = "primary-background-color"

  /** A token under one scheme paints both: otherwise the browser's white
    * fallback looks like a theme with no opinion.
    */
  def from(theme: Theme): Option[ChromeColors] = {
    val light = theme.tokens.get(Token)
    val dark = theme.tokensDark.get(Token)
    (light.orElse(dark), dark.orElse(light)).mapN(ChromeColors.apply)
  }
}
