# EXPECT: typecheck PASSES. The control for
# module-annotation-must-be-outermost.ncl -- same module, annotation moved
# outside the `let`.
let m = import "annotation-outermost/module.ncl" in
((m.v ++ "!") : String)
