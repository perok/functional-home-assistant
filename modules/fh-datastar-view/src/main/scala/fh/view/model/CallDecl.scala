package fh.view.model

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, DecodingFailure, JsonObject}

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

  def all: List[ValueExpr] = this match
    case Service(service, target, data, exclude) =>
      service :: target.values.flatten.toList ++
        exclude.values.flatten ++ data.values

  lazy val id: CallId = CallId.of(this)

  /** How a message names it. */
  def named: String = this match
    case s: Service => s"the call ${s.service.literal.getOrElse(id)}"

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

  private def ordered[A: Decoder](
      c: ACursor
  ): Decoder.Result[ListMap[String, A]] =
    c.as[JsonObject]
      .flatMap(_.toList.traverse((k, v) => v.as[A].map(k -> _)))
      .map(ListMap.from)

  private def keyed(c: ACursor, field: String) =
    ordered[List[ValueExpr]](c.downField(field)).flatMap(
      _.toList
        .traverse((k, es) =>
          IdKind
            .ofKey(k)
            .toRight(
              DecodingFailure(
                s"unknown $field key '$k' — one of " +
                  IdKind.values.map(_.key).mkString(", "),
                c.history
              )
            )
            .map(_ -> es)
        )
        .map(ListMap.from)
    )

  given Decoder[CallDecl] = Decoder.instance { c =>
    c.get[String]("kind").flatMap {
      case "Service" =>
        Strict.only(c, Set("kind", "service", "target", "data", "exclude")) *> (
          c.get[ValueExpr]("service"),
          keyed(c, "target"),
          if (c.downField("data").succeeded)
            ordered[ValueExpr](c.downField("data"))
          else Right(ListMap.empty),
          if (c.downField("exclude").succeeded) keyed(c, "exclude")
          else Right(ListMap.empty)
        ).mapN(Service.apply)
      case other =>
        Left(DecodingFailure(s"unknown call kind '$other'", c.history))
    }
  }

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
    // A service call's canonical form names no kind, so no id moved with
    // the union.
    val canonical = d match
      case CallDecl.Service(service, target, data, exclude) =>
        sized(service.inputKey) + ids(target) + ids(exclude) +
          sized(keyed(data.map((k, e) => k -> e.inputKey)))
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"$b%02x")
      .mkString

  /** As the route names it; whether it is declared is the dashboard's. */
  def parse(s: String): CallId = s
