# hybrid-dyn-tree.ncl with the `| Dyn` applications removed, and nothing else
# changed. It fails: "Expected an expression of type `Dyn`, Found `{ kind :
# String, slots : { _ : String } }`".
#
# There is no implicit upcast into a dynamic position, which is why the `| Dyn`
# noise cannot simply be deleted from a mixed design.
let lib =
  ({
    toggle = fun e => { kind = "toggle", slots = { label = e.friendly_name } },
    leaf = fun b => ({ body = b } | Dyn),
    group = fun kids => ({ body = { kind = "grid" }, children = kids } | Dyn),
  } : {
    toggle : forall a. { entity_id : String, friendly_name : String; a }
          -> { kind : String, slots : { _ : String } },
    leaf : Dyn -> Dyn,
    group : Array Dyn -> Dyn,
  })
in
let bulb : { entity_id : String, domain : String, friendly_name : String }
  = { entity_id = "light.plug", domain = "light", friendly_name = "Plug" }
in
(lib.group [lib.group [lib.leaf (lib.toggle bulb)]] : Dyn)
