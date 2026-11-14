package fh.view.model

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, DecodingFailure, JsonObject}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.immutable.ListMap

/** A call a tap declares (Pkl's `tapMod.CallDecl`): what HA is asked, every
  * part a value expression. A target key holds expressions each giving an id or
  * a list of them, the key's ids their concatenation. The server runs its own
  * evaluation at the press; the page only names the call by [[id]] (ADR 0023).
  */
final case class CallDecl(
    service: ValueExpr,
    target: ListMap[String, List[ValueExpr]],
    data: ListMap[String, ValueExpr] = ListMap.empty
) derives CanEqual:

  /** What it does, besides what it reaches: the fields a node can see. */
  def doing: List[(CallField, ValueExpr)] =
    (CallField.Service -> service) ::
      data.toList.map((k, e) => CallField.Data(k) -> e)

  def all: List[ValueExpr] = service :: target.values.flatten.toList ++
    data.values

  lazy val id: CallId = CallId.of(this)

/** A field of what a call does, as `seen` posts it: `{service, data: {k}}`. */
enum CallField derives CanEqual:
  case Service
  case Data(key: String)

  def name: String = this match
    case Service   => "service"
    case Data(key) => s"data.$key"

object CallDecl:

  /** HA's own names; `exclude` takes the same. */
  val TargetKeys: List[String] = List("entity_id", "area_id", "floor_id")

  private def ordered[A: Decoder](
      c: ACursor
  ): Decoder.Result[ListMap[String, A]] =
    c.as[JsonObject]
      .flatMap(_.toList.traverse((k, v) => v.as[A].map(k -> _)))
      .map(ListMap.from)

  given Decoder[CallDecl] = Decoder.instance { c =>
    Strict.only(c, Set("service", "target", "data")) *> (
      c.get[ValueExpr]("service"),
      ordered[List[ValueExpr]](c.downField("target")).flatMap(t =>
        t.keys.find(!TargetKeys.contains(_)) match
          case Some(k) =>
            Left(
              DecodingFailure(
                s"unknown target key '$k' — one of ${TargetKeys.mkString(", ")}",
                c.history
              )
            )
          case None => Right(t)
      ),
      if (c.downField("data").succeeded) ordered[ValueExpr](c.downField("data"))
      else Right(ListMap.empty)
    ).mapN(CallDecl.apply)
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
    val canonical =
      sized(d.service.inputKey) +
        sized(
          keyed(
            d.target.map((k, es) =>
              k -> es.map(e => sized(e.inputKey)).mkString
            )
          )
        ) +
        sized(keyed(d.data.map((k, e) => k -> e.inputKey)))
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"$b%02x")
      .mkString

  /** As the route names it; whether it is declared is the dashboard's. */
  def parse(s: String): CallId = s
