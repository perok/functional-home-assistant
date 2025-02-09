package api.homeassistant.ws.protocol

import api.homeassistant.ws.utils.defaults.given
import io.circe.derivation.{ConfiguredDecoder, ConfiguredEncoder}

object authentication {

  enum WSAuthenticationPhase derives ConfiguredDecoder, ConfiguredEncoder {
    case auth_required
    case auth(access_token: String)
    case auth_invalid(message: String)
    case auth_ok
  }
}
