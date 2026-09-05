package fh.view.build

import cats.syntax.all.*
import io.circe.{Json, JsonObject}

/** Renders the transformed [[RegistryDump]] JSON as the typed `dump.pkl`
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
    * [[RegistryDump.transform]] (objects keyed by sanitized names).
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
    val users = keyed("users")

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

    // The house-wide lists. DECLARED in `@fh-dashboard/internal/dump-base.pkl` (which
    // this module extends) and merely filled here — so a home with no switches
    // answers `List()` rather than "Cannot find property", and the starter
    // dashboard can query them without having seen this dump. `all` is derived
    // there from the four, so it is not emitted.
    //
    // Assignments are omitted where the list is empty: the declared default
    // already says `List()`, and emitting it again is noise in a generated file
    // a person does read.
    val domainLists = {
      def list(name: String, pred: String => Boolean) = {
        val keys = entities.collect {
          case (key, eo) if str(eo, "domain").exists(pred) => tick(s"e_$key")
        }
        Option.when(keys.nonEmpty)(
          s"$name = List(${keys.mkString(", ")})"
        )
      }
      val modelled = Set("light", "sensor", "switch")
      List(
        list("lights", _ == "light"),
        list("sensors", _ == "sensor"),
        list("switches", _ == "switch"),
        list("generic", d => !modelled.contains(d))
      ).flatten.mkString("\n")
    }

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

    // One property per PERSON who can log in, so a dashboard's access rule
    // names a user the way it names an entity — `dump.users.peri` rather than
    // a raw HA id nobody can check (ADR 0023). No per-user CLASS: a user has
    // no members and nothing hangs off it, so the instance is the whole thing.
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
       |///
       |/// EXTENDS the shared base so the house-wide lists (`lights`, `sensors`,
       |/// `switches`, `generic`, `all`) are a declared contract with `List()`
       |/// defaults, not properties this generator has to remember to emit.
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
      case Some("lock")   => "hass.LockEntity"
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
    "volatileAttrs"
  )

  /** Attributes a DOMAIN's schema models itself (as a capability group or a
    * named field), so `capabilityDecls` must not also declare them on the
    * per-entity class. Everything not listed keeps falling through to the
    * per-entity class — that fallback is what lets an unmodeled domain keep
    * working untouched.
    */
  private val SchemaModelled: Map[String, Set[String]] = Map(
    "light" -> Set(
      "supported_color_modes",
      "supported_features",
      "min_color_temp_kelvin",
      "max_color_temp_kelvin",
      "effect_list"
    ),
    // `code_format` is deliberately absent: no shipped card asks for a code, so
    // it stays an ordinary per-entity attribute an author can read (see
    // `hass.LockEntity`).
    "lock" -> Set("supported_features")
  )

  /** The generated class name for one entity. */
  private def entityClass(key: String): String = tick(s"E_$key")

  /** The schema-modelled ASSIGNMENTS for one entity: the raw data its domain
    * class declares and every entity of that domain has.
    *
    * Capability GROUPS are not here — they are narrowed declarations on the
    * entity's own class ([[schemaGroups]]).
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
      case _ => Nil
    }
  }

  /** Each complete capability GROUP the entity reports, as a NARROWED
    * declaration on the entity's own class: the domain class types the group
    * `ColourTemp?`, and the entity that has one re-declares it `ColourTemp`.
    *
    * The narrowing is what lets a dashboard naming a specific entity reach
    * through the group without proving anything —
    * `c.withColourTemp(dump.entities.light_a.colourTemp)`, no `!!` — while
    * generic code over `List<hass.LightEntity>` still meets the nullable type
    * and still has to guard. One name, two views; a `hasColourTemp` twin would
    * be a second name for the same fact (and would read as a Boolean).
    *
    * A group is emitted only when every field it needs is present — a partial
    * one is dropped and reported by [[warnings]]. Pkl would not catch it: a
    * required property with no value is lazy, so a half-filled group evaluates
    * fine until someone reads the missing field, and then blames the class
    * definition rather than the dump.
    */
  private def schemaGroups(key: String, eo: JsonObject): List[String] = {
    val attrs = eo("attributes").flatMap(_.asObject).getOrElse(JsonObject.empty)
    // Every group back-references the entity's own const, so a card given the
    // group alone still knows its subject. Self-referential (the const's class
    // names the const), which is fine: Pkl resolves module-level consts lazily
    // and order-independently — the same property the `members` edges rely on.
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

  /** Generation-time complaints about entities HA reported inconsistently.
    *
    * Codegen is the right place to catch a half-populated capability: we hold
    * the whole picture here, and the alternative is a Pkl error much later
    * pointing at the schema instead of the entity. Reported rather than fatal —
    * one odd integration must not stop the whole house's dump from building.
    */
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
    val modelled =
      SchemaModelled.getOrElse(str(eo, "domain").getOrElse(""), Set.empty)
    attrs.toList
      .filterNot { case (name, _) =>
        ReservedProperties.contains(name) || modelled.contains(name)
      }
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
      // Capability VALUES are not assigned here — they are declared with their
      // value as the default on the entity's OWN class, so `new {}` carries
      // them. Capability PREDICATES are assigned, because they are declared on
      // the shared domain class (defaulting to false) and this entity overrides.
    ).flatten ++ schemaFields(eo) ++ members.toList

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
