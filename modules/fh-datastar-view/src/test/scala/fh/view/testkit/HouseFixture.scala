package fh.view.testkit

import io.circe.Json

/** A small cross-domain house standing in for a live HA, spanning the render
  * paths that matter: an on light with brightness, an off one (the
  * absent-brightness default), a sensor with a unit, climate, a binary sensor,
  * and a media player (the domain fallback).
  */
object HouseFixture {

  private def s(v: String): Json = Json.fromString(v)
  private def n(v: Int): Json = Json.fromInt(v)
  private def d(v: Double): Json = Json.fromDoubleOrNull(v)

  val kitchenLight: FixtureEntity = FixtureEntity(
    "light.kitchen",
    "on",
    Map(
      "friendly_name" -> s("Kitchen"),
      "brightness" -> n(180)
    )
  )

  val livingRoomLight: FixtureEntity = FixtureEntity(
    "light.living_room",
    "off",
    Map("friendly_name" -> s("Living Room"))
  )

  val outsideTemp: FixtureEntity = FixtureEntity(
    "sensor.outside_temp",
    "12.4",
    Map(
      "friendly_name" -> s("Outside Temperature"),
      "device_class" -> s("temperature"),
      "unit_of_measurement" -> s("°C")
    )
  )

  val hallwayClimate: FixtureEntity = FixtureEntity(
    "climate.hallway",
    "heat",
    Map(
      "friendly_name" -> s("Hallway"),
      "current_temperature" -> d(19.5),
      "temperature" -> d(21.0)
    )
  )

  val frontDoor: FixtureEntity = FixtureEntity(
    "binary_sensor.front_door",
    "off",
    Map(
      "friendly_name" -> s("Front Door"),
      "device_class" -> s("door")
    )
  )

  val tv: FixtureEntity = FixtureEntity(
    "media_player.tv",
    "paused",
    Map("friendly_name" -> s("Living Room TV"))
  )

  /** Can release its latch (`LockEntityFeature.OPEN`), the one thing
    * `hass.LockEntity` models beyond the domain.
    */
  val frontLock: FixtureEntity = FixtureEntity(
    "lock.front_door",
    "locked",
    Map(
      "friendly_name" -> s("Front Door Lock"),
      "supported_features" -> n(1),
      "changed_by" -> s("keypad")
    )
  )

  /** An appliance as HA models one: a handful of `sensor` entities, the three
    * `c.progress` takes. The dishwasher's shape, which has a denominator,
    * because the bar has pixels to check; the no-total shape drops the bar and
    * is a Pkl fact.
    */
  val washerRemaining: FixtureEntity = FixtureEntity(
    "sensor.washer_remaining",
    "47",
    Map(
      "friendly_name" -> s("Washing Machine"),
      "device_class" -> s("duration"),
      "unit_of_measurement" -> s("min")
    )
  )

  val washerProgram: FixtureEntity = FixtureEntity(
    "sensor.washer_program_duration",
    "120",
    Map(
      "friendly_name" -> s("Washing Machine Programme"),
      "device_class" -> s("duration"),
      "unit_of_measurement" -> s("min")
    )
  )

  /** The Electrolux integration declares no `device_class`, and the card
    * renders the words uninterpreted.
    */
  val washerStatus: FixtureEntity = FixtureEntity(
    "sensor.washer_status",
    "Rinsing",
    Map("friendly_name" -> s("Washing Machine Status"))
  )

  val all: List[FixtureEntity] =
    List(
      kitchenLight,
      livingRoomLight,
      outsideTemp,
      hallwayClimate,
      frontDoor,
      tv,
      frontLock,
      washerRemaining,
      washerProgram,
      washerStatus
    )

  /** [[fh.view.build.RegistryDump.transform]]'s output for the house, which
    * [[fh.view.build.PklDump.render]] turns into the typed dump; no areas or
    * floors. A Tier-A dashboard is authored against it, so it and the served
    * state share one source.
    */
  val transformedDump: Json = dumpWith()

  /** For a shape no shared entity has, without adding it to [[all]], where
    * every set and every seeded fake would see it.
    */
  def dumpWith(extra: FixtureEntity*): Json = Json.obj(
    "areas" -> Json.obj(),
    "floors" -> Json.obj(),
    "entities" -> Json.fromFields((all ++ extra).map(_.toDumpEntry))
  )
}
