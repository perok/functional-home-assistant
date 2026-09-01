# The RIGHT module form: `(let … in {…}) : T`, annotation outermost.
(
  let helper = "x" in
  { v = helper }
) : { v : String }
