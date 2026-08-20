package fh.view.auth

import api.homeassistant.ws.domain.HaUser
import fh.view.model.Access
import fh.view.testkit.TestAuth

/** The access rule as a truth table (issue #89).
  *
  * Every other auth test rides on `Access.permits` being right, and it is the
  * one piece with no `IO`, no HA and no request — so it is checked
  * exhaustively here rather than sampled through a route. The cases that earn
  * their line are the ones where a plausible implementation differs: whether
  * an admin passes a `Users` list they are not on, and whether an anonymous
  * visitor passes anything but `Public`.
  */
class AccessSuite extends munit.FunSuite {

  private val admin = TestAuth.admin
  private val guest = TestAuth.guest

  private def permits(access: Access, who: Option[HaUser]) = access.permits(who)

  test("public admits everyone, including nobody") {
    assert(permits(Access.Public, None))
    assert(permits(Access.Public, Some(guest)))
    assert(permits(Access.Public, Some(admin)))
  }

  test("authenticated admits any logged-in user and refuses anonymous") {
    assert(!permits(Access.Authenticated, None))
    assert(permits(Access.Authenticated, Some(guest)))
    assert(permits(Access.Authenticated, Some(admin)))
  }

  test("admin reads HA's own flag") {
    assert(!permits(Access.Admin, None))
    assert(!permits(Access.Admin, Some(guest)))
    assert(permits(Access.Admin, Some(admin)))
  }

  test("users is literal — an admin not on the list is refused") {
    val rule = Access.Users(List(guest.id))
    assert(permits(rule, Some(guest)))
    assert(!permits(rule, Some(admin)))
    assert(!permits(rule, None))
  }

  test("users matches on id, not on name") {
    val impostor = admin.copy(id = "someone-else", name = guest.name)
    assert(!permits(Access.Users(List(guest.id)), Some(impostor)))
  }

  test("the default is authenticated") {
    assertEquals(Access.default, Access.Authenticated)
  }
}
