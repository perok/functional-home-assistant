# The `@` half of produce-polymorphic.ncl, in its own file so ./claims.sh can
# assert that it FAILS. A pipe step accumulating `cases` does exactly this.
#
# The ascription has to be on the WHOLE expression: with only the left operand
# annotated, the result type is free and the file typechecks clean.
(([fun alg => alg.mk 0] : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r))
  @ [fun alg => alg.mk 1]
  : Array (forall r. { mk : Number -> r, join : Array r -> r } -> r))
