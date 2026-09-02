package ha.runtime.definitions

import cats.Eq
import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type EntryId = String

object EntryId {
  inline def of(in: String): EntryId = in
  given Codec[EntryId] = Codec.from(Decoder[String], Encoder[String])
  given Eq[EntryId] = Eq.fromUniversalEquals
  given ToCode[EntryId] = in => s"EntryId.of(s\"$in\")"
  // No `toString` extension: an extension can never be selected over the
  // member `Any.toString` the compiler already finds, and the underlying
  // String's is what runs either way.
}
