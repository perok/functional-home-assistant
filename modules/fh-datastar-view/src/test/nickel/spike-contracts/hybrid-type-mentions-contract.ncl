# Why there is no middle design.
#
# The manual's advice is "types for functions, contracts for data", which reads
# like a recipe for this domain: components are functions, the tree is data. It
# does not compose. The moment a STATIC signature mentions the tree contract,
# the two worlds meet and the typechecker refuses:
#
#   = Found an expression of type `Node` (a contract)
#   = Static types and contracts are not compatible
#
# So a library either types everything (and pays the fold) or contracts
# everything (and waits for eval). Asserted by ./claims.sh.
let core = import "lib3/core.ncl" in
{
  toggle : { entity_id : String, friendly_name : String } -> core.Node,
  toggle = fun e => core.leaf { kind = "toggle", card = "toggle", slots = { label = e.friendly_name } },
}
