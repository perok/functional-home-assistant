package fh.view.testkit

import io.circe.Json

/** A small, cross-domain house that stands in for a live Home Assistant in
  * end-to-end tests — the "static config containing the state of a live system"
  * the functional suite drives.
  *
  * Kept deliberately small (readable at a glance) but spanning the render paths
  * that matter: an on light with brightness, an off light (exercises the
  * off/absent-brightness default), a numeric sensor with a unit, a climate
  * entity, a binary sensor (candidate-set membership), and a generic media
  * player (the domain fallback).
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

  /** A lock that can also release its latch (`LockEntityFeature.OPEN`), so the
    * one thing `hass.LockEntity` models beyond the domain is exercised rather
    * than defaulted.
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

  /** An appliance mid-cycle, as HA actually models one: not a domain but a
    * handful of `sensor` entities, here the three `c.progress` takes.
    *
    * The pair is deliberately the shape that HAS a denominator — the
    * dishwasher's, not the washer's — because the bar is the part with pixels
    * to check. The washer's no-total shape drops the bar entirely, so it is a
    * Pkl fact rather than a screenshot.
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

  /** No `device_class`: the Electrolux integration declares none, and the card
    * renders these words uninterpreted, so the fixture keeps that shape.
    */
  val washerStatus: FixtureEntity = FixtureEntity(
    "sensor.washer_status",
    "Rinsing",
    Map("friendly_name" -> s("Washing Machine Status"))
  )

  /** Every entity, as the seed a [[FakeHomeAssistant]] starts from. */
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

  /** The whole house as a [[fh.view.build.RegistryDump.transform]] output — the
    * `{ areas, floors, entities }` JSON [[fh.view.build.PklDump.render]] turns
    * into `lib/dump.pkl`. No areas/floors (the fixture entities carry no
    * `area_id`); every entity is one derived row. This is what a Tier-A Pkl
    * dashboard is authored against, so it and the served state share one
    * source.
    */
  val transformedDump: Json = dumpWith()

  /** [[transformedDump]] plus entities the house does not carry — for a fixture
    * that needs a shape no shared entity has (a light with no brightness axis),
    * without adding it to [[all]], where every candidate-set query and every
    * seeded fake would start seeing it too.
    */
  def dumpWith(extra: FixtureEntity*): Json = Json.obj(
    "areas" -> Json.obj(),
    "floors" -> Json.obj(),
    "entities" -> Json.fromFields((all ++ extra).map(_.toDumpEntry))
  )
}
