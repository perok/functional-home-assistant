# The WRONG module form: `let … in ({…} : T)`.
# The annotation sits inside the let body, so the module's apparent type is
# `Dyn` and nothing importing it can be typechecked.
let helper = "x" in
({ v = helper } : { v : String })
