# The one interpreter: a tree becomes a FLAT node table.
#
# The carrier has to be non-recursive, because `r` is a type variable and a
# nested node record would contain itself -- instantiating `r` at a recursive
# shape does not merely fail, it CRASHES the typechecker with a stack overflow
# (../renderB.ncl). Flat is what makes the whole encoding typeable end to end.
#
# `r = String -> { root, nodes }`: a node is a function from its PATH to its own
# id plus every node beneath it. Ids are structural, so they are stable across
# renders -- which is what a fragment-patching renderer wants anyway.
({
  render = fun t =>
    let self = fun id b children candidates conditions members => {
      # `include`, not `id = id`: records are recursive, so `field = param` is a
      # self-reference. The typechecker does NOT catch it -- this file passed
      # `nickel typecheck` and failed at eval with "infinite recursion".
      include id,
      kind = b.kind,
      card = b.card,
      readout = b.readout,
      classes = b.cell.classes,
      slots = b.slots,
      include children,
      include candidates,
      where = conditions,
      include members,
    }
    in
    # A bare `{}` would infer as the CLOSED empty record and pin `self`'s
    # parameter to it, so the set branch could no longer pass a dictionary.
    let noMembers : { _ : Array { when : String, node : String } } = {} in
    t {
      node = fun b => fun path => { root = path, nodes = [self path b [] [] [] noMembers] },

      group = fun b kids => fun path =>
        let rs =
          std.array.generate
            (fun i => (std.array.at i kids) "%{path}/%{std.string.from_number i}")
            (std.array.length kids)
        in
        { root = path,
          nodes =
            [self path b (std.array.map (fun r => r.root) rs) [] [] noMembers]
            @ std.array.flatten (std.array.map (fun r => r.nodes) rs) },

      set = fun header cases => fun path =>
        let entries = std.record.to_array cases in
        let rendered =
          std.array.map
            (fun kv => {
              key = kv.field,
              rs =
                std.array.generate
                  (fun i =>
                    let c = std.array.at i kv.value in
                    { when = c.when,
                      out = c.node "%{path}/%{kv.field}/%{std.string.from_number i}" })
                  (std.array.length kv.value),
            })
            entries
        in
        { root = path,
          nodes =
            [self
              path
              { cell = { classes = [] }, kind = "set", card = "", readout = "none", slots = {} }
              []
              header.candidates
              header.where
              (std.array.fold_left
                (fun acc e =>
                  std.record.insert
                    e.key
                    (std.array.map (fun x => { when = x.when, node = x.out.root }) e.rs)
                    acc)
                ({} : { _ : Array { when : String, node : String } })
                rendered)]
            @ std.array.flatten
                (std.array.map (fun e => std.array.flatten (std.array.map (fun x => x.out.nodes) e.rs)) rendered) },
    },
} : {
  render :
    (forall r.
      { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
        group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
        set : { candidates : Array String, where : Array String }
              -> { _ : Array { when : String, node : r } }
              -> r }
      -> r)
    -> (String
        -> { root : String,
             nodes : Array { id : String, kind : String, card : String, readout : String,
                             classes : Array String, slots : { _ : String },
                             children : Array String, candidates : Array String,
                             where : Array String,
                             members : { _ : Array { when : String, node : String } } } }),
})
