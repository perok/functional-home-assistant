package fh.view.testkit

import fh.view.model.{Choice, Fill, LayoutNode, Slot, TemplateSlot}

/** A hand-built component's `slots`, for fixtures that name the two kinds. */
object Slots:

  def values(m: Map[String, TemplateSlot]): Map[String, Slot] =
    m.map((n, s) => n -> Slot.Value(s))

  def nodes(m: Map[String, List[LayoutNode]]): Map[String, Slot] =
    m.map((n, ns) => n -> Slot.Nodes(Fill.Nodes(Choice.Fixed(ns))))

  /** `cs` in the default nodes slot. */
  def kids(cs: LayoutNode*): Map[String, Slot] =
    if cs.isEmpty then Map.empty
    else nodes(Map(LayoutNode.DefaultRegion -> cs.toList))
