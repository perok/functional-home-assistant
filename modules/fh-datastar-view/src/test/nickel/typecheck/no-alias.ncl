# EXPECT: typecheck FAILS -- "Static types and contracts are not compatible".
# There is no type alias: naming a record type moves it into the contract world.
# Note this EVALUATES fine, so `nickel test` and `nickel export` both miss it.
let Rec = { a : Number } in
let f : Rec -> Number = fun r => r.a in
f { a = 1 }
