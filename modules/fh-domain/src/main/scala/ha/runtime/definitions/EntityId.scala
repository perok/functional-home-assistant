package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type EntityId = String

object EntityId {
  def of(in: String): EntityId = in
  given Codec[EntityId] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[EntityId] = in => s"EntityId.of(s\"$in\")"
}
