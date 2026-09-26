package fh.view.build

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{Access, Dashboard}
import io.circe.Json

/** The one entrypoint: slug -> dashboard plus site-wide settings (ADR 0021).
  * One evaluation, but each dashboard decodes on its own, so a bad card is that
  * slug's error page rather than the instance's (ADR 0018).
  */
object Site {

  /** The only `*.pkl` whose meaning its name fixes. */
  val EntryFile: String = "site.pkl"

  /** Its presence tells `fh push site.pkl` apart from a single dashboard. */
  val DashboardsKey: String = "dashboards"

  private val DefaultKey = "default"

  private val AccessKey = "access"

  /** Slug-sorted, so logs and the default-slug fallback are stable. */
  case class Decoded(
      dashboards: List[(String, Either[String, Dashboard.Validated])],
      default: Option[String]
  ) {
    def slugs: List[String] = dashboards.map(_._1)
  }

  /** Only the site shape raises; a failed dashboard is a `Left`, since the
    * others still serve. `sources` only locates invalid transforms.
    */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty
  ): IO[Decoded] =
    json.asObject.flatMap(_(DashboardsKey)).flatMap(_.asObject) match {
      case None     => missingDashboards.raiseError[IO, Decoded]
      case Some(ds) =>
        // Unreadable falls back to the restrictive default (issue #89): better
        // a login than refusing the whole instance over one setting.
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

  /** A `site.pkl` that amends `entry.pkl`. The file is the user's, so the
    * message is the instructions.
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

  def messageOf(err: Throwable): String =
    Option(err.getMessage)
      .filter(_.nonEmpty)
      .getOrElse(err.getClass.getSimpleName)
}
