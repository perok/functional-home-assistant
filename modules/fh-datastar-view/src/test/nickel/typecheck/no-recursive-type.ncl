# EXPECT: typecheck FAILS -- "unbound identifier `Node`".
# There is no type alias and `let` is not recursive, so a RECURSIVE type cannot
# be written. A layout tree is recursive (`children` holds more nodes), so that
# one position is `Array Dyn` by force of the language, not by choice.
let Node = { card : String, children : Array Node } in
({ n = { card = "a", children = [] } } : { n : Node })
