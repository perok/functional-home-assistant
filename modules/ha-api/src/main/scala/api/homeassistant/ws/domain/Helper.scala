package api.homeassistant.ws.domain

import io.circe.Decoder

import scala.deriving.Mirror


object Helper {
  inline final def derived[A <: Product](using
                                         inline A: Mirror.Of[A]
                                        ): Decoder[A] = {
    val decoder = Decoder.derived[A]

    Decoder { cursor =>
      decoder(cursor).map { entity =>
        val simpleName = entity.getClass.getSimpleName
        val allMissing =
          cursor.keys.toSet.flatten -- entity.productElementNames
        if allMissing.nonEmpty then println(s"$simpleName missing: $allMissing")

        entity
      }
    }
  }
}
