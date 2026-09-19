package fh.view.model

import api.homeassistant.ws.domain.HaUser
import io.circe.derivation.ConfiguredDecoder

/** Who may see one dashboard (issue #89).
  *
  * Home Assistant is the identity provider and the only source of roles: this
  * type says which HA users a dashboard admits, never who anyone is. The one
  * case HA has no equivalent for is [[Public]] — a dashboard nobody has to log
  * in for, which is what a wall tablet needs.
  *
  * The wire form is the shared `kind` discriminator (see the `Configuration` in
  * `Dashboard.scala`, which lowercases constructor names), so these decode from
  * `{"kind":"admin"}` / `{"kind":"users","ids":[…]}` — matching
  * `lib/core/access.pkl` field for field.
  *
  * Authored per dashboard on `entry.pkl` with a site-wide default on
  * `site.pkl`; the two are folded together in `Site.decode`, so by the time a
  * dashboard reaches the registry its rule is a resolved value rather than an
  * `Option` every caller has to re-derive.
  */
enum Access derives ConfiguredDecoder, CanEqual:

  /** No login at all — the "guest" of issue #89. */
  case Public

  /** Any Home Assistant user. The default, and what requirement 1 asks for. */
  case Authenticated

  /** HA's own admin flag. `is_owner` implies `is_admin` in HA, so this covers
    * the owner without naming them.
    */
  case Admin

  /** Exactly these HA user ids. Untyped strings for now — typing them off the
    * dump is codegen's job later.
    *
    * Deliberately literal: an admin who is not in the list is refused. The list
    * is the author's own configuration, and an implicit admin override would
    * make "only these two people" quietly untrue.
    */
  case Users(ids: List[String])

  /** Whether `user` may see a dashboard carrying this rule. `None` is an
    * un-authenticated visitor.
    */
  def permits(user: Option[HaUser]): Boolean = this match
    case Public        => true
    case Authenticated => user.isDefined
    case Admin         => user.exists(_.is_admin)
    case Users(ids)    => user.exists(u => ids.contains(u.id))

object Access:
  /** What a dashboard gets when neither it nor its site says otherwise —
    * requirement 1, in one place so "the default" is never re-stated.
    */
  val default: Access = Access.Authenticated
