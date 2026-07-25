package fh.view.testkit

import api.homeassistant.ws.domain.EntitiesEvent
import fh.view.runtime.EntityState
import io.circe.Json
import perok.ha.{EntityId, GetStatesData, GetStatesDataAttributes}
import smithy4s.Document

/** One entity in a test fixture: its id, current `state`, and full attribute
  * map — the SAME shape the runtime's [[EntityState]] carries, but as a plain,
  * static value a test can declare inline.
  *
  * This is the single source of truth for a fixture entity. It renders to every
  * face Home Assistant presents to the runtime — the compressed feed's full state
  * ([[toFeedEntry]]) and delta ([[deltaFrom]]), the `/api/states` snapshot
  * ([[toGetStatesData]]) and the authoring dump row ([[toDumpEntry]]) — so "the
  * state the dashboard was built against" and "the live state the runtime serves"
  * are derived from one declaration and cannot drift.
  */
case class FixtureEntity(
    entityId: String,
    state: String,
    attributes: Map[String, Json] = Map.empty
) {

  /** The runtime value this entity should seed to — used both as a convenience
    * for assertions and as the oracle for the seed round-trip test.
    */
  def toEntityState: EntityState =
    EntityState(entityId, state, attributes)

  /** This entity as one `/api/states` row. `friendly_name`/`device_class` go
    * into the typed attribute fields (as the real HA payload has them, and as
    * [[fh.view.runtime.StateStore.seed]] lifts them back out); every other
    * attribute rides in `unknown`. State is always a string in `/api/states`.
    */
  def toGetStatesData: GetStatesData = {
    val friendly = attributes.get("friendly_name").flatMap(_.asString)
    val deviceClass = attributes.get("device_class").flatMap(_.asString)
    val rest = attributes.removed("friendly_name").removed("device_class")
    GetStatesData(
      entity_id = EntityId(entityId),
      state = Document.fromString(state),
      attributes = GetStatesDataAttributes(
        friendly_name = friendly,
        device_class = deviceClass,
        unknown = Some(rest.view.mapValues(FixtureEntity.jsonToDocument).toMap)
      )
    )
  }

  /** The entity's domain — the segment before the first `.` of its id. */
  def domain: String = entityId.takeWhile(_ != '.')

  /** A Pkl-safe key for this entity in a generated `lib/dump.pkl` — the id with
    * every non-alphanumeric character folded to `_` (so `dump.entities.<key>`
    * is a legal dotted access). Matches the sanitizing `DataDump.transform`
    * does.
    */
  def dumpKey: String = entityId.replaceAll("[^A-Za-z0-9]", "_")

  /** This entity as one row of a [[fh.view.build.DataDump.transform]] output
    * object — the shape [[fh.view.build.PklDump.render]] consumes to emit the
    * typed `lib/dump.pkl`. `entity_id`/`domain`/`friendly_name` are lifted to
    * top-level fields (where `PklDump` reads them); the remaining attributes
    * ride under `attributes` (from which `PklDump` picks only registry facts
    * like `color_mode`).
    *
    * Deriving the AUTHORING dump from the same [[FixtureEntity]] the runtime
    * SERVES is what keeps "the entities the dashboard was built against" and
    * "the live state the runtime pushes" from drifting — the single-source-of-
    * truth property the functional suite depends on, now extended to the Pkl
    * (Tier-A) path.
    */
  def toDumpEntry: (String, Json) = {
    val friendly = attributes.get("friendly_name")
    val fields = List(
      "entity_id" -> Json.fromString(entityId),
      "domain" -> Json.fromString(domain),
      "attributes" -> Json.fromFields(attributes.removed("friendly_name"))
    ) ++ friendly.map("friendly_name" -> _)
    dumpKey -> Json.fromFields(fields)
  }

  /** This entity as one entry of a `subscribe_entities` opening (`a`) frame: its
    * complete state, which the store applies as a replacement.
    */
  def toFeedEntry(lastUpdated: Double): (String, EntitiesEvent.Full) =
    entityId -> EntitiesEvent.Full(
      state = state,
      attributes = attributes,
      lastChanged = Some(lastUpdated),
      lastUpdated = Some(lastUpdated)
    )

  /** This entity as a `subscribe_entities` DELTA (`c`) against `prev` — only the
    * fields and attributes that actually moved, plus the names of attributes that
    * went away, exactly as HA sends them. Real deltas are what the store MERGES,
    * so computing a true diff here is what keeps "the fixture map" and "the
    * store's map" identical rather than accidentally accumulating stale
    * attributes.
    */
  def deltaFrom(
      prev: FixtureEntity,
      lastUpdated: Double
  ): EntitiesEvent.Delta = {
    val changedAttrs = attributes.filter { case (k, v) =>
      !prev.attributes.get(k).contains(v)
    }
    val dropped = (prev.attributes.keySet -- attributes.keySet).toList
    EntitiesEvent.Delta(
      plus = Some(
        EntitiesEvent.Patch(
          state = Option.when(state != prev.state)(state),
          attributes = changedAttrs,
          lastUpdated = Some(lastUpdated)
        )
      ),
      minus = Option.when(dropped.nonEmpty)(EntitiesEvent.Unset(dropped))
    )
  }
}

object FixtureEntity {

  /** A strictly-increasing feed timestamp (epoch seconds) for the nth emit, so a
    * live change always reads as newer than the opening full set (`tick` 0) and
    * than any earlier emit — the recency guard in
    * [[fh.view.runtime.StateStore]] drops anything not newer.
    */
  def epochAt(tick: Long): Double = tick.toDouble

  /** Convert a circe [[Json]] to a smithy4s [[Document]] for building
    * `GetStatesData` fixtures. Numbers go through `BigDecimal` so a value
    * survives the fixture -> `GetStatesData` -> seed round-trip.
    */
  def jsonToDocument(j: Json): Document =
    j.fold(
      Document.nullDoc,
      b => Document.fromBoolean(b),
      n =>
        Document.fromBigDecimal(
          n.toBigDecimal.getOrElse(BigDecimal(n.toDouble))
        ),
      s => Document.fromString(s),
      arr => Document.array(arr.map(jsonToDocument)),
      obj =>
        Document.obj(obj.toList.map { case (k, v) => k -> jsonToDocument(v) })
    )
}
