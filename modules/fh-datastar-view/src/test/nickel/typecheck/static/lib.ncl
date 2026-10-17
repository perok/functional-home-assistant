# A statically typed library module: the annotation is at MODULE level, which is
# what makes the type survive an `import`.
#
# `toggle` is row-polymorphic (`; a`) so it accepts any entity carrying the
# fields it needs — which is exactly what a per-entity dump produces.
({
  toggle = fun e => { card = "toggle", entity_id = e.entity_id },
} : {
  toggle : forall a. { entity_id : String, domain : String; a }
    -> { card : String, entity_id : String },
})
