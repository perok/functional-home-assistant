package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type ManifestDomain = String
object ManifestDomain {
  inline def of(in: String): ManifestDomain = in
  def toString(in: ManifestDomain): String = in
  given Codec[ManifestDomain] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[ManifestDomain] = in => s"ManifestDomain.of(s\"$in\")"
}
