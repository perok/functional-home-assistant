# Pipe-style candidate sets. Compare lib/query/pipe.ncl, where the state's
# `filters`, `when`, `render` and `members` are all `Dyn`.
#
# Nothing here is `Dyn` and nothing here is polymorphic: the state holds flat
# `Body` values, so the signatures stay short. That is not free -- it is exactly
# the restriction core.ncl's `set` documents. A query case renders a LEAF.
#
# `otherwise` is the last case rather than a separate `fallback` field: a
# fallback IS a case whose condition always holds, and an ADT cannot carry a
# tree anyway (see ../enum-rank2.ncl).
((
  let core = import "core.ncl" in
  let expr = import "expr.ncl" in
  {
    from = fun entities => { candidates = entities, where = [], cases = [] },

    where = fun pred q => {
      candidates = q.candidates,
      where = q.where @ [expr.toText pred],
      cases = q.cases,
    },

    case_of = fun pred render q => {
      candidates = q.candidates,
      where = q.where,
      cases = q.cases @ [{ when = expr.toText pred, include render }],
    },

    otherwise = fun render q => {
      candidates = q.candidates,
      where = q.where,
      cases = q.cases @ [{ when = "true", include render }],
    },

    build = fun q =>
      core.set
        { candidates = std.array.map (fun e => e.entity_id) q.candidates, where = q.where }
        (std.array.fold_left
          (fun acc e =>
            std.record.insert
              e.entity_id
              (std.array.map (fun c => { when = c.when, body = c.render e }) q.cases)
              acc)
          {}
          q.candidates),
  }
) : {
  from : forall a. Array { entity_id : String; a }
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } },

  where : forall a.
    (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } }
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } },

  case_of : forall a.
    (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> ({ entity_id : String; a }
        -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } })
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } }
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } },

  otherwise : forall a.
    ({ entity_id : String; a }
     -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } })
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } }
    -> { candidates : Array { entity_id : String; a },
         where : Array String,
         cases : Array { when : String,
                         render : { entity_id : String; a }
                           -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } },

  build : forall a.
    { candidates : Array { entity_id : String; a },
      where : Array String,
      cases : Array { when : String,
                      render : { entity_id : String; a }
                        -> { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } } }
    -> (forall r.
         { node : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> r,
           group : { cell : { classes : Array String }, kind : String, card : String, readout : String, slots : { _ : String } } -> Array r -> r,
           set : { candidates : Array String, where : Array String }
                 -> { _ : Array { when : String, node : r } }
                 -> r }
         -> r),
})
