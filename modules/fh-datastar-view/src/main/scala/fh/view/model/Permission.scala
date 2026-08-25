package fh.view.model

import api.homeassistant.ws.domain.HaUser

/** What one person may do on one dashboard (issue #89, ADR 0023).
  *
  * There are two questions — may you SEE this dashboard, and may an action from
  * it TOUCH this entity — and they were answered in two places by two
  * mechanisms: the gate asked an `Access`, and the action route asked a
  * renderer whether it named the entity. Nothing tied them together, so the
  * invariant that an action needs BOTH was enforced by two call sites agreeing,
  * and the next rule (per-user service limits) would have arrived as a third
  * mechanism beside them.
  *
  * Here they are one value: the dashboard's rule, plus the set of entities it
  * can ever address. [[mayAct]] is defined in terms of [[mayView]], so the
  * invariant is in the type rather than in a convention — and a new rule is a
  * method here rather than a fourth thing to remember.
  *
  * `names` is a predicate rather than a `Set` because the caller already has
  * one (`Dashboard.referencedEntities`) and this should not copy it per
  * request.
  */
final case class Permission(access: Access, names: String => Boolean) {

  /** Whether `user` may have this dashboard at all — its page, its stream, its
    * actions.
    */
  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Whether `user` may drive `entityId` FROM this dashboard.
    *
    * Both halves, and the entity half is not a formality: without it, being
    * admitted to the most permissive dashboard in the house would be admission
    * to every entity in it — and `Access.Public` admits nobody in particular,
    * which would put a door lock one URL edit away from the street.
    *
    * Sound to decide statically because a candidate set's membership is live
    * but its candidate LIST is not (ADR 0003), so what a dashboard can address
    * cannot grow while it runs.
    */
  def mayAct(user: Option[HaUser], entityId: String): Boolean =
    mayView(user) && names(entityId)
}

object Permission {

  /** A dashboard that does not exist, or failed to build: the restrictive rule,
    * and no entity at all. Both halves matter — a failed dashboard's page is a
    * diagnostics dump, and its actions should reach nothing.
    */
  val none: Permission = Permission(Access.default, _ => false)
}
