# The expression AST -- the second recursive structure, and the sharpest
# contrast with the folded design.
#
# Here it is an ordinary recursive ADT whose variants carry sub-expressions, and
# `toText`/`depth` are ordinary recursive functions over it. In the static world
# neither is available: an enum variant CANNOT carry a rank-2 value at all
# (../../spike-rank2/enum-rank2.ncl), so the AST has to become its own fold, and
# its written type runs to 40 lines (../../spike-rank2/lib2/expr.ncl).
#
# Recursion in functions works because record fields see their siblings; plain
# `let` is not recursive.
{
  Expr = [|
    'Prop String,
    'State,
    'Str String,
    'Num Number,
    'Cmp { op | String, lhs | Expr, rhs | Expr },
  |],

  prop | String -> Expr = fun name => 'Prop name,
  state | Expr = 'State,
  str | String -> Expr = fun s => 'Str s,
  num | Number -> Expr = fun n => 'Num n,
  eq | Expr -> Expr -> Expr = fun l r => 'Cmp { op = "eq", lhs = l, rhs = r },
  gt | Expr -> Expr -> Expr = fun l r => 'Cmp { op = "gt", lhs = l, rhs = r },

  toText | Expr -> String
    = match {
      'Prop name => "$attr.%{name}",
      'State => "$state",
      'Str s => "\"%{s}\"",
      'Num n => std.string.from_number n,
      'Cmp c => "(%{toText c.lhs} %{c.op} %{toText c.rhs})",
    },

  depth | Expr -> Number
    = match {
      'Cmp c => 1 + (let l = depth c.lhs in let r = depth c.rhs in if l > r then l else r),
      _ => 1,
    },
}
