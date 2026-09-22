# EXPECT: typecheck FAILS -- "missing row `Axis`".
# A non-exhaustive match on a statically-typed enum. This is the one real
# compile-time guarantee found in the whole spike.
let partial : [| 'Entity String, 'Axis Number |] -> String = match {
  'Entity e => e,
}
in partial ('Entity "x")
