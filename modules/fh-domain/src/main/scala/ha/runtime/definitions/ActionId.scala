package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type ActionId = String
object ActionId {
  inline def of(in: String): ActionId = in
  given Codec[ActionId] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[ActionId] = in => s"ActionId.of(s\"$in\")"
}
