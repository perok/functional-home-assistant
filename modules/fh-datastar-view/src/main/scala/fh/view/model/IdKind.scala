package fh.view.model

/** What an id names, by HA's target key: a call's target and exclude keys, and
  * a set's candidates ([[Value.Present]]). `word` is how a set spells it on the
  * wire.
  */
enum IdKind(val key: String, val word: String) derives CanEqual:
  case Entity extends IdKind("entity_id", "entity")
  case Area extends IdKind("area_id", "area")
  case Floor extends IdKind("floor_id", "floor")

object IdKind:
  def ofKey(k: String): Option[IdKind] = values.find(_.key == k)
  def ofWord(w: String): Option[IdKind] = values.find(_.word == w)
