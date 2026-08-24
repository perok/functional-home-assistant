package fh.view.auth

import org.http4s.implicits.*

/** Where the browser is sent to log in (issue #89).
  *
  * Not the address this server dials Home Assistant at, which is the whole
  * point: under the add-on they are different, and the dialled one is useless
  * to a browser.
  */
class HaOAuthSuite extends munit.FunSuite {

  test("the login redirect goes where the BROWSER can reach HA") {
    val dialed = uri"http://192.168.1.174:8123"
    val internal = uri"http://ha.lan:8123"
    val explicit = uri"https://ha.example"

    // An explicit setting outranks everything, including HA's own opinion.
    assertEquals(
      HaOAuth.browserBase(Some(explicit), Some(internal), dialed),
      explicit
    )
    assertEquals(HaOAuth.browserBase(None, Some(internal), dialed), internal)
    // `internal_url` is optional in HA and was null on the instance this was
    // built against, so the dialled address — which we hold a live socket to —
    // has to be a real rung, not a formality.
    assertEquals(HaOAuth.browserBase(None, None, dialed), dialed)
  }

  /** The case that is broken rather than merely suboptimal: `home-addon/run.sh`
    * dials `http://supervisor/core`, so under the add-on an unguarded fallback
    * points a browser at a container-internal host.
    */
  test("the supervisor address is never handed to a browser") {
    val supervisor = uri"http://supervisor/core"
    assertEquals(
      HaOAuth.browserBase(None, None, supervisor),
      HaOAuth.MdnsFallback
    )
    // ...but it is only the LAST resort: anything that actually knows wins.
    assertEquals(
      HaOAuth.browserBase(None, Some(uri"http://ha.lan:8123"), supervisor),
      uri"http://ha.lan:8123"
    )
  }

  /** The mirror failure of the one above, and the reason `SERVER` is not simply
    * the token address: the supervisor proxies `/core/api/…` and the websocket,
    * and HA's `/auth/…` is not under `/api/`, so the exchange 401s there.
    */
  test("the token exchange is never dialled at the supervisor proxy") {
    val supervisor = uri"http://supervisor/core"
    assertEquals(
      HaOAuth.tokenBase(None, None, supervisor),
      HaOAuth.AddonCoreFallback
    )
    // HA's own `internal_url` outranks the guess — it names the real port.
    assertEquals(
      HaOAuth.tokenBase(None, Some(uri"http://192.168.1.174:8123"), supervisor),
      uri"http://192.168.1.174:8123"
    )
    // And an explicit override outranks both.
    assertEquals(
      HaOAuth.tokenBase(
        Some(uri"http://ha.lan:8123"),
        Some(uri"http://192.168.1.174:8123"),
        supervisor
      ),
      uri"http://ha.lan:8123"
    )
  }

  test("a dialled address that is not the proxy IS the token address") {
    val dialed = uri"http://192.168.1.174:8123"
    // Outranking `internal_url`, unlike the browser chain: this one has to be
    // reachable from THIS process, and the dialled address provably is.
    assertEquals(
      HaOAuth.tokenBase(None, Some(uri"http://ha.lan:8123"), dialed),
      dialed
    )
    assertEquals(HaOAuth.tokenBase(None, None, dialed), dialed)
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
    // All three mean the same thing — HA does not know — and none is an error.
    // `null` is the one the live instance actually returned.
    assertEquals(internalUrlOf("""{"internal_url": null}"""), None)
    assertEquals(internalUrlOf("""{}"""), None)
    assertEquals(internalUrlOf("""{"internal_url": "not a url"}"""), None)
  }
}
