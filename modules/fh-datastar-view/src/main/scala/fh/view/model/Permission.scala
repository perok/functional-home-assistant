package fh.view.model

import api.homeassistant.ServiceTarget
import api.homeassistant.ws.domain.HaUser

/** May you see this dashboard, and may a tap from it make this call (issue #89,
  * ADR 0023), as one value so [[mayCall]] cannot skip [[mayView]]. `calls` and
  * `sets` are functions so `Dashboard.calls`/`callSets` are not copied per
  * request. Only the call route needs one; a route that only asks who may see a
  * slug reads its [[Access]].
  */
final case class Permission(
    access: Access,
    calls: TapCall => Boolean,
    sets: String => Option[Predicate.Tally]
) {

  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Only the exact combination a tap declares: without it, `Access.Public`
    * would put every service on every entity in the house one URL edit away,
    * and an area call reaches entities the dashboard never names. Static is
    * sound: a candidate list does not grow while it runs (ADR 0003).
    */
  def mayCall(user: Option[HaUser], call: TapCall): Boolean =
    mayView(user) && calls(call)
}

object Permission {

  /** A missing or failed dashboard: restrictive, and allowing no call. */
  val none: Permission = Permission(Access.default, _ => false, _ => None)
}

/** A service call a tap declares. `service` is `"<domain>/<service>"`, as a tap
  * spells it; the value of `dataKey` is free.
  */
final case class TapCall(
    service: String,
    target: ServiceTarget | CallSet,
    dataKey: Option[String]
) derives CanEqual

/** A query set a call acts on, by its route id (`Dashboard.callSets`): which of
  * its candidates is decided at the press, never by the request.
  */
final case class CallSet(id: String) derives CanEqual

object TapCall {

  /** The target kinds `tap.pkl` emits, as the route spells them. */
  def targetOf(kind: String, id: String): Option[ServiceTarget | CallSet] =
    kind match {
      case "entity" => Some(ServiceTarget.Entity(id))
      case "area"   => Some(ServiceTarget.Area(id))
      case "floor"  => Some(ServiceTarget.Floor(id))
      case "set"    => Some(CallSet(id))
      case _        => None
    }
}
