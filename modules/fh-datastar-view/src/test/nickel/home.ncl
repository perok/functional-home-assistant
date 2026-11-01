# The GENERATED dump analogue — what codegen would emit.
#
# STATIC TYPES, annotated at module level. That annotation is what makes an
# importing dashboard's calls typecheck: an unannotated module has apparent type
# `Dyn`, and a static block cannot reach into it.
#
# The load-bearing decision is unchanged from the contract version — each entity
# gets its OWN type, naming exactly the fields it has — but a type does the job
# a per-entity contract was doing AND is visible to the typechecker. Contracts
# buy nothing here, and the two cannot be mixed on one value anyway.
#
# `lights` is the house-wide list a query starts from. An `Array` needs ONE
# element type, so it carries the COMMON projection — which is the right answer:
# a query over every light should only see what every light has. Per-entity
# capabilities stay reachable through `entities.<name>`. That is the same
# upcast Pkl gets from subtyping, done by hand because codegen writes it.
({
  entities = {
    light_hue_bibliotek = {
      entity_id = "light.hue_bibliotek",
      domain = "light",
      friendly_name = "Bibliotek",
      area_id = "library",
      supported_color_modes = ['color_temp],
      # PRESENT on this entity, so it is in this entity's type.
      colourTemp = {
        min_kelvin = 2000,
        max_kelvin = 6535,
        owner = { entity_id = "light.hue_bibliotek", domain = "light" },
      },
    },

    light_plug = {
      entity_id = "light.plug",
      domain = "light",
      friendly_name = "Plug",
      # area_id / colourTemp: absent entirely, not `optional` and not null
    },

    sensor_kitchen_temp = {
      entity_id = "sensor.kitchen_temp",
      domain = "sensor",
      friendly_name = "Kitchen temperature",
      device_class = "temperature",
      unit_of_measurement = "°C",
    },
  },

  lights = [
    { entity_id = "light.hue_bibliotek", domain = "light", friendly_name = "Bibliotek" },
    { entity_id = "light.plug", domain = "light", friendly_name = "Plug" },
  ],
  sensors = [
    { entity_id = "sensor.kitchen_temp", domain = "sensor", friendly_name = "Kitchen temperature" },
  ],
  switches = [],
} : {
  entities : {
    light_hue_bibliotek : {
      entity_id : String,
      domain : String,
      friendly_name : String,
      area_id : String,
      supported_color_modes : Array [| 'unknown, 'onoff, 'brightness, 'color_temp,
                                       'hs, 'xy, 'rgb, 'rgbw, 'white |],
      colourTemp : { min_kelvin : Number,
                     max_kelvin : Number,
                     owner : { entity_id : String, domain : String } },
    },

    light_plug : { entity_id : String, domain : String, friendly_name : String },

    sensor_kitchen_temp : {
      entity_id : String,
      domain : String,
      friendly_name : String,
      device_class : String,
      unit_of_measurement : String,
    },
  },

  lights : Array { entity_id : String, domain : String, friendly_name : String },
  sensors : Array { entity_id : String, domain : String, friendly_name : String },
  switches : Array { entity_id : String, domain : String, friendly_name : String },
})
