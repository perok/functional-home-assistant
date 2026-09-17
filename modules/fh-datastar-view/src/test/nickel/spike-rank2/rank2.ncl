# Final (Church) encoding of a recursive tree WITHOUT a recursive type.
# Tree = forall r. { leaf : String -> r, branch : Array r -> r } -> r
# Types cannot be named, so every occurrence is inlined -- and `branch` takes an
# Array of polymorphic values, which is RANK-2. Does Nickel accept it?
let leaf : String -> (forall r. { leaf : String -> r, branch : Array r -> r } -> r)
  = fun s => fun alg => alg.leaf s
in
let branch : Array (forall r. { leaf : String -> r, branch : Array r -> r } -> r)
           -> (forall r. { leaf : String -> r, branch : Array r -> r } -> r)
  = fun kids => fun alg => alg.branch (std.array.map (fun k => k alg) kids)
in
let toJson : (forall r. { leaf : String -> r, branch : Array r -> r } -> r) -> String
  = fun t => t { leaf = fun s => s, branch = fun kids => "(" ++ std.string.join "," kids ++ ")" }
in
({ out = toJson (branch [leaf "a", branch [leaf "b", leaf "c"]]) } : { out : String })
