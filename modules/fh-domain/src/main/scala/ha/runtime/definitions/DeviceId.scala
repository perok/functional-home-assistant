package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type DeviceId = String
object DeviceId {
  inline def of(in: String): DeviceId = in
  inline def toString(in: DeviceId): String = in
  given Codec[DeviceId] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[DeviceId] = in => s"DeviceId.of(s\"$in\")"
}
