package fh.view.model

import api.homeassistant.ws.domain.HaUser
import io.circe.derivation.ConfiguredDecoder

/** Which HA users a dashboard admits (issue #89); HA stays the only source of
  * identity and roles. Decodes the shared `kind` discriminator, matching
  * `lib/core/access.pkl`. `Site.decode` folds the site default in, so it
  * arrives resolved.
  */
enum Access derives ConfiguredDecoder, CanEqual:

  /** No login, for a wall tablet. */
  case Public

  case Authenticated

  /** `is_owner` implies `is_admin` in HA. */
  case Admin

  /** Literal: an admin not in the list is refused, or "only these two people"
    * would be quietly untrue.
    */
  case Users(ids: List[String])

  def permits(user: Option[HaUser]): Boolean = this match
    case Public        => true
    case Authenticated => user.isDefined
    case Admin         => user.exists(_.is_admin)
    case Users(ids)    => user.exists(u => ids.contains(u.id))

object Access:
  val default: Access = Access.Authenticated
