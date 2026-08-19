# The node vocabulary both the component library and the query builder need.
#
# It exists as its own module for the reason ADR 0015 splits `lib/core/` from
# `lib/components/`: without it, `query` would have to name the type components
# produces, and the only way to avoid the dependency is to write `Dyn` — which
# is not a design, it is a hole.
#
# Written as ONE RECORD, not a chain of `let`s: `Node.children | Array Node` is
# recursive, and `let` in Nickel is not. Record fields are.
{
  Cell = { classes | Array String | default = [] },

  # Anything the grid can place. Weaker than `Node` on purpose: a query's `set`
  # node carries a cell but no `card`, so annotating the layout helpers
  # `Node -> Node` is wrong — it fails on a real dashboard.
  Placeable = { cell | Cell | default = {}, .. },

  Node = {
    kind | String | default = "component",
    card | String,
    slots | { _ : String } | default = {},
    children | Array Node | default = [],
    cell | Cell | default = {},
    ..
  },
}
