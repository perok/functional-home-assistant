package fh.view.history

import cats.effect.IO
import fh.view.query.{Fragment, PreparedQuery, QueryIdentity, QueryProvider}

import java.time.Instant

/** A drawn chart is keyed by its series AND its style: the same 24-hour series
  * at two widths is two pictures, and serving one for the other is a squashed
  * axis rather than a visible error.
  */
final case class ChartKey(series: SeriesKey, style: ChartStyle) {
  def expiresAt: Instant = series.expiresAt
}

/** How a series becomes bytes.
  *
  * Narrow on purpose: the store needs exactly this, and taking the whole
  * [[ChartRenderer]] would put a JavaScript engine in every test that only
  * wants to know what the cache does — the same reason [[SeriesProvider]] takes
  * a [[SeriesSource]] rather than the HA client.
  */
type ChartDraw = (Series, ChartStyle) => IO[String]

/** The `history` query provider: one entity's readings over one window, drawn.
  *
  * Two caches, and they are its own business rather than the pipeline's. They
  * expire together but are shared differently — one fetch feeds every style,
  * one drawing feeds every viewer — and the split earns itself in exactly one
  * case, which has a test: a dashboard tile and a more-info popup showing the
  * same sensor at different sizes cost one fetch and two drawings. With a
  * single style the two are 1:1 and the lower one is invisible, which was
  * measured by deleting it: of 854 tests only its own noticed.
  */
final class HistoryProvider private (
    series: SeriesStore,
    draw: ChartDraw,
    cache: BucketCache[ChartKey, String]
) extends QueryProvider {

  def name: String = HistoryProvider.Name

  /** The params a chart slot carries. `entity` and `window` are the question;
    * the rest is how it is drawn, and every one of them is part of the cache
    * key through [[ChartStyle]].
    */
  def parse(
      params: Map[String, String]
  ): Either[String, PreparedQuery] =
    for {
      entity <- params
        .get("entity")
        .toRight("query 'history' needs an 'entity' parameter")
      name <- params
        .get("window")
        .toRight("query 'history' needs a 'window' parameter")
      window <- Window
        .byName(name)
        .toRight(
          s"query 'history' has unknown window '$name' — one of " +
            Window.values.map(_.name).mkString(", ")
        )
      width <- intParam(params, "width", ChartStyle().width)
      height <- intParam(params, "height", ChartStyle().height)
    } yield {
      val style = ChartStyle(
        width = width,
        height = height,
        unit = params.get("unit")
      )
      new PreparedQuery {
        def resolve(identity: QueryIdentity, asOf: Instant): IO[Fragment] =
          svg(identity, entity, window, style, asOf)
            .map(Fragment(window.bucketOf(asOf).getEpochSecond, _))
      }
    }

  private def intParam(
      params: Map[String, String],
      key: String,
      fallback: Int
  ): Either[String, Int] =
    params.get(key) match {
      case None    => Right(fallback)
      case Some(v) =>
        v.toIntOption.toRight(s"query 'history' has non-numeric $key '$v'")
    }

  def svg(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      style: ChartStyle,
      asOf: Instant
  ): IO[String] = {
    val key = ChartKey(
      SeriesKey(identity, entityId, window, window.bucketOf(asOf)),
      style
    )
    cache.get(key, asOf)(
      series
        .get(identity, entityId, window, asOf)
        .flatMap(draw(_, style))
    )
  }

  /** What is currently drawn, for tests and diagnostics. */
  def keys: IO[Set[ChartKey]] = cache.keys
}

object HistoryProvider {

  val Name: String = "history"

  def create(series: SeriesStore, draw: ChartDraw): IO[HistoryProvider] =
    BucketCache
      .create[ChartKey, String](_.expiresAt)
      .map(new HistoryProvider(series, draw, _))

  def fromRenderer(
      series: SeriesStore,
      renderer: ChartRenderer
  ): IO[HistoryProvider] = create(series, renderer.render)
}
