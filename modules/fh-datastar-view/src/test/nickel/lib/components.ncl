# The component library — analogue of lib/components.pkl.
#
# NO `Dyn` in any signature here. The one that needed it — `slider`, whose
# argument is an entity OR a colour-temperature axis — is an ADT instead; see
# `SliderAxis`. Nickel has no untagged union of record contracts that both
# discriminates and reports failure usefully, so the union is tagged.
#
# Annotated with CONTRACTS (`|`), not static types (`:`). That is forced, not
# chosen: a static type cannot be named, cannot cross a module boundary, and
# cannot describe `&`. See typesystem.test.ncl.
#
# Written as ONE RECORD rather than `let`s with a re-export block: an annotation
# on a `let` does NOT survive `include` into the exported record, and hover
# reports `Dyn` for it. Record fields keep theirs.
let hass = import "hass.ncl" in
let core = import "core.ncl" in
{
  sliderSpec = {
    light = { action = "light/turn_on", key = "brightness", min = 1, max = 255, value = "brightness" },
    cover = { action = "cover/set_cover_position", key = "position", min = 0, max = 100, value = "current_position" },
    fan = { action = "fan/set_percentage", key = "percentage", min = 0, max = 100, value = "percentage" },
  },

  Readout = [| 'state, 'percent, 'none |],

  # The domain check Pkl does with a CONSTRAINED TYPE
  # (`hass.Entity(sliderSpec.containsKey(domain))`) has to become an explicit
  # predicate contract here.
  # `&` does NOT compose a record contract with a custom one ("non mergeable
  # terms" once anything forces it); `all_of` is the combinator that does.
  SlidableEntity =
    std.contract.all_of [
      hass.Entity,
      std.contract.from_predicate (fun e => std.record.has_field e.domain sliderSpec),
    ],

  # Pkl writes this union directly and dispatches with `on is hass.ColourTemp`.
  # Here it is a tagged ADT, which buys an EAGER check of the tag (a mistagged
  # or untagged argument fails at the call, not deep inside the body) and a
  # `match` instead of sniffing for a field with `std.record.has_field`.
  # It costs a tag at every call site: `c.slider ('Axis light.colourTemp)`.
  SliderAxis = [| 'Entity SlidableEntity, 'Axis hass.ColourTemp |],

  columns | Number -> core.Placeable -> core.Placeable = fun n node =>
    node & { cell.classes = node.cell.classes @ ["fh-cols-%{std.string.from_number n}"] },

  fullWidth | core.Placeable -> core.Placeable = fun node =>
    node & { cell.classes = node.cell.classes @ ["fh-cols-full"] },

  slider | SliderAxis -> core.Node = match {
    'Entity entity =>
      let spec = sliderSpec."%{entity.domain}" in
      {
        card = "slider",
        readout | Readout | default = 'state,
        label | String | optional,
        slots = {
          entity_id = entity.entity_id,
          value = "$attr.%{spec.value}",
          action = spec.action,
          key = spec.key,
          min = std.string.from_number spec.min,
          max = std.string.from_number spec.max,
        },
      },

    'Axis axis =>
      {
        card = "slider",
        readout | Readout | default = 'state,
        label | String | optional,
        slots = {
          entity_id = axis.owner.entity_id,
          value = "$attr.color_temp_kelvin",
          action = "light/turn_on",
          key = "color_temp_kelvin",
          min = std.string.from_number axis.min_kelvin,
          max = std.string.from_number axis.max_kelvin,
        },
      },
  },

  sliderGroup | SliderAxis -> Array SliderAxis -> core.Node = fun axis members =>
    (slider axis) & { children = std.array.map (fun m => (slider m) & { readout = 'percent }) members },

  button | String -> String -> core.Node = fun label action =>
    { card = "button", slots = { include label, onclick = action } },

  toggle | hass.Entity -> core.Node = fun entity =>
    {
      card = "toggle",
      slots = {
        entity_id = entity.entity_id,
        label = entity.friendly_name,
        onclick = "homeassistant/toggle",
      },
    },
}
