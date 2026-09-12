# The component library — analogue of lib/components.pkl.
#
# STATIC TYPES (`:`), annotated at MODULE level so the types survive `import`.
# The annotation must be the OUTERMOST expression — `(let … in {…}) : T`, not
# `let … in ({…} : T)`, which parses as the annotation being inside the let and
# leaves the module's apparent type `Dyn`. That cost an hour; see
# typecheck/module-annotation-must-be-outermost.ncl.
#
# No contracts here. A non-slidable domain fails on the `sliderSpec` lookup,
# which is the runtime validation; `hass.ncl` has the contract form of the same
# check for when it is wanted at the boundary.
#
# Every entity parameter is ROW-POLYMORPHIC (`; a`), so a component accepts any
# entity that carries the fields it needs, whatever else the dump gave it. That
# is what lets per-entity dump types and fixed component signatures compose
# without inlining each entity's shape at every call site.
#
# Types cannot be NAMED in Nickel (naming one moves it into the contract world),
# so each signature spells its structure out. The module-level annotation block
# below keeps that to one place per module rather than one per definition.
#
# Every component returns an already-PLACED node (`{ cell, body }`) — see
# core.ncl for why placement is separate.
((
  let core = import "core.ncl" in
  {
  sliderSpec = {
    light = { action = "light/turn_on", key = "brightness", min = 1, max = 255, value = "brightness" },
    cover = { action = "cover/set_cover_position", key = "position", min = 0, max = 100, value = "current_position" },
    fan = { action = "fan/set_percentage", key = "percentage", min = 0, max = 100, value = "percentage" },
  },

  # Pkl writes this union directly and dispatches with `on is hass.ColourTemp`.
  # Here it is a tagged ADT, which the typechecker checks exhaustively: dropping
  # the `'Axis` arm below is a `nickel typecheck` error, not a runtime surprise.
  slider = match {
    'Entity entity =>
      let spec = sliderSpec."%{entity.domain}" in
      core.place {
        kind = "component",
        card = "slider",
        readout = "state",
        children = [],
        slots = {
          entity_id = entity.entity_id,
          value = "$attr.%{spec.value}",
          action = spec.action,
          key = spec.key,
          min = std.string.from_number spec.min,
          max = std.string.from_number spec.max,
        } : { _ : String },
      },

    'Axis axis =>
      core.place {
        kind = "component",
        card = "slider",
        readout = "state",
        children = [],
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

  # Rebuilt field by field rather than merged: `&` is untyped, so an amend
  # cannot appear here at all.
  sliderGroup = fun axis members =>
    let head = (slider axis).body in
    core.place {
      kind = head.kind,
      card = head.card,
      readout = head.readout,
      slots = head.slots,
      children = std.array.map (fun m => (slider m | Dyn)) members,
    },

  button = fun label action =>
    core.place {
      kind = "component",
      card = "button",
      readout = "none",
      children = [],
      slots = { include label, onclick = action } : { _ : String },
    },

  toggle = fun entity =>
    core.place {
      kind = "component",
      card = "toggle",
      readout = "none",
      children = [],
      slots = {
        entity_id = entity.entity_id,
        label = entity.friendly_name,
        onclick = "homeassistant/toggle",
      } : { _ : String },
    },
}) : {
  # A dictionary type, so the `sliderSpec."%{domain}"` lookup is typed.
  sliderSpec : { _ : { action : String, key : String, min : Number, max : Number, value : String } },

  slider : forall a b.
    [| 'Entity { entity_id : String, domain : String; a },
       'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; b } } |]
    -> { cell : { classes : Array String },
         body : { kind : String, card : String, readout : String,
                  children : Array Dyn, slots : { _ : String } } },

  sliderGroup : forall a b.
    [| 'Entity { entity_id : String, domain : String; a },
       'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; b } } |]
    -> Array [| 'Entity { entity_id : String, domain : String; a },
                'Axis { min_kelvin : Number, max_kelvin : Number, owner : { entity_id : String; b } } |]
    -> { cell : { classes : Array String },
         body : { kind : String, card : String, readout : String,
                  children : Array Dyn, slots : { _ : String } } },

  button : String -> String
    -> { cell : { classes : Array String },
         body : { kind : String, card : String, readout : String,
                  children : Array Dyn, slots : { _ : String } } },

  toggle : forall a. { entity_id : String, friendly_name : String; a }
    -> { cell : { classes : Array String },
         body : { kind : String, card : String, readout : String,
                  children : Array Dyn, slots : { _ : String } } },
})
