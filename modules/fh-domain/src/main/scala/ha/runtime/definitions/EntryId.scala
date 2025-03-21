package ha.runtime.definitions

import fh.codegen.utils.ToCode
import io.circe.{Codec, Decoder, Encoder}

opaque type EntryId = String

object EntryId {
  inline def of(in: String): EntryId = in
  // inline def toString(in: EntryId): String = in
  given Codec[EntryId] = Codec.from(Decoder[String], Encoder[String])
  given ToCode[EntryId] = in => s"EntryId.of(s\"$in\")"
  extension (in: EntryId) def toString: String = in
}
