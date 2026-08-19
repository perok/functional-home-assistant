# Analogue of lib/hass.pkl — the hand-written schema the generated dump is typed against.
{
  ColourTemp = {
    min_kelvin | Number,
    max_kelvin | Number,
    owner | LightEntity,
  },

  # Base entity. Note: record contracts are CLOSED by default in Nickel, so a
  # generated entity carrying extra fields would be rejected -> we need `..`.
  Entity = {
    entity_id | String,
    domain | String,
    friendly_name | String,
    area_id | String | optional,
    ..
  },

  # The narrowing point: colourTemp is OPTIONAL here, and the generated
  # per-entity value either has it or does not.
  LightEntity = Entity & {
    supported_color_modes | Array ColorMode | optional,
    colourTemp | ColourTemp | optional,
    ..
  },

  SensorEntity = Entity & { device_class | String | optional, unit_of_measurement | String | optional, .. },
  SwitchEntity = Entity & { .. },

  ColorMode = [| 'unknown, 'onoff, 'brightness, 'color_temp, 'hs, 'xy, 'rgb, 'rgbw, 'white |],
}
