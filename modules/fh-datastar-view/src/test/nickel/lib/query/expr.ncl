# Wire-AST predicates, shared by both builder styles. Analogue of the
# expression half of lib/query.pkl.
#
# No `Dyn`. The one genuinely heterogeneous position — a comparison's
# right-hand side, which is a String for a state and a Number for a battery
# level — is `Literal`, an UNTAGGED union via `std.contract.any_of`. Untagged
# because it has to serialize as a bare JSON scalar; a tagged ADT would put a
# tag on the wire. The cost is a worse error: `any_of` reports the failure of
# its FIRST alternative rather than "matched none of them".
{
  Literal = std.contract.any_of [String, Number, Bool],

  Prop = { kind | String, name | String },
  Cmp = { kind | String, op | String, lhs | Expr, rhs | Literal },
  Expr = { kind | String, .. },

  prop | String -> Prop = fun name => { kind = "prop", include name },
  state | Prop = { kind = "prop", name = "state" },
  eq | Expr -> Literal -> Cmp = fun lhs rhs => { kind = "cmp", op = "eq", include lhs, include rhs },
  lt | Expr -> Literal -> Cmp = fun lhs rhs => { kind = "cmp", op = "lt", include lhs, include rhs },
}
