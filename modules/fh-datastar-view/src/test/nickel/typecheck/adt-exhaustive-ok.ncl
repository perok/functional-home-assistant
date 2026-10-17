# EXPECT: typecheck PASSES. The control for adt-exhaustive.ncl.
let total : [| 'Entity String, 'Axis Number |] -> String = match {
  'Entity e => e,
  'Axis n => std.string.from_number n,
}
in total ('Entity "x")
