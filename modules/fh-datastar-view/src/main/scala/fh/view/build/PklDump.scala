package fh.view.build

import cats.syntax.all.*
import io.circe.{Json, JsonObject}

/** The typed `dump.pkl` module, against `lib/hass.pkl`: every floor, area and
  * entity a named property, so a typo is an eval error (ADR 0013).
  *
  * `Listing` values are assigned, never amended: amending a null default is a
  * type error once forced. Nullable fields are omitted when absent.
  */
object PklDump {

  /** `transformed` is the output of [[RegistryDump.transform]]. */
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
    val devices = keyed("devices")
    val users = keyed("users")

    // Member edges reference `e_*` consts; a group naming a removed or disabled
    // entity would otherwise dangle and fail every dashboard's eval.
    val known = entities.map(_._1).toSet

    // One class per entity, carrying exactly its capabilities: reading one it
    // lacks is a Pkl error, not a null.
    val entityDecls = entities.map { case (key, eo) =>
      val caps = capabilityDecls(eo) ++ schemaGroups(key, eo)
      val body = if (caps.isEmpty) "" else caps.mkString("\n") + "\n"
      s"""class ${entityClass(key)} extends ${entityType(eo)} {
         |$body}
         |
         |const hidden ${tick(s"e_$key")}: ${entityClass(
          key
        )} = ${entityLiteral(eo, known)}""".stripMargin
    }

    val entitiesClass =
      s"""class Entities {
         |${entities
          .map { case (key, _) =>
            s"  ${tick(key)}: ${entityClass(key)} = ${tick(s"e_$key")}"
          }
          .mkString("\n")}
         |}
         |
         |entities: Entities = new {}""".stripMargin

    // Declared (with a `List()` default) in `internal/dump-base.pkl`, which also
    // derives the per-domain lists; only filled here.
    val domainLists =
      Option
        .when(entities.nonEmpty)(
          s"all = List(${entities.map { case (key, _) => tick(s"e_$key") }.mkString(", ")})"
        )
        .getOrElse("")

    val areaClasses = areas.map { case (slug, ao) =>
      val areaId = str(ao, "area_id")
      val members = entities.filter { case (_, eo) =>
        str(eo, "area_id") == areaId && areaId.isDefined
      }
      val memberProps = members.map { case (key, _) =>
        s"  ${tick(key)}: ${entityClass(key)} = ${tick(s"e_$key")}"
      }
      // Domains are selected out of `all` (`hass.lights(area.all)`).
      val lists = Option
        .when(members.nonEmpty)(
          s"  all = List(${members.map { case (key, _) => tick(key) }.mkString(", ")})"
        )
        .toList
      s"""class ${tick(s"Area_$slug")} extends hass.Area {
         |${(areaFields(ao) ++ memberProps ++ lists).mkString("\n")}
         |}""".stripMargin
    }

    // So an access rule names `dump.users.x`, not a raw HA id (ADR 0023).
    val usersClass = Option.when(users.nonEmpty)(
      s"""class Users {
         |${users
          .map { case (slug, uo) =>
            val fields = List(
              "user_id" -> str(uo, "user_id").map(pklString),
              "user_name" -> str(uo, "user_name").map(pklString),
              "is_admin" -> uo("is_admin").flatMap(_.asBoolean).map(_.toString),
              "is_owner" -> uo("is_owner").flatMap(_.asBoolean).map(_.toString)
            ).collect { case (k, Some(v)) => s"$k = $v" }
            s"  ${tick(slug)}: hass.User = new { ${fields.mkString("; ")} }"
          }
          .mkString("\n")}
         |}
         |
         |users: Users = new {}""".stripMargin
    )

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
        str(fo, "floor_name").map(v => s"  floor_name = ${pklString(v)}"),
        fo("level")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(l => s"  level = $l")
      ).flatten
      val propName =
        if (Set("entities", "areas", "output").contains(slug)) s"${slug}_floor"
        else slug
      s"""class ${tick(s"Floor_$slug")} extends hass.Floor {
         |${(fields ++ areaProps ++ areasList).mkString("\n")}
         |}
         |
         |${tick(propName)}: ${tick(s"Floor_$slug")} = new {}""".stripMargin
    }

    val deviceClasses = devices.map { case (slug, dvo) =>
      val deviceId = str(dvo, "device_id")
      val memberProps = entities.collect {
        case (key, eo)
            if deviceId.isDefined && str(eo, "device_id") == deviceId =>
          s"  ${tick(key)}: ${entityClass(key)} = ${tick(s"e_$key")}"
      }
      val entityList = Option.when(memberProps.nonEmpty)(
        s"  entities = List(${entities
            .collect {
              case (key, eo)
                  if deviceId.isDefined && str(eo, "device_id") == deviceId =>
                tick(key)
            }
            .mkString(", ")})"
      )
      val fields = List(
        str(dvo, "device_id").map(v => s"  device_id = ${pklString(v)}"),
        str(dvo, "device_name").map(v => s"  device_name = ${pklString(v)}"),
        str(dvo, "area_id").map(v => s"  area_id = ${pklString(v)}"),
        str(dvo, "manufacturer").map(v => s"  manufacturer = ${pklString(v)}"),
        str(dvo, "model").map(v => s"  model = ${pklString(v)}")
      ).flatten
      s"""class ${tick(s"Device_$slug")} extends hass.Device {
         |${(fields ++ memberProps ++ entityList).mkString("\n")}
         |}""".stripMargin
    }

    val devicesClass = Option.when(devices.nonEmpty)(
      s"""class Devices {
         |${devices
          .map { case (slug, _) =>
            s"  ${tick(slug)}: ${tick(s"Device_$slug")} = new {}"
          }
          .mkString("\n")}
         |}
         |
         |devices: Devices = new {}""".stripMargin
    )

    // By alias: it must resolve to the same URI the library's own
    // `import "hass.pkl"` does, or a dump entity is not a card's `hass.Entity`
    // (ADR 0010, "Module identity").
    s"""/// GENERATED from the live HA registry by PklDump — do not edit.
       |/// The entity/area/floor dump, typed against `hass.pkl`.
       |///
       |/// EXTENDS the shared base so the house-wide lists (`all`, and the
       |/// per-domain `lights`/`locks`/`sensors`/`switches`/`generic` derived
       |/// from it) are a declared contract with `List()` defaults, not
       |/// properties this generator has to remember to emit.
       |/// `extends` rather than `amends` because an amending module may not
       |/// declare classes, and a dump is mostly classes.
       |extends "@fh-dashboard/internal/dump-base.pkl"
       |
       |import "@fh-dashboard/hass.pkl"
       |
       |${entityDecls.mkString("\n\n")}
       |
       |$entitiesClass
       |
       |$domainLists
       |
       |${areaClasses.mkString("\n\n")}
       |
       |$areasClass
       |${usersClass.getOrElse("")}
       |
       |${floorDecls.mkString("\n\n")}
       |${deviceSection(deviceClasses, devicesClass)}
       |""".stripMargin
  }

  private def deviceSection(
      classes: List[String],
      devicesClass: Option[String]
  ): String =
    devicesClass.fold("")(dc => s"\n${classes.mkString("\n\n")}\n\n$dc\n")

  private def str(o: JsonObject, field: String): Option[String] =
    o(field).flatMap(_.asString)

  private def entityType(eo: JsonObject): String =
    str(eo, "domain") match {
      case Some("light")         => "hass.LightEntity"
      case Some("lock")          => "hass.LockEntity"
      case Some("sensor")        => "hass.SensorEntity"
      case Some("binary_sensor") => "hass.BinarySensorEntity"
      case Some("switch")        => "hass.SwitchEntity"
      case Some("number")        => "hass.NumberEntity"
      case Some("select")        => "hass.SelectEntity"
      case _                     => "hass.GenericEntity"
    }

  /** Owned by `hass.Entity`; an attribute of the same name is skipped. */
  private val ReservedProperties = Set(
    "entity_id",
    "domain",
    "friendly_name",
    "area_id",
    "floor_id",
    "id_hidden",
    "device_id",
    "entity_category",
    "members",
    "volatileAttrs"
  )

  /** Modelled by the domain's schema, so not redeclared per entity. */
  private val SchemaModelled: Map[String, Set[String]] = Map(
    "light" -> Set(
      "supported_color_modes",
      "supported_features",
      "min_color_temp_kelvin",
      "max_color_temp_kelvin",
      "effect_list"
    ),
    // Not `code_format`: no card asks for a code, so it stays per-entity.
    "lock" -> Set("supported_features"),
    // Not `icon`: `core/icon.pkl` owns the glyph choice.
    "sensor" -> Set(
      "device_class",
      "state_class",
      "unit_of_measurement",
      "options"
    ),
    "binary_sensor" -> Set("device_class")
  )

  private def entityClass(key: String): String = tick(s"E_$key")

  /** Assignments to fields the domain class declares; groups are
    * [[schemaGroups]].
    */
  private def schemaFields(eo: JsonObject): List[String] = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    str(eo, "domain") match {
      case Some("light") =>
        val modes = attrs("supported_color_modes")
          .flatMap(pklTyped)
          .map { case (_, rendered) => s"  colourModes = $rendered" }
        val features = attrs("supported_features")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(v => s"  supported_features = $v")
        List(modes, features).flatten
      case Some("lock") =>
        attrs("supported_features")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(v => s"  supported_features = $v")
          .toList
      case Some("sensor") =>
        deviceClassField(attrs, HassVocabulary.SensorDeviceClasses) ++
          List("state_class", "unit_of_measurement")
            .flatMap(n => str(attrs, n).map(v => s"  $n = ${pklString(v)}")) ++
          attrs("options")
            .flatMap(pklTyped)
            .map { case (_, rendered) => s"  options = $rendered" }
            .toList
      case Some("binary_sensor") =>
        deviceClassField(attrs, HassVocabulary.BinarySensorDeviceClasses)
      case _ => Nil
    }
  }

  /** A value outside the vendored union is dropped to a comment: HA grows
    * `SensorDeviceClass` most releases, so assigning it would fail a newer HA's
    * whole dashboard, and no card has a branch for it anyway.
    * `supported_color_modes` is assigned verbatim because that set is closed.
    */
  private def deviceClassField(
      attrs: JsonObject,
      known: Set[String]
  ): List[String] =
    str(attrs, "device_class").toList.map { v =>
      if (known.contains(v)) s"  device_class = ${pklString(v)}"
      else s"  // device_class $v is not in the vendored union; re-sync hass/"
    }

  /** Each complete capability group, narrowed on the entity's own class
    * (`ColourTemp?` on the domain, `ColourTemp` here), so a named entity needs
    * no `!!` while generic code still guards. A partial group is dropped and
    * reported by [[warnings]]: Pkl's lazy required properties would blame the
    * schema much later.
    */
  private def schemaGroups(key: String, eo: JsonObject): List[String] = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    // So a card given only the group knows its subject; the self-reference is
    // fine, module consts resolve lazily.
    val owner = s"owner = ${tick(s"e_$key")}"
    str(eo, "domain") match {
      case Some("light") =>
        val colourTemp = (
          attrs("min_color_temp_kelvin").flatMap(_.asNumber).flatMap(_.toInt),
          attrs("max_color_temp_kelvin").flatMap(_.asNumber).flatMap(_.toInt)
        ) match {
          case (Some(lo), Some(hi)) =>
            Some(
              "  hidden colourTemp: hass.ColourTemp = " +
                s"new { $owner; min_kelvin = $lo; max_kelvin = $hi }"
            )
          case _ => None
        }
        val effects = attrs("effect_list")
          .flatMap(pklTyped)
          .map { case (_, rendered) =>
            s"  hidden effects: hass.Effects = new { $owner; list = $rendered }"
          }
        List(colourTemp, effects).flatten
      case _ => Nil
    }
  }

  /** Not fatal: one odd integration must not stop the house's dump. */
  def warnings(transformed: Json): List[String] = {
    val entities = transformed.hcursor
      .downField("entities")
      .focus
      .flatMap(_.asObject)
      .map(_.toList.flatMap { case (k, v) => v.asObject.map(k -> _) })
      .getOrElse(Nil)

    entities
      .sortBy(_._1)
      .filter { case (_, eo) =>
        str(eo, "domain").contains("light")
      }
      .flatMap { case (_, eo) =>
        val id = str(eo, "entity_id").getOrElse("?")
        val attrs =
          eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
        val lo = attrs("min_color_temp_kelvin").isDefined
        val hi = attrs("max_color_temp_kelvin").isDefined
        val modes = attrs("supported_color_modes")
          .flatMap(_.asArray)
          .fold(Set.empty[String])(_.flatMap(_.asString).toSet)
        List(
          Option.when(lo != hi)(
            s"$id: colour temperature half-reported (min=$lo max=$hi) — " +
              "dropping the colourTemp group"
          ),
          Option.when(modes.contains("color_temp") && !(lo && hi))(
            s"$id: reports the color_temp mode but no kelvin range — " +
              "colourTemp will be null"
          )
        ).flatten
      }
  }

  /** Non-nullable, on the entity's own class: whether X exists is whether the
    * property does.
    */
  private def capabilityDecls(eo: JsonObject): List[String] = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val modelled =
      SchemaModelled.getOrElse(str(eo, "domain").getOrElse(""), Set.empty)
    attrs.toList
      .filterNot { case (name, _) =>
        ReservedProperties.contains(name) || modelled.contains(name)
      }
      .sortBy(_._1)
      .flatMap { case (name, value) =>
        pklTyped(value).map { case (tpe, rendered) =>
          // Typed as the union, so a typo in a comparison fails the eval.
          val declared =
            if (name == "supported_color_modes") "Listing<hass.ColorMode>"
            else tpe
          s"  ${tick(name)}: $declared = $rendered"
        }
      }
  }

  private def entityLiteral(eo: JsonObject, known: Set[String]): String = {
    val memberRefs = eo("members")
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_.asString)
      .map(RegistryDump.entityKey)
      .filter(known.contains)
      .distinct
    val members = Option.when(memberRefs.nonEmpty)(
      s"  members = List(${memberRefs.map(k => tick(s"e_$k")).mkString(", ")})"
    )

    val fields = List(
      str(eo, "entity_id").map(v => s"  entity_id = ${pklString(v)}"),
      str(eo, "domain").map(v => s"  domain = ${pklString(v)}"),
      str(eo, "friendly_name").map(v => s"  friendly_name = ${pklString(v)}"),
      str(eo, "area_id").map(v => s"  area_id = ${pklString(v)}"),
      str(eo, "floor_id").map(v => s"  floor_id = ${pklString(v)}"),
      str(eo, "device_id").map(v => s"  device_id = ${pklString(v)}"),
      str(eo, "entity_category").map(v =>
        s"  entity_category = ${pklString(v)}"
      ),
      eo("id_hidden")
        .flatMap(_.asBoolean)
        .filter(identity)
        .as("  id_hidden = true")
      // Capability values are defaults on the entity's own class, not here.
    ).flatten ++ schemaFields(eo) ++ members.toList

    s"new {\n${fields.mkString("\n")}\n}"
  }

  /** None for objects, mixed arrays and nulls: a property is declared only when
    * its type can be stated, and a null is the same as absent.
    */
  private def pklTyped(j: Json): Option[(String, String)] =
    j.fold(
      None,
      b => Some("Boolean" -> b.toString),
      n =>
        Some(
          n.toLong
            .map(l => "Int" -> l.toString)
            .getOrElse("Float" -> n.toDouble.toString)
        ),
      s => Some("String" -> pklString(s)),
      arr =>
        val strings = arr.flatMap(_.asString)
        val numbers = arr.flatMap(_.asNumber)
        if (arr.nonEmpty && strings.sizeIs == arr.size)
          Some(
            "Listing<String>" ->
              s"new Listing { ${strings.map(pklString).mkString("; ")} }"
          )
        else if (arr.nonEmpty && numbers.sizeIs == arr.size)
          Some(
            "Listing<Number>" ->
              s"new Listing { ${numbers.map(_.toDouble).mkString("; ")} }"
          )
        else None
      ,
      _ => None
    )

  private def areaFields(ao: JsonObject): List[String] =
    List(
      str(ao, "area_id").map(v => s"  area_id = ${pklString(v)}"),
      str(ao, "area_name").map(v => s"  area_name = ${pklString(v)}"),
      str(ao, "floor_id").map(v => s"  floor_id = ${pklString(v)}")
    ).flatten

  // Pkl's own lexer, so the keyword set cannot drift from the evaluator.
  private def tick(name: String): String =
    org.pkl.parser.Lexer.maybeQuoteIdentifier(name)

  // Escaping `\` first also defuses `\(` interpolation.
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
