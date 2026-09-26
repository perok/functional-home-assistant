package api.homeassistant.ws.domain

import io.circe.{Decoder, Json}

/** One `subscribe_entities` frame: the first carries every entity, later ones
  * only what moved. Snapshot and feed are one subscription, so no change can
  * land between a `get_states` and a `subscribe_events`.
  *
  * Undocumented. The format is read off core's `websocket_api/messages.py` and
  * `const.py` (`COMPRESSED_STATE_*`), and `home-assistant-js-websocket`'s
  * `lib/entities.ts`, whose apply step merges attributes as
  * [[EntitiesEvent.Delta]] does. `EntitiesFeedSuite` pins frames captured from
  * HA 2026.7.2.
  */
case class EntitiesEvent(
    /** Replaces what is stored. The whole set on the first frame, which makes
      * re-subscribing the reconnect catch-up.
      */
    added: Map[String, EntitiesEvent.Full] = Map.empty,
    changed: Map[String, EntitiesEvent.Delta] = Map.empty,
    removed: List[String] = Nil
)

object EntitiesEvent {

  // A delta's `c` (context) is dropped: it never travels alone (246 deltas
  // observed) and differs on every update. It is `Context | string` if ever
  // decoded.

  /** HA omits `lastUpdated` when it equals `lastChanged`. Timestamps are float
    * epoch seconds, not ISO strings.
    */
  case class Full(
      state: String,
      attributes: Map[String, Json] = Map.empty,
      lastChanged: Option[Double] = None,
      lastUpdated: Option[Double] = None
  )

  /** `plus` holds only what moved, so its attributes merge into the stored map;
    * `minus` names the attributes that went away.
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
