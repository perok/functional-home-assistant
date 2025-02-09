package api.homeassistant.ws.utils

import io.circe.derivation.Configuration

object defaults {
  given Configuration = Configuration.default.withDiscriminator("type")
}
