# EXPECT: typecheck FAILS -- "Found an expression of type `Dyn`".
#
# The subtlest thing in this directory. A module carries its type across
# `import` only if the annotation is the OUTERMOST expression. Write
# `let x = … in ({…} : T)` — the natural way to put imports at the top — and
# `let … in X : T` parses as `let … in (X : T)`, so the module's apparent type
# is `Dyn` and every importer silently loses static checking.
#
# Nothing warns. The module itself typechecks clean either way; only the
# IMPORTER degrades, and it degrades to "no checking", which looks like success.
# Compare module-annotation-outermost-ok.ncl, identical but for the parens.
let m = import "annotation-inside-let/module.ncl" in
((m.v ++ "!") : String)
