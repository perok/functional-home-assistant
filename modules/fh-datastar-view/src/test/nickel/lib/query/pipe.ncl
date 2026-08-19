# STYLE 1 — plain functions, chained with `|>`.
#
#   q.pipe.from home.lights
#   |> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
#   |> q.pipe.build
#
# The state argument comes LAST in every step, so partial application makes each
# step a `|>`-able function — which the signatures say out loud: every step is
# `… -> State -> State`.
#
# Every step REBUILDS the state record field by field. Neither shortcut is
# available in statically typed code: `&` (merge) is untyped, and
# `std.record.update` is a Dictionary operation that refuses a concrete record
# type. This is the most visible tax the static side charges.
((
  let fold = import "fold.ncl" in
  {
  from = fun entities => { candidates = entities, filters = [], cases = [], fallback = 'None },

  where = fun pred q => {
    candidates = q.candidates,
    filters = q.filters @ [pred],
    cases = q.cases,
    fallback = q.fallback,
  },

  case_of = fun pred render q => {
    candidates = q.candidates,
    filters = q.filters,
    cases = q.cases @ [{ when = pred, include render }],
    fallback = q.fallback,
  },

  otherwise = fun render q => {
    candidates = q.candidates,
    filters = q.filters,
    cases = q.cases,
    fallback = 'Render render,
  },

  build = fun q => fold.emit q,
}) : {
  from : forall a. Array { entity_id : String; a }
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] },

  where : forall a. Dyn
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] }
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] },

  case_of : forall a. Dyn
    -> ({ entity_id : String; a } -> Dyn)
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] }
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] },

  otherwise : forall a. ({ entity_id : String; a } -> Dyn)
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] }
    -> { candidates : Array { entity_id : String; a },
         filters : Array Dyn,
         cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
         fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] },

  build : forall a.
    { candidates : Array { entity_id : String; a },
      filters : Array Dyn,
      cases : Array { when : Dyn, render : { entity_id : String; a } -> Dyn },
      fallback : [| 'None, 'Render ({ entity_id : String; a } -> Dyn) |] }
    -> { kind : String,
         cell : { classes : Array String },
         candidates : Array String,
         where : Array Dyn,
         members : { _ : Dyn } },
})
