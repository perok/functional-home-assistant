# EXPECT: typecheck PASSES -- and that is the POINT, not a reassurance.
# `lib/` is contract-annotated, so the typechecker has nothing to say about it
# either way. A green `nickel typecheck` on this directory means the tool did
# not look, not that the code is right. Use `nickel test`.
let c = import "../lib/components.ncl" in
let q = import "../lib/query.ncl" in
{
  # both of these are wrong, and typecheck reports neither
  wrongArg = q.pipe.where "not an expr",
  wrongTag = c.slider ('Nonsense { entity_id = "x" }),
}
