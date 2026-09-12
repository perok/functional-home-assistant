# The layout tree as its own fold. No recursive type, no `Dyn`.
#
# `r` marks every recursive position, so the type that has to be WRITTEN is not
# recursive -- and Nickel's rank-2 polymorphism accepts it (`group` takes an
# ARRAY of polymorphic trees, which is rank 2). There is no way to name a type,
# so the algebra is spelled out at every occurrence: that is the entire cost of
# the encoding, and it falls on this module, never on a dashboard author.
#
# A node's own data (the `Body` record) is flat and concrete, so components and
# layout helpers never mention the tree. Only `leaf`/`group`/`set` do.
#
# A tree may NOT be carried by an enum variant -- `match` opens the `forall` and
# nothing re-generalises it (see ../enum-rank2.ncl). That is why a query's
# fallback is the last CASE here rather than a `[| 'None, 'Render … |]` payload.
({
  fullWidth = fun b => {
    cell = { classes = b.cell.classes @ ["fh-cols-full"] },
    kind = b.kind,
    card = b.card,
    readout = b.readout,
    slots = b.slots,
  },

  columns = fun n b => {
    cell = { classes = b.cell.classes @ ["fh-cols-%{std.string.from_number n}"] },
    kind = b.kind,
    card = b.card,
    readout = b.readout,
    slots = b.slots,
  },

  leaf = fun b => fun alg => alg.node b,

  group = fun b kids => fun alg => alg.group b (std.array.map (fun k => k alg) kids),

  # `group` takes trees, which can only ever come from an array LITERAL --
  # neither `map` nor `@` will produce polymorphic elements. Anything built by
  # mapping over a list therefore goes through here instead, where `alg` is in
  # scope and the map is monomorphic.
  groupLeaves = fun b leaves => fun alg => alg.group b (std.array.map (fun x => alg.node x) leaves),

  # Members carry a flat `Body`, not a tree. They have to: `map` and `@` both
  # refuse to PRODUCE polymorphic elements (only an array literal does), so a
  # query -- which accumulates -- cannot accumulate trees. Applying `alg.node`
  # here is what makes it work: inside this function `r` is rigid, so the map
  # below is monomorphic. The cost is that a query case renders a leaf.
  set = fun header members => fun alg =>
    alg.set
      header
      (std.record.map
        (fun _k cases => std.array.map (fun c => { when = c.when, node = alg.node c.body }) cases)
        members),
} : {
  fullWidth :
    { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  columns :
    Number
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } },

  leaf :
    { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),

  group :
    { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> Array (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r)
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),

  groupLeaves :
    { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> Array { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } }
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),

  set :
    { candidates : Array String, where : Array String }
    -> { _ : Array { when : String,
                     body : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } }
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),
})
