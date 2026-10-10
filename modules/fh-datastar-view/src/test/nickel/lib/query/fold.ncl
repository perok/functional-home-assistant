# The fold both builder styles share: builder state -> the `set` node that rides
# the wire. Keeping it in one place is what makes "the two styles agree" a real
# test rather than a tautology about two copies of the same code.
#
# Static types, module-annotated. `Render` is a function type written inline:
# an entity goes in, a placed node comes out. Types cannot be named, so the node
# shape is spelled out again here — the tax the language charges for a library
# with more than one module.
#
# `fallback` is an ADT rather than a nullable field, so "no fallback" is a state
# the fold matches on rather than a value it re-tests. The typechecker checks the
# match is exhaustive.
({
  emit = fun st => {
    kind = "set",
    cell = { classes = [] },
    candidates = std.array.map (fun e => e.entity_id) st.candidates,
    where = st.filters,
    members =
      std.array.fold_left
        (fun acc e =>
          std.record.insert
            e.entity_id
            (
              {
                cases = std.array.map (fun c => { when = c.when, node = (c.render e | Dyn) }) st.cases,
                fallback = st.fallback |> match { 'None => null, 'Render g => (g e | Dyn) },
              } | Dyn
            )
            acc)
        {}
        st.candidates,
  },
} : {
  emit : forall a.
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
