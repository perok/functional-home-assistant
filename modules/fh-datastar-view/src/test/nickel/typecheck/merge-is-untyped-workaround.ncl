# EXPECT: typecheck PASSES.
# `&` is untyped (merge-is-untyped.ncl), but the job it was doing can be done
# statically by REBUILDING the record instead of merging into it.
#
# That forces a better shape than the merge version had: placement is separated
# from the node body, so the concrete half is rebuilt and the body stays
# polymorphic. One helper then works on cards AND on query set nodes, with no
# `Dyn` and no open-record contract.
({
  fullWidth = fun p => { cell = { classes = p.cell.classes @ ["fh-cols-full"] }, body = p.body },
} : {
  fullWidth : forall a. { cell : { classes : Array String }, body : a }
    -> { cell : { classes : Array String }, body : a },
})
