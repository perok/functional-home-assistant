# WHERE A POLYMORPHIC VALUE CAN COME FROM. This is the constraint that shapes
# the whole library: an array LITERAL can hold trees, but `map` and `@` cannot
# produce them, so anything built by iterating a list has to be assembled inside
# the fold, where the algebra is in scope and `r` is rigid.
#
# Stand-in for a tree: `forall r. { mk : Number -> r, join : Array r -> r } -> r`.
#
# Uncomment either failing case to see it; both are asserted by ./claims.sh.
{
  # WORKS -- an array literal generalises each element.
  literal : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r) =
    [fun alg => alg.mk 0, fun alg => alg.mk 1],

  # FAILS -- `@` unifies the element type instead of generalising it:
  #   Expected `forall r. …`, Found `{ mk : Number -> _a, … } -> _a`
  # appended : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r) =
  #   ([fun alg => alg.mk 0] : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r))
  #   @ [fun alg => alg.mk 1],

  # FAILS the same way -- this is why lib2/core.ncl needs `groupLeaves` and why
  # a query accumulates flat `Body` values rather than trees.
  # mapped : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r) =
  #   std.array.map (fun n => fun alg => alg.mk n) [0, 1],

  # WORKS -- consuming is fine: once `alg` is applied the element type is
  # monomorphic, so `map` has nothing to generalise.
  consumed : Array Number =
    std.array.map
      (fun t => t { mk = fun n => n, join = fun xs => std.array.length xs })
      literal,
}
