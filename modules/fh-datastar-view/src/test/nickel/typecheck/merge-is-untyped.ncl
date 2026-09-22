# EXPECT: typecheck FAILS -- `&` is typed `Dyn -> Dyn -> Dyn`.
# So the amend idiom the component library is built on cannot appear in
# statically-typed code at all.
let fullWidth
  : forall a. { cell : { classes : Array String }; a } -> { cell : { classes : Array String }; a }
  = fun node => node & { cell.classes = node.cell.classes @ ["fh-cols-full"] }
in fullWidth { cell = { classes = [] }, card = "toggle" }
