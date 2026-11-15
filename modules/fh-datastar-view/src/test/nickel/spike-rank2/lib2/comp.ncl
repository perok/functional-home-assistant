# Components return a flat `Body`. The tree does not appear in a single
# signature here -- compare lib/components.ncl, where every return type carries
# `children : Array Dyn`, and `sliderGroup` needs a second `| Dyn` per child.
#
# The slider's tagged ADT survives the encoding because its payloads are plain
# entity records, not trees. An ADT carrying a tree does NOT work; see core.ncl.
((
  let core = import "core.ncl" in
  {
    sliderSpec = {
      light = { action = "light/turn_on", key = "brightness", min = 1, max = 255, value = "brightness" },
      cover = { action = "cover/set_cover_position", key = "position", min = 0, max = 100, value = "current_position" },
      fan = { action = "fan/set_percentage", key = "percentage", min = 0, max = 100, value = "percentage" },
    },

    slider = match {
      'Entity entity =>
        let spec = sliderSpec."%{entity.domain}" in
        {
          cell = { classes = [] },
          kind = "component",
          card = "slider",
          readout = "state",
          slots = {
            entity_id = entity.entity_id,
            value = "$attr.%{spec.value}",
            action = spec.action,
            key = spec.key,
            min = std.string.from_number spec.min,
            max = std.string.from_number spec.max,
          } : { _ : String },
        },

      'Axis axis => {
        cell = { classes = [] },
        kind = "component",
        card = "slider",
        readout = "state",
        slots = {
          entity_id = axis.owner.entity_id,
          value = "$attr.color_temp_kelvin",
          action = "light/turn_on",
          key = "color_temp_kelvin",
          min = std.string.from_number axis.min_kelvin,
          max = std.string.from_number axis.max_kelvin,
        } : { _ : String },
      },
    },

    toggle = fun entity => {
      cell = { classes = [] },
      kind = "component",
      card = "toggle",
      readout = "none",
      slots = {
        entity_id = entity.entity_id,
        label = entity.friendly_name,
        onclick = "homeassistant/toggle",
      } : { _ : String },
    },

    button = fun label action => {
      cell = { classes = [] },
      kind = "component",
      card = "button",
      readout = "none",
      slots = { include label, onclick = action } : { _ : String },
    },

    grid = { cell = { classes = [] }, kind = "grid", card = "", readout = "none", slots = {} },

    # A slider with sub-sliders. In lib/components.ncl this needs `| Dyn` per
    # child; here it is an ordinary `group`, and its children are checked.
    sliderGroup = fun head members =>
      core.groupLeaves (slider head) (std.array.map (fun m => slider m) members),
  }
) : {
  sliderSpec : { _ : { action : String, key : String, min : Number, max : Number, value : String } },

  slider : forall a b.
    [| 'Entity { entity_id : String, domain : String; a },
       'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; b } } |]
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  toggle : forall a. { entity_id : String, friendly_name : String; a }
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  button : String -> String
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  grid : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  # The head and the members need INDEPENDENT row variables: sharing one forces
  # every member to carry exactly the head's extra fields, which a real dump
  # never satisfies. The typechecker caught this on the first dashboard.
  sliderGroup : forall a b cc d.
    [| 'Entity { entity_id : String, domain : String; a },
       'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; b } } |]
    -> Array [| 'Entity { entity_id : String, domain : String; cc },
                'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; d } } |]
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),
})
