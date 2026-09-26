package fh.view.testkit

import api.homeassistant.ws.domain.EntitiesEvent
import fh.view.runtime.EntityState
import io.circe.Json

/** A fixture entity, and the single source of every face HA presents: the
  * feed's full state ([[toFeedEntry]]) and delta ([[deltaFrom]]), and the
  * authoring dump row ([[toDumpEntry]]). So the state a dashboard was built
  * against and the state served cannot drift.
  */
case class FixtureEntity(
    entityId: String,
    state: String,
    attributes: Map[String, Json] = Map.empty
) {

  /** Also the oracle for the seed round-trip test. */
  def toEntityState: EntityState =
    EntityState(entityId, state, attributes)

  def domain: String = entityId.takeWhile(_ != '.')

  /** Matches `RegistryDump.transform`'s sanitizing, so `dump.entities.<key>` is
    * a legal access.
    */
  def dumpKey: String = entityId.replaceAll("[^A-Za-z0-9]", "_")

  /** One row of [[fh.view.build.RegistryDump.transform]]'s output, which
    * [[fh.view.build.PklDump.render]] consumes.
    * `entity_id`/`domain`/`friendly_name` are top level; the rest ride under
    * `attributes`, from which `PklDump` picks registry facts like `color_mode`.
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

  /** Its complete state, which the store applies as a replacement. */
  def toFeedEntry(lastUpdated: Double): (String, EntitiesEvent.Full) =
    entityId -> EntitiesEvent.Full(
      state = state,
      attributes = attributes,
      lastChanged = Some(lastUpdated),
      lastUpdated = Some(lastUpdated)
    )

  /** Only what moved, plus the attributes that went away, as HA sends them. The
    * store merges deltas, so a true diff keeps its map identical to the
    * fixture's.
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

  /** Strictly increasing, since [[fh.view.runtime.StateStore]]'s recency guard
    * drops anything not newer.
    */
  def epochAt(tick: Long): Double = tick.toDouble
}
