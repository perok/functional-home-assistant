package fh.view.auth

import org.http4s.implicits.*

/** Where the browser is sent to log in (issue #89): not the address this server
  * dials HA at, which under the add-on is useless to a browser.
  */
class HaOAuthSuite extends munit.FunSuite {

  test("the login redirect goes where the BROWSER can reach HA") {
    val dialed = uri"http://192.168.1.174:8123"
    val internal = uri"http://ha.lan:8123"
    val explicit = uri"https://ha.example"

    assertEquals(
      HaOAuth.browserBase(Some(explicit), Some(internal), dialed),
      explicit
    )
    assertEquals(HaOAuth.browserBase(None, Some(internal), dialed), internal)
    // `internal_url` is optional in HA and was null on the instance this was
    // built against, so the dialled address, which holds a live socket, is a
    // real rung.
    assertEquals(HaOAuth.browserBase(None, None, dialed), dialed)
  }

  /** `home-addon/run.sh` dials `http://supervisor/core`, so an unguarded
    * fallback points a browser at a container-internal host.
    */
  test("the supervisor address is never handed to a browser") {
    val supervisor = uri"http://supervisor/core"
    assertEquals(
      HaOAuth.browserBase(None, None, supervisor),
      HaOAuth.MdnsFallback
    )
    // Only the last resort.
    assertEquals(
      HaOAuth.browserBase(None, Some(uri"http://ha.lan:8123"), supervisor),
      uri"http://ha.lan:8123"
    )
  }

  /** The mirror failure: the supervisor proxies `/core/api/…` and a websocket
    * for add-ons. HA's `/auth/…` is not under `/api/` (the exchange 401s) and a
    * user token is not an add-on token (the identity socket gets
    * `auth_invalid`).
    */
  test("a per-user credential is never dialled at the supervisor proxy") {
    val supervisor = uri"http://supervisor/core"
    assertEquals(
      HaOAuth.coreBase(None, None, supervisor),
      HaOAuth.AddonCoreFallback
    )
    // HA's `internal_url` names the real port.
    assertEquals(
      HaOAuth.coreBase(None, Some(uri"http://192.168.1.174:8123"), supervisor),
      uri"http://192.168.1.174:8123"
    )
    assertEquals(
      HaOAuth.coreBase(
        Some(uri"http://ha.lan:8123"),
        Some(uri"http://192.168.1.174:8123"),
        supervisor
      ),
      uri"http://ha.lan:8123"
    )
  }

  test("a dialled address that is not the proxy IS the login address") {
    val dialed = uri"http://192.168.1.174:8123"
    // Unlike the browser chain this must be reachable from this process, and
    // the dialled address provably is.
    assertEquals(
      HaOAuth.coreBase(None, Some(uri"http://ha.lan:8123"), dialed),
      dialed
    )
    assertEquals(HaOAuth.coreBase(None, None, dialed), dialed)
  }

  /** `SERVER_WS` exists because of the supervisor (`/core/websocket`), so
    * carrying it past the proxy would send a user token straight back to the
    * address the chain routed around.
    */
  test("the SERVER_WS override is dropped when the login leaves the proxy") {
    val supervisor = uri"http://supervisor/core"
    val supervisorWs = Some(uri"ws://supervisor/core/websocket")

    assertEquals(
      HaOAuth.coreWs(
        HaOAuth.coreBase(None, None, supervisor),
        supervisor,
        supervisorWs
      ),
      None
    )
    // Kept when it describes the address dialled: every non-add-on deployment
    // that sets it.
    val dialed = uri"http://192.168.1.174:8123"
    val explicitWs = Some(uri"ws://192.168.1.174:8123/api/websocket")
    assertEquals(
      HaOAuth.coreWs(
        HaOAuth.coreBase(None, None, dialed),
        dialed,
        explicitWs
      ),
      explicitWs
    )
    assertEquals(HaOAuth.coreWs(dialed, dialed, None), None)
  }

  test("get_config's internal_url is read, and its absence is just absence") {
    def internalUrlOf(raw: String) =
      HaOAuth.internalUrlOf(
        io.circe.parser.parse(raw).getOrElse(fail(s"bad fixture: $raw"))
      )

    assertEquals(
      internalUrlOf(
        """{"internal_url": "http://ha.lan:8123", "external_url": "https://x"}"""
      ),
      Some(uri"http://ha.lan:8123")
    )
    // All three mean HA does not know; `null` is what the live instance
    // returned.
    assertEquals(internalUrlOf("""{"internal_url": null}"""), None)
    assertEquals(internalUrlOf("""{}"""), None)
    assertEquals(internalUrlOf("""{"internal_url": "not a url"}"""), None)
  }
}
