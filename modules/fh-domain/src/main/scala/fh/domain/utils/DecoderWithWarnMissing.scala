package fh.domain.utils

import io.circe.Decoder

import scala.deriving.Mirror

object DecoderWithWarnMissing {
  inline final def derived[A <: Product](using
      inline A: Mirror.Of[A]
  ): Decoder[A] = {
    val decoder = Decoder.derived[A](using A)

    // `Decoder.instance`, not `Decoder(...)`: the latter is circe's SUMMONER
    // (`apply[A](implicit d: Decoder[A])`), so passing the function positionally
    // supplies an implicit parameter explicitly and SAM-converts it.
    Decoder.instance { cursor =>
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
