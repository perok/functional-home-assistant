package fh.view.history

import api.homeassistant.ws.domain.StatisticsPeriod
import io.github.iltotore.iron.*

/** More-info's presets, as `window.pkl` writes them. */
object Windows {
  val hour: Window = Window.Raw(3600L)
  val day: Window =
    Window.Statistics(86400L, StatisticsPeriod.FiveMinute, fallback = true)
  val week: Window =
    Window.Statistics(604800L, StatisticsPeriod.Hour, fallback = true)
  val month: Window =
    Window.Statistics(2592000L, StatisticsPeriod.Hour, fallback = true)

  val presets: Map[String, Window] =
    Map("1h" -> hour, "24h" -> day, "7d" -> week, "30d" -> month)
}
