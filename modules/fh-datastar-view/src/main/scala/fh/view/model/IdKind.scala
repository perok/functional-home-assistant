package fh.view.model

/** What an id names, by HA's target key: a call's target and exclude keys. */
enum IdKind(val key: String) derives CanEqual:
  case Entity extends IdKind("entity_id")
  case Area extends IdKind("area_id")
  case Floor extends IdKind("floor_id")

object IdKind:
  def ofKey(k: String): Option[IdKind] = values.find(_.key == k)
