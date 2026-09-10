package fh.view.build

/** The vendored HA string enums the DUMP GENERATOR has to know, mirroring the
  * `typealias` unions in the `dashboards/lib/hass/` modules.
  *
  * Two copies of one list is the thing this codebase normally refuses, so the
  * reason for it: the Pkl union is what gives an author a typo error and
  * editor completion, and it has to live in the lib because that is what a
  * dashboard imports. The generator needs the same set BEFORE any Pkl runs, to
  * decide whether assigning a device class would produce a dump that cannot
  * evaluate (see `PklDump.deviceClassField`). Neither side can read the other
  * at the moment it needs the answer.
  *
  * So they are kept honest by test instead of by construction:
  * `HassVocabularySuite` parses the unions straight out of the vendored `.pkl`
  * sources and asserts set equality with these. Re-syncing a domain against a
  * newer HA release means editing both, and the suite is what says so.
  */
private[build] object HassVocabulary {

  /** `SensorDeviceClass` — `hass/sensor.pkl`. */
  val SensorDeviceClasses: Set[String] = Set(
    "absolute_humidity",
    "apparent_power",
    "aqi",
    "area",
    "atmospheric_pressure",
    "battery",
    "blood_glucose_concentration",
    "co",
    "co2",
    "conductivity",
    "current",
    "data_rate",
    "data_size",
    "date",
    "distance",
    "duration",
    "energy",
    "energy_distance",
    "energy_storage",
    "enum",
    "frequency",
    "gas",
    "humidity",
    "illuminance",
    "irradiance",
    "moisture",
    "monetary",
    "nitrogen_dioxide",
    "nitrogen_monoxide",
    "nitrous_oxide",
    "ozone",
    "ph",
    "pm1",
    "pm10",
    "pm25",
    "pm4",
    "power",
    "power_factor",
    "precipitation",
    "precipitation_intensity",
    "pressure",
    "radon",
    "reactive_energy",
    "reactive_power",
    "signal_strength",
    "sound_pressure",
    "speed",
    "sulphur_dioxide",
    "temperature",
    "temperature_delta",
    "timestamp",
    "uptime",
    "volatile_organic_compounds",
    "volatile_organic_compounds_parts",
    "voltage",
    "volume",
    "volume_flow_rate",
    "volume_storage",
    "water",
    "weight",
    "wind_direction",
    "wind_speed"
  )

  /** `BinarySensorDeviceClass` — `hass/binary_sensor.pkl`. */
  val BinarySensorDeviceClasses: Set[String] = Set(
    "battery",
    "battery_charging",
    "co",
    "cold",
    "connectivity",
    "door",
    "garage_door",
    "gas",
    "heat",
    "light",
    "lock",
    "moisture",
    "motion",
    "moving",
    "occupancy",
    "opening",
    "plug",
    "power",
    "presence",
    "problem",
    "running",
    "safety",
    "smoke",
    "sound",
    "tamper",
    "update",
    "vibration",
    "window"
  )
}
