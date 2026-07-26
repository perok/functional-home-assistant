package api.homeassistant.ws.domain

import io.circe.{Decoder, Json}

/** One `subscribe_entities` frame: HA's compressed state feed.
  *
  * This is the command the HA frontend itself uses, and it makes the full
  * snapshot and the change feed ONE subscription — the first frame carries
  * every entity ([[added]]), every later frame carries only what moved. That
  * removes the gap a separate `get_states` + `subscribe_events state_changed`
  * pair has by construction (a change landing between the snapshot and the
  * subscription is lost), which is why it replaces both.
  *
  * Field names are HA's single letters, kept verbatim in the wire types and
  * given readable names here. The command is absent from the WebSocket API docs
  * (which cover `subscribe_events`/`subscribe_trigger` only), but the format is
  * pinned by readable source on BOTH ends:
  *   - producer — core's `websocket_api/messages.py`: `ENTITY_EVENT_ADD/REMOVE/
  *     CHANGE` = `a`/`r`/`c`, `STATE_DIFF_ADDITIONS/REMOVALS` = `+`/`-`; and
  *     `homeassistant/const.py`: `COMPRESSED_STATE_*` = `s`/`a`/`c`/`lc`/`lu`.
  *   - consumer — `home-assistant-js-websocket` (`lib/entities.ts`):
  *     `StatesUpdates`/`EntityDiff`, whose apply step is
  *     `Object.assign(attributes, toAdd.a)` then delete `toRemove.a` — i.e.
  *     attributes MERGE, which is what [[EntitiesEvent.Delta]] reproduces.
  *
  * `EntitiesFeedSuite` pins it further against frames captured from a live
  * instance (2026.7.2), since neither source is a stability promise.
  */
case class EntitiesEvent(
    /** `a` — full state, replacing whatever is stored. The whole entity set on
      * the first frame after subscribing (also after a reconnect, which is what
      * makes re-subscribing the catch-up mechanism).
      */
    added: Map[String, EntitiesEvent.Full] = Map.empty,
    /** `c` — a per-entity DELTA. Attributes merge; see [[EntitiesEvent.Delta]].
      */
    changed: Map[String, EntitiesEvent.Delta] = Map.empty,
    /** `r` — entities that no longer exist. */
    removed: List[String] = Nil
)

object EntitiesEvent {

  /** The feed's `c` (context: who/what caused the change — `{id, user_id,
    * parent_id}`, or a bare id string) is DROPPED, not missing. It never
    * travels alone (246 delta payloads observed, none context-only), so
    * ignoring it cannot lose a change; nothing here renders attribution; and it
    * changes on every single update, so carrying it would just be another
    * always-differing field for `EntityState.sameContent` to ignore. Decode it
    * if we ever want "changed by" — note the `Context | string` union.
    */

  /** An entity's complete state. `lastUpdated` is absent when it equals
    * `lastChanged` (HA omits the duplicate), so read it as
    * `lastUpdated orElse lastChanged`. Timestamps are epoch seconds as a float
    * — NOT the ISO strings `state_changed` used.
    */
  case class Full(
      state: String,
      attributes: Map[String, Json] = Map.empty,
      lastChanged: Option[Double] = None,
      lastUpdated: Option[Double] = None
  )

  /** What changed about one entity: `plus` holds only the fields that moved and
    * only the attributes that moved (so attributes MERGE into the stored map,
    * they do not replace it), `minus` names attributes that went away. An
    * absent `state` means only attributes/timestamps changed.
    */
  case class Delta(
      plus: Option[Patch] = None,
      minus: Option[Unset] = None
  )

  case class Patch(
      state: Option[String] = None,
      attributes: Map[String, Json] = Map.empty,
      lastChanged: Option[Double] = None,
      lastUpdated: Option[Double] = None
  )

  case class Unset(attributes: List[String] = Nil)

  private def attrs(c: io.circe.HCursor, key: String) =
    c.getOrElse[Map[String, Json]](key)(Map.empty)

  given Decoder[Full] = Decoder.instance(c =>
    for {
      s <- c.get[String]("s")
      a <- attrs(c, "a")
      lc <- c.get[Option[Double]]("lc")
      lu <- c.get[Option[Double]]("lu")
    } yield Full(s, a, lc, lu)
  )

  given Decoder[Patch] = Decoder.instance(c =>
    for {
      s <- c.get[Option[String]]("s")
      a <- attrs(c, "a")
      lc <- c.get[Option[Double]]("lc")
      lu <- c.get[Option[Double]]("lu")
    } yield Patch(s, a, lc, lu)
  )

  given Decoder[Unset] = Decoder.instance(
    _.getOrElse[List[String]]("a")(Nil).map(Unset(_))
  )

  given Decoder[Delta] = Decoder.instance(c =>
    for {
      plus <- c.get[Option[Patch]]("+")
      minus <- c.get[Option[Unset]]("-")
    } yield Delta(plus, minus)
  )

  given Decoder[EntitiesEvent] = Decoder.instance(c =>
    for {
      a <- c.getOrElse[Map[String, Full]]("a")(Map.empty)
      ch <- c.getOrElse[Map[String, Delta]]("c")(Map.empty)
      r <- c.getOrElse[List[String]]("r")(Nil)
    } yield EntitiesEvent(a, ch, r)
  )
}
