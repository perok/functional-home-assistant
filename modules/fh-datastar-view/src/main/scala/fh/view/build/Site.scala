package fh.view.build

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{Access, Dashboard}
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

  /** The entrypoint's filename — named after what it IS (the site), not after
    * one of the things it names. The only `*.pkl` in a workspace whose meaning
    * is fixed by its name; everything else is an ordinary module.
    *
    * A workspace that predates the entrypoint therefore keeps its files as
    * ordinary modules and gets a starter `site.pkl` seeded beside them: nothing
    * breaks, and serving one is a key.
    */
  val EntryFile: String = "site.pkl"

  /** The entrypoint's map of slug -> dashboard. Public because its presence is
    * what tells a pushed payload (`fh push site.pkl`) apart from a single
    * pushed dashboard.
    */
  val DashboardsKey: String = "dashboards"

  private val DefaultKey = "default"

  private val AccessKey = "access"

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
        // The site-wide access rule (issue #89), read once and folded into
        // every dashboard that did not name its own. A site whose `access` is
        // absent or unreadable gets the restrictive default rather than an
        // error: the entrypoint still names real dashboards, and refusing to
        // serve the whole instance over one malformed setting is worse than
        // demanding a login for it.
        val siteAccess: Access =
          json.asObject
            .flatMap(_(AccessKey))
            .flatMap(_.as[Access].toOption)
            .getOrElse(Access.default)

        ds.toList
          .sortBy(_._1)
          .traverse { case (slug, value) =>
            DashboardBuild
              .decode(value, sources, Some(slug))
              .map(_.withAccess(siteAccess))
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

  /** The diagnostic for an entrypoint that is really a single dashboard — it
    * amends `entry.pkl`, so it evaluates to a bare dashboard with no
    * `dashboards` key. Nothing here rewrites the file (it is the user's), so
    * the message IS the instructions, and it reaches them as the error page the
    * instance serves at `/`.
    */
  private def missingDashboards: FHError =
    FHError.badCondition(
      s"$EntryFile has no `$DashboardsKey`, so it names no dashboard. It reads " +
        "like a single dashboard rather than the site. It should start\n" +
        "  amends \"@fh-dashboard/site.pkl\"\n" +
        "and name each dashboard as a key — inline,\n" +
        "  dashboards { [\"home\"] { title = ...; card = ... } }\n" +
        "or in its own file (one that starts with `amends " +
        "\"@fh-dashboard/entry.pkl\"`):\n" +
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
