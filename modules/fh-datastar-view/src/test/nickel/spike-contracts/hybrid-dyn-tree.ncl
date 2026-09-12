# The other half of "no middle design": type the components statically and
# leave the tree combinators `Dyn -> Dyn`, so the module keeps a static type.
#
# The component call IS still typechecked -- a wrong entity here is caught
# before eval, which is the good half. But `Dyn` is not a top type in argument
# position: passing a typed value into a `Dyn` parameter needs an explicit
# contract application, so every child gets a `| Dyn`.
#
# That is precisely the shape of ../dashboard.ncl and its six `| Dyn`, and each
# one is a hole where checking AND hover stop. This file typechecks with them
# and fails without them; ./claims.sh asserts both directions.
let lib =
  ({
    toggle = fun e => { kind = "toggle", slots = { label = e.friendly_name } },
    # The library pays it too: a `Dyn` RETURN needs the application as well.
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
(lib.group [lib.group [lib.leaf (lib.toggle bulb | Dyn)] | Dyn] : Dyn)
