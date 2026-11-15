package fh.view.model

import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.immutable.ListMap

/** A call a tap declares (Pkl's `slotMod.CallDecl`), one kind per call, picked
  * by `kind` on the wire. The server runs its own evaluation at the press; the
  * page only names the call by [[id]] (ADR 0023).
  */
enum CallDecl derives CanEqual:

  /** What HA is asked, every part a value expression. A target key holds
    * expressions each giving an id or a list of them, the key's ids their
    * concatenation.
    */
  case Service(
      service: ValueExpr,
      target: ListMap[IdKind, List[ValueExpr]],
      data: ListMap[String, ValueExpr] = ListMap.empty,
      exclude: ListMap[IdKind, List[ValueExpr]] = ListMap.empty
  )

  /** This viewer's choice of the node variable `variable`, its declarer the
    * nearest one at the pressing node; `None` clears the choice.
    */
  case SetVar(variable: String, value: Option[String])

  def all: List[ValueExpr] = this match
    case Service(service, target, data, exclude) =>
      service :: target.values.flatten.toList ++
        exclude.values.flatten ++ data.values
    case _: SetVar => Nil

  lazy val id: CallId = CallId.of(this)

  /** How a message names it. */
  def named: String = this match
    case s: Service   => s"the call ${s.service.literal.getOrElse(id)}"
    case SetVar(v, _) => s"the choice of '$v'"

extension (s: CallDecl.Service)
  /** What it does, besides what it reaches: the fields a node can see. */
  def doing: List[(CallField, ValueExpr)] =
    (CallField.Service -> s.service) ::
      s.data.toList.map((k, e) => CallField.Data(k) -> e)

/** A field of what a call does, as `seen` posts it: `{service, data: {k}}`. */
enum CallField derives CanEqual:
  case Service
  case Data(key: String)

  def name: String = this match
    case Service   => "service"
    case Data(key) => s"data.$key"

object CallDecl:
  import Strict.listMap

  given Decoder[CallDecl] =
    ConfiguredDecoder.derived(using Strict.configuration)

/** A declared call's id: equal declarations share one, and a declaration's key
  * order does not move it.
  */
opaque type CallId <: String = String

object CallId:

  /** Over [[ValueExpr.inputKey]], which is injective and names every entity,
    * each key's entries sorted.
    */
  def of(d: CallDecl): CallId =
    def sized(s: String) = s"${s.length}:$s"
    def keyed(m: Iterable[(String, String)]) =
      m.toList.sortBy(_._1).map((k, v) => sized(k) + sized(v)).mkString
    def ids(m: Iterable[(IdKind, List[ValueExpr])]) =
      sized(
        keyed(
          m.map((k, es) => k.key -> es.map(e => sized(e.inputKey)).mkString)
        )
      )
    // A service call's canonical form names no kind, so no id moved with the
    // union; it opens with a digit, which a choice's never does.
    val canonical = d match
      case CallDecl.Service(service, target, data, exclude) =>
        sized(service.inputKey) + ids(target) + ids(exclude) +
          sized(keyed(data.map((k, e) => k -> e.inputKey)))
      case CallDecl.SetVar(variable, value) =>
        "SetVar" + sized(variable) + value.fold("-")(sized)
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"$b%02x")
      .mkString

  /** As the route names it; whether it is declared is the dashboard's. */
  def parse(s: String): CallId = s
