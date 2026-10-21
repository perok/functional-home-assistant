# EXPECT: typecheck FAILS -- a typed value does not implicitly become `Dyn`.
#
# There is no upcast. To put a typed value into a `Dyn` position (a layout
# tree's children, say) you must apply the CONTRACT `| Dyn`, which is the
# documented "turn the typechecker off here". A static ascription `: Dyn` is
# rejected, which is what this fixture shows.
({ f = fun x => [({ a = x } : Dyn)] } : { f : String -> Array Dyn })
