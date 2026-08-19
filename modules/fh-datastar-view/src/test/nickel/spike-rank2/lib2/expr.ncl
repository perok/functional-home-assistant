# Query expressions, folded the same way as the layout tree.
#
# The AST is recursive (a comparison's sides are expressions), which is why the
# current lib/query/expr.ncl types `lhs`/`rhs` as `Dyn`. Here `e` marks the
# recursive positions and nothing is `Dyn` -- including the literals, which the
# old version could only express as an untagged `any_of`.
#
# Because an expression IS its fold, it supports more than one interpretation of
# the same authored value: `toText` renders it, `depth` measures it. That is not
# available from a record AST without a recursive type to walk.
({
  prop = fun name => fun alg => alg.prop name,
  state = fun alg => alg.state,
  str = fun s => fun alg => alg.str s,
  num = fun n => fun alg => alg.num n,
  eq = fun l r => fun alg => alg.cmp "eq" (l alg) (r alg),
  gt = fun l r => fun alg => alg.cmp "gt" (l alg) (r alg),

  toText = fun ex =>
    ex {
      prop = fun name => "$attr.%{name}",
      state = "$state",
      str = fun s => "\"%{s}\"",
      num = fun n => std.string.from_number n,
      cmp = fun op l r => "(%{l} %{op} %{r})",
    },

  depth = fun ex =>
    ex {
      prop = fun _name => 1,
      state = 1,
      str = fun _s => 1,
      num = fun _n => 1,
      cmp = fun _op l r => 1 + (if l > r then l else r),
    },
} : {
  prop : String -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e),
  state : forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e,
  str : String -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e),
  num : Number -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e),

  eq : (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e),

  gt : (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e)
    -> (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e),

  toText : (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e) -> String,
  depth : (forall e. { prop : String -> e, state : e, str : String -> e, num : Number -> e, cmp : String -> e -> e -> e } -> e) -> Number,
})
