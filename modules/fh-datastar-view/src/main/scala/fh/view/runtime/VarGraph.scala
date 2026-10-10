package fh.view.runtime

import fh.view.model.{Dashboard, NodeId, SlotAsk}
import io.circe.Json

/** A node variable's identity: the node declaring it and its name (ADR 0033).
  * A viewer's choice is keyed by it, and every spelling of the variable on the
  * wire comes from it. `core/variable.pkl`'s `Spelling` is the template half,
  * and `VarSignalNamesSuite` holds the two equal.
  */
final case class VarKey(declarer: NodeId, name: String) derives CanEqual:

  /** The selection group, which also names the pending ask (ADR 0025). */
  def group: String = s"var_${declarer}__$name"

  /** `_`-prefixed, so only the SSE GET carries it ([[Server.SseInclude]]). */
  def committedSignal: String = s"_$group"

  def pendingSignal: String = s"${committedSignal}__pending"

  /** The URL mirror, which survives a refresh. */
  def urlParam: String = s"${VarKey.ParamPrefix}$declarer.$name"

object VarKey:

  val ParamPrefix: String = "v."
  val SignalPrefix: String = "_var_"

  /** `v.<declarer>.<name>`. Untrusted: the caller narrows it to declarations. */
  def fromParam(key: String): Option[VarKey] =
    Option.when(key.startsWith(ParamPrefix))(key.drop(ParamPrefix.length)).flatMap {
      _.split('.').toList match {
        case node :: name :: Nil if node.nonEmpty && name.nonEmpty =>
          Some(VarKey(NodeId.derived(node), name))
        case _ => None
      }
    }

  /** A signals frame, never carrying a null (`Datastar.signalsJson`). */
  def signalsJson(values: Map[VarKey, String]): Json =
    Json.obj(values.toList.map { (key, value) =>
      key.committedSignal -> Json.fromString(value)
    }*)

/** Who sees which node variable, beside [[SurfaceGraph]] and [[MemberGraph]]:
  * scopes, a viewer's environment, and what a write reaches (ADR 0033).
  * `Renderer` renders with what it says and holds the write's check
  * ([[Renderer.refusals]]), which needs the queries' parse.
  *
  * @param scopes
  *   the model's ([[Dashboard.varScopes]]), every node with one.
  * @param setOfMember
  *   each set member's indexed set: a member reads the scope there.
  * @param panelsSelected
  *   each variable-selected bake group, by the variable choosing its member.
  * @param asksAt
  *   a node's query asks, a member's whole subtree included.
  */
private[runtime] final class VarGraph(
    scopes: Map[NodeId, Map[String, Dashboard.InScope]],
    setOfMember: Map[NodeId, NodeId],
    panelsSelected: Map[NodeId, String],
    asksAt: NodeId => List[SlotAsk]
):

  private val allScopes: Map[NodeId, Map[String, Dashboard.InScope]] =
    scopes ++ setOfMember.flatMap((member, set) =>
      scopes.get(set).map(member -> _)
    )

  /** Every declaration, with the value it holds before anyone chooses. */
  val declarations: Map[VarKey, String] =
    scopes.values.flatten.map { case (name, in) =>
      VarKey(in.declarer, name) -> in.declared
    }.toMap

  /** Total over declarations, so a control never shows the declared value over
    * a choice.
    */
  def committed(chosen: Map[VarKey, String]): Map[VarKey, String] =
    declarations.map((key, declared) => key -> chosen.getOrElse(key, declared))

  /** Never cached on the renderer or a `NodePlan`: both outlive a session, so a
    * choice held there would be served to the next viewer.
    */
  def env(chosen: Map[VarKey, String]): VarEnv =
    allScopes.view.mapValues { scope =>
      scope.view.map { case (name, in) =>
        name -> chosen.getOrElse(VarKey(in.declarer, name), in.declared)
      }.toMap
    }.toMap

  /** Exact: a write both checks every reader and re-renders them. */
  def readersOf(key: VarKey): List[NodeId] =
    allScopes.toList.collect {
      case (id, scope)
          if scope.get(key.name).exists(_.declarer == key.declarer) &&
            asksAt(id).exists(_.query.references.contains(key.name)) =>
        id
    }

  /** The panels a write moves, swapped as a tab press is. A reader as much as a
    * query is, so a write reaching one is not refused for lacking a query.
    */
  def panelsSelectedBy(key: VarKey): List[NodeId] =
    panelsSelected.toList.sortBy(_._1).collect {
      case (gid, name)
          if name == key.name &&
            scopes.get(gid).flatMap(_.get(name)).exists(_.declarer == key.declarer) =>
        gid
    }
