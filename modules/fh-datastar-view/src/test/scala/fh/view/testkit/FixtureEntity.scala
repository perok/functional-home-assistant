package fh.view.testkit

import api.homeassistant.ws.protocol.server.{Event, ResultContext}
import fh.view.runtime.EntityState
import io.circe.Json
import perok.ha.{EntityId, GetStatesData, GetStatesDataAttributes}
import smithy4s.Document

/** One entity in a test fixture: its id, current `state`, and full attribute
  * map — the SAME shape the runtime's [[EntityState]] carries, but as a plain,
  * static value a test can declare inline.
  *
  * This is the single source of truth for a fixture entity. It renders to both
  * faces Home Assistant presents to the runtime — the `/api/states` snapshot
  * ([[toGetStatesData]]) and a `state_changed` WebSocket event
  * ([[eventDataState]]) — so "the state the dashboard was built against" and
  * "the live state the runtime serves" are derived from one declaration and
  * cannot drift.
  */
final case class FixtureEntity(
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

  /** This entity as the `new_state`/`old_state` payload of a `state_changed`
    * event. The runtime's `applyEvent` reads only `state` (a `Json`) and the
    * full `attributes` map, so the timestamps/context are inert filler.
    */
  def eventDataState: Event.EventDataState =
    Event.EventDataState(
      entity_id = entityId,
      state = Json.fromString(state),
      attributes = attributes,
      last_changed = FixtureEntity.Epoch,
      last_reported = FixtureEntity.Epoch,
      last_updated = FixtureEntity.Epoch,
      context = FixtureEntity.emptyContext
    )
}

object FixtureEntity {

  private val Epoch = "1970-01-01T00:00:00+00:00"
  private val emptyContext = ResultContext("test", None, None)

  /** Convert a circe [[Json]] to a smithy4s [[Document]] — the inverse of
    * `api.DocumentJson.decoder`. Numbers go through `BigDecimal`, matching that
    * decoder's `DNumber -> Json.fromBigDecimal`, so a value survives the
    * fixture -> `GetStatesData` -> seed round-trip.
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
