package api.homeassistant.ws

import io.circe.derivation.Configuration

object defaults {
  given Configuration = Configuration.default.withDiscriminator("type")
}
