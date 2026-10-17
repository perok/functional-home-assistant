package fh.view.model

import api.homeassistant.ServiceTarget
import api.homeassistant.ws.domain.HaUser

/** May you see this dashboard, and what may a tap from it reach (issue #89, ADR
  * 0023), as one value so [[reachOf]] cannot skip [[mayView]]. `calls` is a
  * function so `Dashboard.calls` is not copied per request. Only the call route
  * needs one; a route that only asks who may see a slug reads its [[Access]].
  */
final case class Permission(
    access: Access,
    calls: TapCall => Option[CallReach]
) {

  def mayView(user: Option[HaUser]): Boolean = access.permits(user)

  /** Only the exact combination a tap declares: without it, `Access.Public`
    * would put every service on every entity in the house one URL edit away,
    * and an area call reaches entities the dashboard never names. Static is
    * sound: a candidate list does not grow while it runs (ADR 0003).
    */
  def reachOf(user: Option[HaUser], call: TapCall): Option[CallReach] =
    if (mayView(user)) calls(call) else None
}

object Permission {

  /** A missing or failed dashboard: restrictive, and allowing no call. */
  val none: Permission = Permission(Access.default, _ => None)
}

/** A service call a tap declares. `service` is `"<domain>/<service>"`, as a tap
  * spells it; the value of `dataKey` is free.
  */
final case class TapCall(
    service: String,
    target: ServiceTarget | CallSet,
    dataKey: Option[String]
) derives CanEqual

/** A query set a call acts on, by its route id: which of its candidates is
  * decided at the press, never by the request.
  */
final case class CallSet(id: String) derives CanEqual

/** What a declared call acts on: its fixed target, or a set the press narrows
  * to the candidates present then.
  */
type CallReach = ServiceTarget | Predicate.Tally

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
