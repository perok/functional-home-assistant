package api.homeassistant.ws

import io.circe.derivation.{ConfiguredDecoder, ConfiguredEncoder}
import defaults.given

object authentication {

  enum WSAuthenticationPhase derives ConfiguredDecoder, ConfiguredEncoder {
    case auth_required
    case auth(access_token: String)
    case auth_invalid(message: String)
    case auth_ok
  }
}
