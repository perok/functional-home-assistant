package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type ReadableEntityId = String

object ReadableEntityId {
  inline def of(in: String): ReadableEntityId = in
  inline def toString(in: ReadableEntityId): String = in
  def domain(in: ReadableEntityId): String = in.split('.')(0)
  given Codec[ReadableEntityId] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[ReadableEntityId] = in => s"ReadableEntityId.of(s\"$in\")"
}
