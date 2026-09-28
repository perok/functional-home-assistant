# Components, contract-annotated. Compare ../../spike-rank2/lib2/comp.ncl: these
# return a whole `Node`, not a `Body`, because nothing here has to keep the tree
# out of a component's type. `sliderGroup` is an ordinary nested record again.
let core = import "core.ncl" in
{
  Entity = core.Entity,
  ColourTemp = { min_kelvin | Number, max_kelvin | Number, .. },
  SliderAxis = [| 'Entity Entity, 'Axis ColourTemp |],

  toggle | Entity -> core.Node
    = fun e =>
      core.leaf { kind = "toggle", card = "toggle", slots = { label = e.friendly_name } },

  slider | SliderAxis -> core.Node
    = match {
      'Entity e =>
        core.leaf { kind = "slider", card = "slider", readout = e.entity_id,
                    slots = { label = e.friendly_name } },
      'Axis a =>
        core.leaf { kind = "slider", card = "slider",
                    readout = std.string.from_number a.min_kelvin,
                    slots = { label = "colour temperature" } },
    },

  sliderGroup | SliderAxis -> Array SliderAxis -> core.Node
    = fun head subs =>
      core.group { kind = "sliderGroup", card = "sliderGroup" }
        ([slider head] @ std.array.map slider subs),

  button | String -> String -> core.Node
    = fun label action =>
      # `label = label` would be self-reference: records are recursive. The trap
      # is identical in both designs -- it is a property of Nickel records, not
      # of the typing style.
      core.leaf { kind = "button", card = "button", slots = { include label, include action } },
}
