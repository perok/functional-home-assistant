package fh.view.model

import api.homeassistant.ws.domain.HaUser

/** May you see this dashboard, and may an action from it touch this entity
  * (issue #89, ADR 0023), as one value so [[mayAct]] cannot skip [[mayView]].
  * `names` is a predicate so `Dashboard.referencedEntities` is not copied per
  * request.
  */
final case class Permission(access: Access, names: String => Boolean) {

  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Without the entity half, `Access.Public` would put every entity in the
    * house one URL edit away. Static is sound: a candidate list does not grow
    * while it runs (ADR 0003).
    */
  def mayAct(user: Option[HaUser], entityId: String): Boolean =
    mayView(user) && names(entityId)
}

object Permission {

  /** A missing or failed dashboard: restrictive, and reaching no entity. */
  val none: Permission = Permission(Access.default, _ => false)
}
