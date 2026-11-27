package fh.view.runtime

import scala.collection.immutable.BitSet

/** An open set by the containers' DOM ids, for a test to state which cases a
  * viewer has without spelling the values that would pick them.
  */
extension (g: PickGraph)
  def openOf(spelled: Set[String]): BitSet =
    BitSet.fromSpecific(
      spelled.iterator.flatMap(g.containersSpelled).map(_.toInt)
    )
