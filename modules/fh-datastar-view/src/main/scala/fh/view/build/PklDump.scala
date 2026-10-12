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

    // Each subclasses its `internal/dump-base.pkl` namespace, which declares the
    // property (so an empty house still has it) and derives `all`.
    val entitiesClass = namespace(
      "Entities",
      "entities",
      "EntityNamespace",
      named = entities.map { case (key, _) => key -> key }
    )

    val domainNamespaces = DomainNamespaces.flatMap { ns =>
      val members =
        entities.filter { case (_, eo) => namespaceOf(eo) == ns }
      Option.when(members.nonEmpty)(
        if (ns == Generic)
          namespace(
            ns.className,
            ns.property,
            ns.base,
            unnamed = members.map(_._1)
          )
        else
          namespace(
            ns.className,
            ns.property,
            ns.base,
            named = members.map { case (key, eo) =>
              val objectId =
                str(eo, "domain").fold(key)(d => key.stripPrefix(s"${d}_"))
              (if (objectId == "all") s"all_${ns.kind}" else objectId) -> key
            }
          )
      )
    }

    val areaClasses = areas.map { case (slug, ao) =>
      val areaId = str(ao, "area_id")
      val members = entities.filter { case (_, eo) =>
        str(eo, "area_id") == areaId && areaId.isDefined
      }
      val memberProps = members.map { case (key, _) =>
        s"  ${tick(key)}: ${entityClass(key)} = ${tick(s"e_$key")}"
      }
      // `hass.Area` derives `all` from it, and domains come out of that.
      val lists = Option
        .when(members.nonEmpty)(
          s"  allWithHidden = List(${members.map { case (key, _) => tick(key) }.mkString(", ")})"
        )
        .toList
      s"""class ${tick(s"Area_$slug")} extends hass.Area {
         |${(areaFields(ao) ++ memberProps ++ lists).mkString("\n")}
         |}""".stripMargin
    }

    // So an access rule names `dump.users.x`, not a raw HA id (ADR 0023).
    val usersClass = Option.when(users.nonEmpty)(
      container(
        "Users",
        "user",
        "hass.User",
        users.map { case (slug, uo) =>
          val fields = List(
            "user_id" -> str(uo, "user_id").map(pklString),
            "user_name" -> str(uo, "user_name").map(pklString),
            "is_admin" -> uo("is_admin").flatMap(_.asBoolean).map(_.toString),
            "is_owner" -> uo("is_owner").flatMap(_.asBoolean).map(_.toString)
          ).collect { case (k, Some(v)) => s"$k = $v" }
          slug -> s"hass.User = new { ${fields.mkString("; ")} }"
        }
      )
    )

    val areasClass = container(
      "Areas",
      "area",
      "hass.Area",
      areas.map { case (slug, _) => slug -> s"${tick(s"Area_$slug")} = new {}" }
    )

    // How the house is stacked; a floor with no level goes last.
    val floorsByLevel = floors.sortBy { case (slug, fo) =>
      val level = fo("level").flatMap(_.asNumber).flatMap(_.toInt)
      (level.isEmpty, level.getOrElse(0), slug)
    }

    val floorsClass = container(
      "Floors",
      "floor",
      "hass.Floor",
      floorsByLevel.map { case (slug, _) =>
        slug -> s"${tick(s"Floor_$slug")} = new {}"
      }
    )

    val floorClasses = floors.map { case (slug, fo) =>
      val floorAreas = fo("areas")
        .flatMap(_.asObject)
        .map(_.keys.toList.sorted)
        .getOrElse(Nil)
      val areaProps = floorAreas.flatMap { a =>
        val prop = s"  ${tick(floorAreaName(a))}: ${tick(s"Area_$a")} = new {}"
        if (floorAreaName(a) == a) List(prop)
        else
          List(s"  /// Area `$a`: the floor's own `$a` keeps the name.", prop)
      }
      val areasList = Option.when(floorAreas.nonEmpty)(
        s"  areas = List(${floorAreas.map(a => tick(floorAreaName(a))).mkString(", ")})"
      )
      val fields = List(
        str(fo, "floor_id").map(v => s"  floor_id = ${pklString(v)}"),
        str(fo, "floor_name").map(v => s"  floor_name = ${pklString(v)}"),
        fo("level")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(l => s"  level = $l")
      ).flatten
      s"""class ${tick(s"Floor_$slug")} extends hass.Floor {
         |${(fields ++ areaProps ++ areasList).mkString("\n")}
         |}""".stripMargin
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
      container(
        "Devices",
        "device",
        "hass.Device",
        devices.map { case (slug, _) =>
          slug -> s"${tick(s"Device_$slug")} = new {}"
        }
      )
    )

    // By alias: it must resolve to the same URI the library's own
    // `import "hass.pkl"` does, or a dump entity is not a card's `hass.Entity`
    // (ADR 0010, "Module identity").
    s"""/// GENERATED from the live HA registry by PklDump — do not edit.
       |/// The entity/area/floor dump, typed against `hass.pkl`.
       |///
       |/// EXTENDS the shared base so the house-wide namespaces (`entities`,
       |/// `lights`, …, `generic`, each answering `all`) are a declared contract
       |/// with `List()` defaults, not properties this generator has to remember
       |/// to emit. `extends` rather than `amends` because an amending module may
       |/// not declare classes, and a dump is mostly classes.
       |extends "@fh-dashboard/internal/dump-base.pkl"
       |
       |import "@fh-dashboard/hass.pkl"
       |
       |${entityDecls.mkString("\n\n")}
       |
       |$entitiesClass
       |
       |${domainNamespaces.mkString("\n\n")}
       |
       |${areaClasses.mkString("\n\n")}
       |
       |$areasClass
       |${usersClass.getOrElse("")}
       |
       |${floorClasses.mkString("\n\n")}
       |
       |$floorsClass
       |${deviceSection(deviceClasses, devicesClass)}
       |""".stripMargin
  }

  /** `hass.Floor`'s own properties. A floor class names each of its areas as a
    * property, and an area slugged like one of these would override it.
    */
  private[build] val FloorMembers =
    Set("floor_id", "floor_name", "areas", "level", "all", "allWithHidden")

  private def floorAreaName(slug: String): String =
    if (FloorMembers.contains(slug)) s"${slug}_area" else slug

  /** A namespace class holding one property per member, in `members` order, and
    * the same members as its `all`. A member slugged `all` is renamed
    * `all_<kind>` so it cannot shadow the list.
    */
  private def container(
      className: String,
      kind: String,
      element: String,
      members: List[(String, String)]
  ): String = {
    val named = members.map { case (slug, decl) =>
      (if (slug == "all") s"all_$kind" else slug) -> decl
    }
    s"""class $className {
       |${named.map { case (n, decl) => s"  ${tick(n)}: $decl" }.mkString("\n")}
       |  hidden all: List<$element> = List(${named
        .map(n => tick(n._1))
        .mkString(", ")})
       |}
       |
       |${className.toLowerCase}: $className = new {}""".stripMargin
  }

  /** One of `internal/dump-base.pkl`'s entity namespaces, filled: `named` as
    * (property, entity key) members, and `allWithHidden` of every member in
    * entity-id order. `unnamed` members are listed without a property.
    */
  private def namespace(
      className: String,
      property: String,
      base: String,
      named: List[(String, String)] = Nil,
      unnamed: List[String] = Nil
  ): String = {
    val props = named.map { case (n, key) =>
      s"  ${tick(n)}: ${entityClass(key)} = ${tick(s"e_$key")}"
    }
    val refs = named.map(n => tick(n._1)) ++ unnamed.map(k => tick(s"e_$k"))
    val all =
      Option.when(refs.nonEmpty)(
        s"  allWithHidden = List(${refs.mkString(", ")})"
      )
    s"""class $className extends $base {
       |${(props ++ all).mkString("\n")}
       |}
       |
       |$property: $className = new {}""".stripMargin
  }

  /** A typed domain's house namespace. Its `kind` renames a member slugged
    * `all` (`all_light`), as [[container]] does.
    */
  private case class DomainNamespace(
      entityType: String,
      property: String,
      className: String,
      base: String,
      kind: String
  ) derives CanEqual

  private val Generic =
    DomainNamespace("", "generic", "Generic", "GenericNamespace", "generic")

  /** One per selector in `hass.pkl`; a number or select is typed but has none,
    * so it lands in [[Generic]] as `hass.generic` puts it.
    */
  private val DomainNamespaces: List[DomainNamespace] = List(
    DomainNamespace(
      "hass.LightEntity",
      "lights",
      "Lights",
      "LightNamespace",
      "light"
    ),
    DomainNamespace(
      "hass.LockEntity",
      "locks",
      "Locks",
      "LockNamespace",
      "lock"
    ),
    DomainNamespace(
      "hass.SensorEntity",
      "sensors",
      "Sensors",
      "SensorNamespace",
      "sensor"
    ),
    DomainNamespace(
      "hass.BinarySensorEntity",
      "binarySensors",
      "BinarySensors",
      "BinarySensorNamespace",
      "binary_sensor"
    ),
    DomainNamespace(
      "hass.SwitchEntity",
      "switches",
      "Switches",
      "SwitchNamespace",
      "switch"
    ),
    DomainNamespace(
      "hass.SceneEntity",
      "scenes",
      "Scenes",
      "SceneNamespace",
      "scene"
    ),
    DomainNamespace(
      "hass.ScriptEntity",
      "scripts",
      "Scripts",
      "ScriptNamespace",
      "script"
    ),
    DomainNamespace(
      "hass.ButtonEntity",
      "buttons",
      "Buttons",
      "ButtonNamespace",
      "button"
    ),
    DomainNamespace(
      "hass.InputButtonEntity",
      "inputButtons",
      "InputButtons",
      "InputButtonNamespace",
      "input_button"
    ),
    Generic
  )

  private def namespaceOf(eo: JsonObject): DomainNamespace =
    DomainNamespaces.find(_.entityType == entityType(eo)).getOrElse(Generic)

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
      case Some("scene")         => "hass.SceneEntity"
      case Some("script")        => "hass.ScriptEntity"
      case Some("button")        => "hass.ButtonEntity"
      case Some("input_button")  => "hass.InputButtonEntity"
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
          .flatMap(_.asArray)
          .fold(Vector.empty[String])(_.flatMap(_.asString))
        val (known, unknown) = modes.partition(HassVocabulary.ColorModes)
        val features = attrs("supported_features")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(v => s"  supported_features = $v")
        Option
          .when(known.nonEmpty)(
            s"  colourModes = new Listing { ${known.map(pklString).mkString("; ")} }"
          )
          .toList ++ unknown.map(unvendored("supported_color_modes", _)) ++
          features
      case Some("lock") =>
        attrs("supported_features")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(v => s"  supported_features = $v")
          .toList
      case Some("sensor") =>
        vocabField(attrs, "device_class", HassVocabulary.SensorDeviceClasses) ++
          vocabField(attrs, "state_class", HassVocabulary.SensorStateClasses) ++
          str(attrs, "unit_of_measurement")
            .map(v => s"  unit_of_measurement = ${pklString(v)}")
            .toList ++
          attrs("options")
            .flatMap(pklTyped)
            .map { case (_, rendered) => s"  options = $rendered" }
            .toList
      case Some("binary_sensor") =>
        vocabField(
          attrs,
          "device_class",
          HassVocabulary.BinarySensorDeviceClasses
        )
      case _ => Nil
    }
  }

  /** A field typed by a vendored union takes only the values that union names
    * (ADR 0013): HA adding one is not a breaking change, but assigning it here
    * would fail every dashboard that reads the field.
    */
  private def vocabField(
      attrs: JsonObject,
      name: String,
      known: Set[String]
  ): List[String] =
    str(attrs, name).toList.map { v =>
      if (known.contains(v)) s"  $name = ${pklString(v)}"
      else unvendored(name, v)
    }

  private def unvendored(name: String, value: String): String =
    s"  // $name $value is not in the vendored union; re-sync hass/"

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
    def objects(field: String): List[(String, JsonObject)] =
      transformed.hcursor
        .downField(field)
        .focus
        .flatMap(_.asObject)
        .map(_.toList.flatMap { case (k, v) => v.asObject.map(k -> _) })
        .getOrElse(Nil)

    val renamedAreas = objects("floors").sortBy(_._1).flatMap { case (f, fo) =>
      fo("areas")
        .flatMap(_.asObject)
        .fold(Nil)(_.keys.toList.sorted)
        .filter(a => floorAreaName(a) != a)
        .map(a =>
          s"floor $f: area '$a' is `${floorAreaName(a)}` on the floor, " +
            s"which has a '$a' of its own"
        )
    }

    renamedAreas ++ objects("entities")
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
          s"  ${tick(name)}: $tpe = $rendered"
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
