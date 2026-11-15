package fh.view.model

import io.circe.{Decoder, KeyDecoder}

/** What an id names, by HA's target key: a call's target and exclude keys, and
  * a set's candidates ([[Value.Present]], [[LayoutNode.SetNode]]). `word` is
  * how a set spells it on the wire, absent for entities.
  */
enum IdKind(val key: String, val word: String) derives CanEqual:
  case Entity extends IdKind("entity_id", "entity")
  case Area extends IdKind("area_id", "area")
  case Floor extends IdKind("floor_id", "floor")

object IdKind:
  def ofKey(k: String): Option[IdKind] = values.find(_.key == k)

  given KeyDecoder[IdKind] = KeyDecoder.instance(ofKey)

  given Decoder[IdKind] = Decoder.decodeString.emap(w =>
    values
      .find(_.word == w)
      .toRight(s"a set of '$w' — one of ${values.map(_.word).mkString(", ")}")
  )
