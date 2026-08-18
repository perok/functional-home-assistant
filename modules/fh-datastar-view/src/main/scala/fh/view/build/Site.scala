package fh.view.build

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.Dashboard
import io.circe.Json

/** The workspace's ONE entrypoint: a slug -> dashboard map plus the settings
  * that apply across all of them (ADR 0021). A dashboard is data here, not a
  * file, which is what lets an author generate them and what makes membership
  * an ordinary edit the reload path re-reads.
  *
  * Decoding is deliberately PER SLUG. One Pkl evaluation produces the whole
  * site, but each dashboard is decoded and validated on its own, so an unknown
  * card in one of them is that slug's error page and not the whole instance's
  * (ADR 0018).
  */
object Site {

  /** The entrypoint's filename. The only `*.pkl` in a workspace with a meaning
    * fixed by its name — everything else is an ordinary module.
    */
  val EntryFile: String = "dashboard.pkl"

  /** The entrypoint's map of slug -> dashboard. Public because its presence is
    * what tells a pushed payload (`fh push dashboard.pkl`) apart from a single
    * pushed dashboard.
    */
  val DashboardsKey: String = "dashboards"

  private val DefaultKey = "default"

  /** One evaluated site: every slug it names — each either a proven dashboard
    * or the message its own build failed with — and the slug it wants served at
    * `/`. Slug-sorted, so logs and the default-slug fallback are stable.
    */
  case class Decoded(
      dashboards: List[(String, Either[String, Dashboard.Validated])],
      default: Option[String]
  ) {
    def slugs: List[String] = dashboards.map(_._1)
  }

  /** Decode an evaluated entrypoint. Only the SITE shape is terminal here (a
    * missing `dashboards` is not something a caller recovers from — see
    * [[missingDashboards]]); a dashboard that fails to decode or validate is
    * carried as a `Left` rather than raised, because the other slugs still
    * serve.
    *
    * `sources` (the entrypoint + its transitive imports) only points invalid
    * transforms back at their source line.
    */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty
  ): IO[Decoded] =
    json.asObject.flatMap(_(DashboardsKey)).flatMap(_.asObject) match {
      case None     => missingDashboards.raiseError[IO, Decoded]
      case Some(ds) =>
        ds.toList
          .sortBy(_._1)
          .traverse { case (slug, value) =>
            DashboardBuild
              .decode(value, sources)
              .map(_.withSlug(slug))
              .attempt
              .map(r => slug -> r.leftMap(messageOf))
          }
          .map(
            Decoded(
              _,
              json.asObject
                .flatMap(_(DefaultKey))
                .flatMap(_.asString)
                .filter(_.nonEmpty)
            )
          )
    }

  /** The diagnostic a pre-ADR-0021 workspace gets: its `dashboard.pkl` amends
    * `entry.pkl` and so evaluates to a bare dashboard with no `dashboards` key.
    * There is no automatic migration (the file is the user's), so this message
    * IS the migration instructions — it reaches them as the error page the
    * instance serves at `/`.
    */
  private def missingDashboards: FHError =
    FHError.badCondition(
      s"$EntryFile has no `$DashboardsKey`, so it names no dashboard. It looks " +
        "like a dashboard from before the one-entrypoint change. Wrap it: move " +
        s"its body into `$EntryFile` as\n" +
        "  amends \"@fh-dashboard/site.pkl\"\n" +
        "  dashboards { [\"home\"] { title = ...; card = ... } }\n" +
        "or keep the dashboard in its own file (starting with `amends " +
        "\"@fh-dashboard/entry.pkl\"`) and point a key at it:\n" +
        "  dashboards { [\"home\"] = import(\"my-dashboard.pkl\") }"
    )

  /** A failure's message for a `Left` slug and for logs; exceptions can throw a
    * null message, which neither an error page nor a log line wants.
    */
  def messageOf(err: Throwable): String =
    Option(err.getMessage)
      .filter(_.nonEmpty)
      .getOrElse(err.getClass.getSimpleName)
}
