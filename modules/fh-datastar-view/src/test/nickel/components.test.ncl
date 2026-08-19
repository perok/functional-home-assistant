# The component library — slider, subsliders, button, toggle, layout.
# Analogue of src/test/pkl/components.test.pkl.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/components.test.ncl
{
  slider_on_an_entity
    | doc m%"
      The slider reads its action/key/range from the domain spec, so the author
      names only the entity.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      (c.slider ('Entity home.entities.light_plug)).slots
      # => { action = "light/turn_on", entity_id = "light.plug", key = "brightness", max = "255", min = "1", value = "$attr.brightness" }
      ```
    "%
    = null,

  slider_on_a_narrowed_axis
    | doc m%"
      Handing the slider a colourTemp AXIS instead of the entity swaps the whole
      wire contract — different key, different range, range taken from the
      device. Pkl does this with `on is hass.ColourTemp`; here the union is a
      tagged ADT and the dispatch is a `match`.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      let s = c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp) in
      [s.slots.key, s.slots.min, s.slots.max]
      # => ["color_temp_kelvin", "2000", "6535"]
      ```
    "%
    = null,

  subsliders_are_children
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      let g = c.sliderGroup ('Entity home.entities.light_plug) ['Entity home.entities.light_hue_bibliotek] in
      [std.array.length g.children, (std.array.at 0 g.children).readout]
      # => [1, 'percent]
      ```
    "%
    = null,

  button_and_toggle
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.button "Close" "closePopup()").slots.label, (c.toggle home.entities.light_plug).slots.label]
      # => ["Close", "Plug"]
      ```
    "%
    = null,

  layout_helpers_return_a_new_node
    | doc m%"
      `fullWidth`/`columns` add a cell class and hand back a node, so they
      compose at the end of a pipe.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.fullWidth (c.toggle home.entities.light_plug)).cell.classes,
       (c.columns 6 (c.toggle home.entities.light_plug)).cell.classes]
      # => [["fh-cols-full"], ["fh-cols-6"]]
      ```
    "%
    = null,

  layout_helpers_also_take_a_query_set
    | doc m%"
      A query's `set` node carries a cell but NO `card`, so the layout helpers
      are annotated `Placeable -> Placeable`, not `Node -> Node`. Annotating
      them the obvious way broke `dashboard.ncl` — the one place in this
      directory where adding a type annotation paid for itself immediately.

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      (c.fullWidth (q.pipe.from home.lights |> q.pipe.build)).cell.classes
      # => ["fh-cols-full"]
      ```
    "%
    = null,

  a_non_slidable_domain_is_an_error
    | doc m%"
      Pkl expresses this as a constrained type
      (`hass.Entity(sliderSpec.containsKey(domain))`); here it is
      `SlidableEntity`, a record contract composed with a predicate via
      `std.contract.all_of`.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider ('Entity home.entities.sensor_kitchen_temp)
      # => error
      ```
    "%
    = null,

  an_untagged_argument_is_rejected_at_the_call
    | doc m%"
      The payoff for tagging: an argument of the wrong SHAPE fails immediately
      with "expected an enum", instead of being sniffed for a field deep inside
      the body and failing somewhere less obvious.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider home.entities.light_plug
      # => error: expected an enum
      ```
    "%
    = null,
}
