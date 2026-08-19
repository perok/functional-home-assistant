# Runtime validation — the only thing left in this spike that is a CONTRACT.
#
# That is the point: contracts are for facts about a VALUE that the type system
# cannot state. Everything about shape moved to static types (see core.ncl,
# components.ncl, home.ncl), because a type is checked at compile time and a
# contract is not.
#
# `Slidable` is the surviving example: "this entity's domain is one the slider
# knows how to drive" is a predicate over data, not a shape. Pkl writes it as a
# constrained type, `hass.Entity(sliderSpec.containsKey(domain))`.
#
# Note `all_of`, not `&`: composing a record contract with a custom one using
# `&` type-checks and then fails with "non mergeable terms" the moment anything
# forces it.
let sliderDomains = ["light", "cover", "fan"] in
{
  Slidable =
    std.contract.all_of [
      { entity_id | String, domain | String, .. },
      std.contract.from_predicate (fun e => std.array.elem e.domain sliderDomains),
    ],
}
