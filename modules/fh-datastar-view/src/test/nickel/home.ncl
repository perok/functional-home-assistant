# The GENERATED dump analogue — what codegen would emit, in the shape that
# actually works (ADR 0013's job, in Nickel).
#
# The load-bearing decision: one CONTRACT PER ENTITY, emitted next to the
# entity, declaring exactly the fields that entity has. A single shared
# `hass.LightEntity` contract also type-checks, but then editor completion is
# driven by the contract rather than the value, so it offers `colourTemp` on a
# light that has none. Per-entity contracts fix that — see hass.test.ncl and the
# README.
#
# `not_exported` is required on the contracts themselves (they are records in
# this module and would otherwise be serialized) and on the `owner`
# back-pointer, which is infinite when serialized. It is Pkl's `hidden`.
let hass = import "lib/hass.ncl" in
{
  E_light_hue_bibliotek | not_exported = {
    entity_id | String,
    domain | String,
    friendly_name | String,
    area_id | String,
    supported_color_modes | Array hass.ColorMode,
    colourTemp | hass.ColourTemp, # PRESENT: declared, not optional
  },

  E_light_plug | not_exported = {
    entity_id | String,
    domain | String,
    friendly_name | String,
    # area_id / colourTemp: absent entirely, not `optional` and not null
  },

  E_sensor_kitchen_temp | not_exported = {
    entity_id | String,
    domain | String,
    friendly_name | String,
    device_class | String,
    unit_of_measurement | String,
  },

  entities = {
    light_hue_bibliotek =
      {
        entity_id = "light.hue_bibliotek",
        domain = "light",
        friendly_name = "Bibliotek",
        area_id = "library",
        supported_color_modes = ['color_temp],
        colourTemp = {
          min_kelvin = 2000,
          max_kelvin = 6535,
          owner | not_exported = light_hue_bibliotek,
        },
      } | E_light_hue_bibliotek,

    light_plug =
      {
        entity_id = "light.plug",
        domain = "light",
        friendly_name = "Plug",
      } | E_light_plug,

    sensor_kitchen_temp =
      {
        entity_id = "sensor.kitchen_temp",
        domain = "sensor",
        friendly_name = "Kitchen temperature",
        device_class = "temperature",
        unit_of_measurement = "°C",
      } | E_sensor_kitchen_temp,
  },

  # The house-wide lists a query starts from.
  lights = [entities.light_hue_bibliotek, entities.light_plug],
  sensors = [entities.sensor_kitchen_temp],
  switches = [],
}
