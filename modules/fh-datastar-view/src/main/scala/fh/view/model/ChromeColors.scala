package fh.view.model

import cats.syntax.apply.*

/** The colour a phone paints its own chrome with — the browser's URL bar, and
  * an installed PWA's status bar — under each scheme. Browser chrome, not
  * [[Theme.chrome]], which is the dashboard's frame template; see
  * `docs/terminology.md`.
  *
  * It is the dashboard's BACKGROUND, not its accent: the bar sits directly
  * above the page, and any other colour reads as a stripe of unrelated UI
  * rather than as the top of the dashboard.
  *
  * Both consumers take the PAIR rather than picking one here, because the two
  * channels reach different surfaces and neither is a fallback for the other:
  * `Renderer.themeColorTags` emits a scheme-qualified `<meta>` each, and
  * `PwaAssets.manifest` fills the manifest's themeable members. Collapsing to
  * one value used to happen at the manifest's edge, which is why the manifest
  * could only ever be half right.
  */
final case class ChromeColors(light: String, dark: String) {

  /** What a surface paints when it cannot ask for a scheme: the manifest's bare
    * `theme_color`/`background_color`, read once at install time for a whole
    * origin, and — until `color_scheme_dark` is widely implemented — what every
    * browser that does not know that member uses in BOTH schemes.
    *
    * Dark, deliberately. An installed app's status bar and splash are chrome
    * rather than page: dark reads as a bar under either scheme, where the light
    * value reads as a white stripe on a dark phone — which is the bug this
    * whole path exists to fix. Once Chrome ships the override
    * (crbug.com/383165202), this becomes `light` and the member below starts
    * doing the work instead — along with the manifest test that pins it.
    *
    * It is not free while it holds: WebKit shipped the override in May 2026, so
    * a Safari-installed app would already read a light/dark pair correctly and
    * gets a dark bar in light mode because of this pin. The pin buys Chrome —
    * nearly every client here — at Safari's expense, rather than waiting out an
    * implementation nobody has.
    */
  def base: String = dark
}

object ChromeColors {

  /** The HA-named token the chrome colour is read from. */
  private val Token = "primary-background-color"

  /** A theme's chrome colours, or `None` when it names the token under neither
    * scheme (a theme with no opinion: the committed manifest and no metas at
    * all).
    *
    * A theme that defines the token under only ONE scheme paints BOTH with it —
    * hence the crossed `orElse`s. Dropping the other is worse than reusing the
    * colour: the browser then falls back to its own chrome — white, on the
    * scheme the theme said nothing about — which is indistinguishable from a
    * theme that had no opinion.
    */
  def from(theme: Theme): Option[ChromeColors] = {
    val light = theme.tokens.get(Token)
    val dark = theme.tokensDark.get(Token)
    (light.orElse(dark), dark.orElse(light)).mapN(ChromeColors.apply)
  }
}
