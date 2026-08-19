# The idiomatic design: the tree is a CONTRACT, and contracts can recurse.
#
# `Node` refers to itself because a record's fields see their siblings. That is
# the whole trick -- no fold, no rank-2, no flat carrier. The recursive type the
# static world cannot write (../spike-rank2/README.md) is three lines here, and
# the nested JSON it exports is the shape the renderer already consumes.
#
# The price is not visible in this file. It is that `nickel typecheck` has
# nothing to say about any of it, and neither does the editor -- see ./README.md.
{
  Body = {
    kind | String,
    card | String | default = "",
    readout | String | default = "",
    classes | Array String | default = [],
    slots | { _ | String } | default = {},
  },

  Case = { when | String, node | Node },

  Node = {
    body | Body,
    children | Array Node | default = [],
    # A query set node: static membership plus the per-member cases.
    candidates | Array String | default = [],
    where | Array String | default = [],
    members | { _ | Array Case } | default = {},
  },

  # Open (`..`) for the same reason the static version is row-polymorphic: a
  # generated entity carries whatever attributes it has.
  Entity = { entity_id | String, friendly_name | String, .. },

  grid = { kind = "grid" },

  leaf | Body -> Node = fun b => { body = b },

  group | Body -> Array Node -> Node = fun b kids => { body = b, children = kids },

  # `&` is symmetric merge, not amend: `b & { classes = … }` fails the moment
  # the component already set `classes`. Untyped code can reach for
  # `std.record.update`, which typed code could not.
  fullWidth | Body -> Body
    = fun b => std.record.update "classes" (b.classes @ ["s12"]) b,

  columns | Number -> Body -> Body
    = fun n b => std.record.update "classes" (b.classes @ ["s%{std.string.from_number n}"]) b,
}
