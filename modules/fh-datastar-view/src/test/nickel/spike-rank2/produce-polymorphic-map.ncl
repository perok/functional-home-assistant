# The `map` half of produce-polymorphic.ncl, in its own file so ./claims.sh can
# assert that it FAILS. This is why lib2/core.ncl has `groupLeaves`.
(std.array.map (fun n => fun alg => alg.mk n) [0, 1]
  : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r))
