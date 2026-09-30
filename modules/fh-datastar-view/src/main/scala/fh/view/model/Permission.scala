package fh.view.model

import api.homeassistant.ServiceTarget
import api.homeassistant.ws.domain.HaUser

/** May you see this dashboard, and may an action from it touch this entity
  * (issue #89, ADR 0023), as one value so [[mayAct]] cannot skip [[mayView]].
  * `names` is a predicate so `Dashboard.referencedEntities` is not copied per
  * request; `groupCalls` likewise for `Dashboard.groupCalls`.
  */
final case class Permission(
    access: Access,
    names: String => Boolean,
    groupCalls: GroupCall => Boolean = _ => false
) {

  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Without the entity half, `Access.Public` would put every entity in the
    * house one URL edit away. Static is sound: a candidate list does not grow
    * while it runs (ADR 0003).
    */
  def mayAct(user: Option[HaUser], entityId: String): Boolean =
    mayView(user) && names(entityId)

  /** Only the exact combination a tap declares: an area call reaches entities
    * the dashboard never names, so the service, the target and the value's key
    * are all part of what was allowed (ADR 0023).
    */
  def mayCall(user: Option[HaUser], call: GroupCall): Boolean =
    mayView(user) && groupCalls(call)
}

object Permission {

  /** A missing or failed dashboard: restrictive, and reaching no entity. */
  val none: Permission = Permission(Access.default, _ => false)
}

/** `service` is `"<domain>/<service>"`, as a tap spells it. */
final case class GroupCall(
    service: String,
    target: ServiceTarget,
    dataKey: Option[String]
) derives CanEqual

object GroupCall {

  /** The kinds a tap may name; an entity goes through the entity route. */
  def targetOf(kind: String, id: String): Option[ServiceTarget] = kind match {
    case "area"  => Some(ServiceTarget.Area(id))
    case "floor" => Some(ServiceTarget.Floor(id))
    case _       => None
  }
}
