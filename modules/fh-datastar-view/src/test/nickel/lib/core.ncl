# Placement — the layout half of a node, and the layout helpers.
#
# STATIC TYPES (`:`), annotated at MODULE level. That is what makes the types
# survive an `import`: an unannotated module has apparent type `Dyn`.
#
# Why placement is separated from the node body: `&` (merge) is untyped
# (`Dyn -> Dyn -> Dyn`), so the amend idiom cannot appear in statically typed
# code. Rebuilding the record instead does typecheck — but only the concrete
# half can be rebuilt, so the body has to stay behind a type variable. The
# result is better than what merge gave us: ONE `fullWidth` covers component
# cards and query set nodes alike, with no `Dyn` and no open-record contract.
#
# `place` wraps a body; every component here returns an already-placed node, so
# a dashboard writes `c.fullWidth (c.toggle e)` exactly as before.
({
  # `body = body` would be self-reference, not the parameter — records are
  # recursive. Fifth time this trap has fired in this directory.
  place = fun body => { cell = { classes = [] }, include body },

  fullWidth = fun p => { cell = { classes = p.cell.classes @ ["fh-cols-full"] }, body = p.body },

  columns = fun n p => {
    cell = { classes = p.cell.classes @ ["fh-cols-%{std.string.from_number n}"] },
    body = p.body,
  },
} : {
  place : forall a. a -> { cell : { classes : Array String }, body : a },

  fullWidth : forall a. { cell : { classes : Array String }, body : a }
    -> { cell : { classes : Array String }, body : a },

  columns : forall a. Number
    -> { cell : { classes : Array String }, body : a }
    -> { cell : { classes : Array String }, body : a },
})
