package fh.view.build

import io.circe.{Json, JsonObject}

/** Renders the transformed [[DataDump]] JSON as the typed `lib/dump.pkl`
  * module, typed against the hand-written `lib/hass.pkl` schema.
  *
  * Every floor/area/entity becomes a NAMED, TYPED property, so
  * `dump.entities.light_kitchen` and `dump.<floor>.<area>.<entityKey>`
  * dot-complete in a Pkl editor and a typo is an eval error. Plain string
  * templating, per project convention (scalameta does not support Scala 3).
  *
  * Generation safety rules:
  *   - generated identifiers are backticked only when Pkl's own lexer says the
  *     plain form is illegal — reserved words like `override` or a
  *     digit-leading slug like `3rd_floor` (see [[tick]]);
  *   - string values go through [[pklString]] (escaping `\` first also
  *     neutralizes Pkl's `\(...)` interpolation trigger);
  *   - nullable schema fields are omitted when absent (their default is null);
  *   - `Listing` values are ASSIGNED (`= new Listing {...}`), never amended —
  *     amending a null default is a type error when the value is forced.
  */
object PklDump {

  /** Render the module source. `transformed` is the OUTPUT of
    * [[DataDump.transform]] (objects keyed by sanitized names).
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
    val devices = keyed("devices")

    // Member/sibling edges are emitted as references to the `e_*` consts, so a
    // group's members ARE the dump entities (same object, live `.members`
    // recursion) rather than id strings the author has to look up again. Only
    // ids that actually made it into the dump can be referenced — a group may
    // name an entity that has since been removed or disabled, and a dangling
    // `e_*` would be an eval error in every dashboard.
    val known = entities.map(_._1).toSet

    // One class PER ENTITY, carrying exactly the capabilities that entity
    // reports. The domain class it extends carries none, so a light without
    // color temperature has no `min_color_temp_kelvin` property at all and
    // reading one is a Pkl error rather than a null (ADR 0013).
    val entityDecls = entities.map { case (key, eo) =>
      val caps = capabilityDecls(eo)
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

    // One class per area (from the flat map — floor nesting references these).
    // Members = entities whose raw `area_id` matches the area's.
    val areaClasses = areas.map { case (slug, ao) =>
      val areaId = str(ao, "area_id")
      val members = entities.filter { case (_, eo) =>
        str(eo, "area_id") == areaId && areaId.isDefined
      }
      val memberProps = members.map { case (key, _) =>
        s"  ${tick(key)}: ${entityClass(key)} = ${tick(s"e_$key")}"
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
        str(fo, "floor_name").map(v => s"  floor_name = ${pklString(v)}"),
        fo("level")
          .flatMap(_.asNumber)
          .flatMap(_.toInt)
          .map(l => s"  level = $l")
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

    // One class per device, holding references to the entities that report it
    // as their `device_id` — the "one appliance, several entities" grouping,
    // which is orthogonal to area/floor. Omitted entirely when the dump carries
    // no devices (the legacy template path produces none).
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

    // The schema comes in BY ALIAS, not as a file sibling: `dump.pkl` lives in
    // its own `@fh-home` package (it is live per-home data and can never ship
    // inside the shared `@fh-dashboard` library), so it is no longer a sibling
    // of `hass.pkl`. The alias resolves to
    // `projectpackage://fh.invalid/fh-dashboard@1.0.0#/hass.pkl` — the SAME URI
    // `components.pkl`'s own relative `import "hass.pkl"` lands on — which is
    // what keeps a dump entity assignable to a card factory's `hass.Entity`
    // parameter. See ADR 0010, "Module identity".
    s"""/// GENERATED from the live HA registry by PklDump — do not edit.
       |/// The entity/area/floor dump, typed against `hass.pkl`.
       |module dump
       |
       |import "@fh-dashboard/hass.pkl"
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
       |${deviceSection(deviceClasses, devicesClass)}
       |""".stripMargin
  }

  /** The device half of the module, or nothing at all when the dump carries no
    * devices — an empty `class Devices {}` would still be valid Pkl, but it
    * advertises a namespace with nothing in it.
    */
  private def deviceSection(
      classes: List[String],
      devicesClass: Option[String]
  ): String =
    devicesClass.fold("")(dc => s"\n${classes.mkString("\n\n")}\n\n$dc\n")

  private def str(o: JsonObject, field: String): Option[String] =
    o(field).flatMap(_.asString)

  /** The hass.pkl class for an entity's domain (GenericEntity fallback). */
  private def entityType(eo: JsonObject): String =
    str(eo, "domain") match {
      case Some("light")  => "hass.LightEntity"
      case Some("sensor") => "hass.SensorEntity"
      case Some("switch") => "hass.SwitchEntity"
      case Some("number") => "hass.NumberEntity"
      case Some("select") => "hass.SelectEntity"
      case _              => "hass.GenericEntity"
    }

  /** Property names `hass.Entity` and its domain subclasses already own. An
    * incoming attribute that collides with one is skipped rather than
    * redeclared, which would shadow the schema's own field.
    */
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
    "isDynamic"
  )

  /** The generated class name for one entity. */
  private def entityClass(key: String): String = tick(s"E_$key")

  /** Capability predicate overrides for one entity — only the TRUE ones, since
    * the domain class already defaults each to false.
    */
  private def predicates(eo: JsonObject): List[String] =
    eo("capabilities")
      .flatMap(_.asObject)
      .map(_.toList.collect {
        case (name, v) if v.asBoolean.contains(true) => s"  $name = true"
      })
      .getOrElse(Nil)
      .sorted

  /** The capability declarations for one entity: `name: Type = value`, one per
    * attribute the entity actually reports.
    *
    * These go on the entity's OWN class, never on a shared schema class, and
    * they are NOT nullable — the entity reports the capability, so the value
    * exists. An entity without the capability simply has no such property, and
    * reading it is a Pkl error instead of a silent null. That is the whole
    * point: the dump answers "does this entity have X" by whether X is there.
    */
  private def capabilityDecls(eo: JsonObject): List[String] = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    attrs.toList
      .filterNot { case (name, _) => ReservedProperties.contains(name) }
      .sortBy(_._1)
      .flatMap { case (name, value) =>
        pklTyped(value).map { case (tpe, rendered) =>
          // `supported_color_modes` is HA's own ColorMode enum, so declare it as
          // that union rather than a bare String list — a typo in an author's
          // comparison then fails the eval instead of never matching.
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
      .map(DataDump.entityKey)
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
        .map(_ => "  id_hidden = true")
      // Capability VALUES are not assigned here — they are declared with their
      // value as the default on the entity's OWN class, so `new {}` carries
      // them. Capability PREDICATES are assigned, because they are declared on
      // the shared domain class (defaulting to false) and this entity overrides.
    ).flatten ++ predicates(eo) ++ members.toList

    s"new {\n${fields.mkString("\n")}\n}"
  }

  /** A JSON attribute as a Pkl `(type, literal)` pair, or None when there is no
    * faithful representation (an object, a mixed array, an explicit null).
    *
    * Dropping the unrepresentable is the honest move: a property is declared
    * only when its type can be stated, so an author never meets a field whose
    * type is a guess. A `null` in particular means HA reported the attribute
    * with no value, which is indistinguishable from not having it.
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

  /** Render a generated identifier, backticked only when necessary. Delegates
    * to Pkl's own lexer (pkl-parser, version-locked to pkl-core) so the keyword
    * set and identifier grammar cannot drift from the evaluator: `kitchen`
    * stays plain, `new`/`override`/`3rd_floor` come back quoted.
    */
  private def tick(name: String): String =
    org.pkl.parser.Lexer.maybeQuoteIdentifier(name)

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
