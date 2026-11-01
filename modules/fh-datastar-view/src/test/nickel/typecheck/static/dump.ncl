# A dump carrying a module-level static type, as codegen could emit it.
({
  entities = {
    light_plug = { entity_id = "light.plug", domain = "light", friendly_name = "Plug" },
  },
} : {
  entities : {
    light_plug : { entity_id : String, domain : String, friendly_name : String },
  },
})
