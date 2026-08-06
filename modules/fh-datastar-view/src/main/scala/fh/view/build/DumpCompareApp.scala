package fh.view.build

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import fh.api.FHApi
import io.circe.Json

/** Runs [[DataDump]] (Jinja template) and [[RegistryDump]] (WebSocket
  * registries) against the SAME live instance and reports where they disagree.
  *
  * This is how the registry path earns the right to replace the template one:
  * the fields both produce must match entity-for-entity, and the fields only the
  * registry can produce are reported so the gain is visible too. Needs a live HA
  * (`SERVER`/`SECRET`), so it is a manual tool, not a test —
  * `fh-datastar-view/runMain fh.view.build.DumpCompareApp`.
  */
object DumpCompareApp extends IOApp {

  /** Fields the template path also produces, so a difference is a REGRESSION. */
  private val SharedFields =
    List("entity_id", "domain", "friendly_name", "area_id", "floor_id", "id_hidden")

  def run(args: List[String]): IO[ExitCode] =
    FHApi.fromEnv
      .use(api => (DataDump.fetch(api), RegistryDump.fetch(api)).tupled)
      .flatMap { case (old, neu) =>
        report(old, neu) *> args.headOption.traverse_ { out =>
          IO.blocking(os.write.over(os.Path(out, os.pwd), PklDump.render(neu))) *>
            IO.println(s"wrote rendered dump.pkl to $out")
        }
      }
      .as(ExitCode.Success)

  private def report(old: Json, neu: Json): IO[Unit] = {
    val oldEntities = entities(old)
    val newEntities = entities(neu)

    val onlyOld = oldEntities.keySet -- newEntities.keySet
    val onlyNew = newEntities.keySet -- oldEntities.keySet
    val shared = oldEntities.keySet.intersect(newEntities.keySet).toList.sorted

    val mismatches = shared.flatMap { key =>
      SharedFields.flatMap { field =>
        val a = oldEntities(key).apply(field).getOrElse(Json.Null)
        val b = newEntities(key).apply(field).getOrElse(Json.Null)
        // The template omits a field it has no value for; the registry path
        // writes an explicit null. Both mean "unset", so normalize before
        // comparing or every absent area_id reads as a difference.
        Option.when(a =!= b && !(a.isNull && b.isNull))(
          s"  $key.$field: template=${a.noSpaces} registry=${b.noSpaces}"
        )
      }
    }

    val withMembers = newEntities.values.count(o =>
      o("members").flatMap(_.asArray).exists(_.nonEmpty)
    )
    val categories = newEntities.values.toList
      .flatMap(_("entity_category").flatMap(_.asString))
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toList
      .sortBy(-_._2)

    IO.println(s"entities:  template=${oldEntities.size} registry=${newEntities.size}") *>
      IO.println(s"only in template: ${onlyOld.toList.sorted.take(20).mkString(", ")}") *>
      IO.println(s"only in registry: ${onlyNew.toList.sorted.take(20).mkString(", ")}") *>
      IO.println(s"shared-field mismatches: ${mismatches.size}") *>
      mismatches.take(40).traverse_(IO.println) *>
      IO.println(s"--- registry-only ---") *>
      IO.println(s"devices: ${count(neu, "devices")}") *>
      IO.println(s"entities with members: $withMembers") *>
      IO.println(s"entity_category: ${categories.mkString(", ")}") *>
      IO.println(s"areas: ${count(neu, "areas")} floors: ${count(neu, "floors")}")
  }

  private def entities(dump: Json): Map[String, io.circe.JsonObject] =
    dump.hcursor
      .downField("entities")
      .focus
      .flatMap(_.asObject)
      .map(_.toList.flatMap { case (k, v) => v.asObject.map(k -> _) }.toMap)
      .getOrElse(Map.empty)

  private def count(dump: Json, field: String): Int =
    dump.hcursor.downField(field).focus.flatMap(_.asObject).fold(0)(_.size)
}
