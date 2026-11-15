package fh.view.model

import api.homeassistant.ws.domain.HaUser

/** May you see this dashboard, and which call may a tap from it make (issue
  * #89, ADR 0023), as one value so [[callOf]] cannot skip [[mayView]]. `calls`
  * is a function so `Dashboard.calls` is not copied per request. Only the call
  * route needs one; a route that only asks who may see a slug reads its
  * [[Access]].
  */
final case class Permission(
    access: Access,
    calls: CallId => Option[Ready.Call]
) {

  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Only a call this dashboard declares: without it, `Access.Public` would put
    * every service on every entity in the house one URL edit away, and an area
    * call reaches entities the dashboard never names. Static is sound: a
    * candidate list does not grow while it runs (ADR 0003).
    */
  def callOf(user: Option[HaUser], id: CallId): Option[Ready.Call] =
    if (mayView(user)) calls(id) else None
}

object Permission {

  /** A missing or failed dashboard: restrictive, and allowing no call. */
  val none: Permission = Permission(Access.default, _ => None)
}
