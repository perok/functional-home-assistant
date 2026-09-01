# WHERE RANK-2 STOPS. A `forall` survives in a function argument and inside an
# `Array` (core.ncl's `group` relies on both), but NOT as an enum variant's
# payload: `match` unifies the payload type instead of instantiating it.
#
# Tree stands in as `forall r. (r -> r) -> r` -- same shape, far shorter.
{
  # FAILS: "Could not match the two declarations of `Node`" -- the bound `t`
  # arrives as `forall r. (r -> r) -> r` where an instantiated type is wanted.
  direct =
    let mapOne : [| 'None, 'Node (forall r. (r -> r) -> r) |] -> [| 'None, 'Node Number |]
      = fun f => f |> match { 'None => 'None, 'Node t => 'Node (t (fun x => x + 1)) }
    in
    mapOne 'None,

  # WORKS: the same polymorphic value behind a record field. Field access
  # instantiates where a pattern binding does not.
  wrapped =
    let mapOne : [| 'None, 'Node { node : forall r. (r -> r) -> r } |] -> [| 'None, 'Node Number |]
      = fun f => f |> match { 'None => 'None, 'Node t => 'Node (t.node (fun x => x + 1)) }
    in
    mapOne ('Node { node = fun g => g 0 }),

  # WORKS: an Array is the other position that instantiates.
  arrayed =
    let mapAll : Array (forall r. (r -> r) -> r) -> Array Number
      = fun ts => std.array.map (fun t => t (fun x => x + 1)) ts
    in
    mapAll [fun g => g 0, fun g => g 5],
}
