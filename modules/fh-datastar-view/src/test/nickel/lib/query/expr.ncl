# Wire-AST predicates, shared by both builder styles. Analogue of the
# expression half of lib/query.pkl.
#
# Static types, module-annotated.
#
# `rhs` is the second position where `Dyn` is FORCED rather than chosen: a
# comparison's right-hand side is a String for a state and a Number for a
# battery level, static types have no untagged scalar union, and the polymorphic
# workaround (`forall a. … -> a -> { …, rhs : a }`) dies at the next step —
# `filters` is an Array, and an Array needs ONE element type. Tagging the
# literal would fix the type at the cost of putting a tag on the wire.
#
# The parameters are `l`/`r`, not `lhs`/`rhs`: `lhs = lhs` inside a record is
# self-reference, not the parameter, and `include` cannot be used because the
# value is wrapped. Sixth time this trap has fired here.
#
# Note the injection is `| Dyn`, a contract application, not `: Dyn`: a static
# ascription to `Dyn` is rejected, so this is the documented way to hand a typed
# value to a dynamic position.
({
  prop = fun name => { kind = "prop", include name },
  state = { kind = "prop", name = "state" },
  eq = fun l r => { kind = "cmp", op = "eq", lhs = (l | Dyn), rhs = (r | Dyn) },
  lt = fun l r => { kind = "cmp", op = "lt", lhs = (l | Dyn), rhs = (r | Dyn) },
} : {
  prop : String -> { kind : String, name : String },
  state : { kind : String, name : String },
  eq : forall a b. a -> b -> { kind : String, op : String, lhs : Dyn, rhs : Dyn },
  lt : forall a b. a -> b -> { kind : String, op : String, lhs : Dyn, rhs : Dyn },
})
