package fh.view.testkit

import io.circe.Json

/** A small, cross-domain house that stands in for a live Home Assistant in
  * end-to-end tests — the "static config containing the state of a live system"
  * the functional suite drives.
  *
  * Kept deliberately small (readable at a glance) but spanning the render paths
  * that matter: an on light with brightness, an off light (exercises the
  * off/absent-brightness default), a numeric sensor with a unit, a climate
  * entity, a binary sensor (dynamic-group membership), and a generic media
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

  /** Every entity, as the seed a [[FakeHomeAssistant]] starts from. */
  val all: List[FixtureEntity] =
    List(
      kitchenLight,
      livingRoomLight,
      outsideTemp,
      hallwayClimate,
      frontDoor,
      tv
    )
}
