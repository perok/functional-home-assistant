package fh.view.build

import io.circe.{Json, JsonObject}

/** Renders the transformed [[DataDump]] JSON as a typed Pkl module — the
  * `lib/dump.pkl` counterpart of `dump.libsonnet`, typed against the
  * hand-written `lib/hass.pkl` schema.
  *
  * Every floor/area/entity becomes a NAMED, TYPED property, so
  * `dump.entities.light_kitchen` and `dump.<floor>.<area>.<entityKey>`
  * dot-complete in a Pkl editor and a typo is an eval error. Plain string
  * templating, per project convention (scalameta does not support Scala 3).
  *
  * Generation safety rules:
  *   - every generated identifier is backticked (defends against Pkl reserved
  *     words like `override` with zero case analysis);
  *   - string values go through [[pklString]] (escaping `\` first also
  *     neutralizes Pkl's `\(...)` interpolation trigger);
  *   - nullable schema fields are omitted when absent (their default is null);
  *   - `Listing` values are ASSIGNED (`= new Listing {...}`), never amended —
  *     amending a null default is a type error when the value is forced.
  */
object PklDump {

  /** Render the module source. `transformed` is the OUTPUT of
    * [[DataDump.transform]] (objects keyed by sanitized names), i.e. the same
    * JSON that is written to `dump.libsonnet`.
    */
  def render(transformed: Json): String = {
    val root = transformed.asObject.getOrElse(JsonObject.empty)

    def keyed(field: String): List[(String, JsonObject)] =
      root(field)
        .flatMap(_.asObject)
        .map(_.toList.flatMap { case (k, v) => v.asObject.map(k -> _) })
        .getOrElse(Nil)
        .sortBy(_._1)

    val entities = keyed("entities")
    val areas = keyed("areas")
    val floors = keyed("floors")

    val entityDecls = entities.map { case (key, eo) =>
      s"const hidden ${tick(s"e_$key")}: ${entityType(eo)} = ${entityLiteral(eo)}"
    }

    val entitiesClass =
      s"""class Entities {
         |${entities
          .map { case (key, eo) =>
            s"  ${tick(key)}: ${entityType(eo)} = ${tick(s"e_$key")}"
          }
          .mkString("\n")}
         |}
         |
         |entities: Entities = new {}""".stripMargin

    // One class per area (from the flat map — floor nesting references these).
    // Members = entities whose raw `area_id` matches the area's.
    val areaClasses = areas.map { case (slug, ao) =>
      val areaId = str(ao, "area_id")
      val members = entities.filter { case (_, eo) =>
        str(eo, "area_id") == areaId && areaId.isDefined
      }
      val memberProps = members.map { case (key, eo) =>
        s"  ${tick(key)}: ${entityType(eo)} = ${tick(s"e_$key")}"
      }
      def domainList(name: String, pred: String => Boolean) = {
        val keys = members.collect {
          case (key, eo) if str(eo, "domain").exists(pred) => tick(key)
        }
        Option.when(keys.nonEmpty)(s"  $name = List(${keys.mkString(", ")})")
      }
      val lists = List(
        domainList("lights", _ == "light"),
        domainList("sensors", _ == "sensor"),
        domainList("switches", _ == "switch"),
        domainList(
          "generic",
          d => d != "light" && d != "sensor" && d != "switch"
        )
      ).flatten
      s"""class ${tick(s"Area_$slug")} extends hass.Area {
         |${(areaFields(ao) ++ memberProps ++ lists).mkString("\n")}
         |}""".stripMargin
    }

    val areasClass =
      s"""class Areas {
         |${areas
          .map { case (slug, _) =>
            s"  ${tick(slug)}: ${tick(s"Area_$slug")} = new {}"
          }
          .mkString("\n")}
         |}
         |
         |areas: Areas = new {}""".stripMargin

    // One class + one top-level property per floor; its areas come from the
    // floor's nested slug-keyed `areas` object (same slugs as the flat map).
    val floorDecls = floors.map { case (slug, fo) =>
      val floorAreas = fo("areas")
        .flatMap(_.asObject)
        .map(_.keys.toList.sorted)
        .getOrElse(Nil)
      val areaProps =
        floorAreas.map(a => s"  ${tick(a)}: ${tick(s"Area_$a")} = new {}")
      val areasList = Option.when(floorAreas.nonEmpty)(
        s"  areas = List(${floorAreas.map(tick).mkString(", ")})"
      )
      val fields = List(
        str(fo, "floor_id").map(v => s"  floor_id = ${pklString(v)}"),
        str(fo, "floor_name").map(v => s"  floor_name = ${pklString(v)}")
      ).flatten
      // Guard the module namespace: a floor named e.g. "Entities" must not
      // shadow the fixed `entities`/`areas`/`output` properties.
      val propName =
        if (Set("entities", "areas", "output").contains(slug)) s"${slug}_floor"
        else slug
      s"""class ${tick(s"Floor_$slug")} extends hass.Floor {
         |${(fields ++ areaProps ++ areasList).mkString("\n")}
         |}
         |
         |${tick(propName)}: ${tick(s"Floor_$slug")} = new {}""".stripMargin
    }

    s"""/// GENERATED from the live HA registry by PklDump — do not edit.
       |/// The Pkl counterpart of `dump.libsonnet`, typed against `hass.pkl`.
       |module dump
       |
       |import "hass.pkl"
       |
       |${entityDecls.mkString("\n\n")}
       |
       |$entitiesClass
       |
       |${areaClasses.mkString("\n\n")}
       |
       |$areasClass
       |
       |${floorDecls.mkString("\n\n")}
       |""".stripMargin
  }

  private def str(o: JsonObject, field: String): Option[String] =
    o(field).flatMap(_.asString)

  /** The hass.pkl class for an entity's domain (GenericEntity fallback). */
  private def entityType(eo: JsonObject): String =
    str(eo, "domain") match {
      case Some("light")  => "hass.LightEntity"
      case Some("sensor") => "hass.SensorEntity"
      case Some("switch") => "hass.SwitchEntity"
      case _              => "hass.GenericEntity"
    }

  /** The `new { ... }` literal for one entity. Typed classes default their
    * `domain`, but emitting it unconditionally is harmless and keeps
    * GenericEntity (where it is required) uniform.
    */
  private def entityLiteral(eo: JsonObject): String = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val effectList = attrs("effect_list")
      .flatMap(_.asArray)
      .map(_.flatMap(_.asString))
      .filter(_.nonEmpty)
      .map(es =>
        s"  effect_list = new Listing { ${es.map(pklString).mkString("; ")} }"
      )
    val fields = List(
      str(eo, "entity_id").map(v => s"  entity_id = ${pklString(v)}"),
      str(eo, "domain").map(v => s"  domain = ${pklString(v)}"),
      str(eo, "friendly_name").map(v => s"  friendly_name = ${pklString(v)}"),
      str(eo, "area_id").map(v => s"  area_id = ${pklString(v)}"),
      str(eo, "floor_id").map(v => s"  floor_id = ${pklString(v)}"),
      eo("id_hidden")
        .flatMap(_.asBoolean)
        .filter(identity)
        .map(_ => "  id_hidden = true"),
      str(attrs, "color_mode").map(v => s"  color_mode = ${pklString(v)}"),
      effectList
    ).flatten
    s"new {\n${fields.mkString("\n")}\n}"
  }

  private def areaFields(ao: JsonObject): List[String] =
    List(
      str(ao, "area_id").map(v => s"  area_id = ${pklString(v)}"),
      str(ao, "area_name").map(v => s"  area_name = ${pklString(v)}"),
      str(ao, "floor_id").map(v => s"  floor_id = ${pklString(v)}")
    ).flatten

  /** Backtick a generated identifier — legal for any name, immune to Pkl
    * reserved words.
    */
  private def tick(name: String): String = s"`$name`"

  /** A double-quoted Pkl string literal. Escaping `\` first turns any `\(` in
    * the input into a literal backslash + paren (no interpolation).
    */
  private def pklString(s: String): String = {
    val escaped = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""
  }
}
