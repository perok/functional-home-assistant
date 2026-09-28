package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{Dashboard, LayoutNode}
import io.circe.{Json, JsonObject}
import fh.view.telemetry.Logging
import org.typelevel.log4cats.LoggerFactory

/** Pkl sources to a validated [[Dashboard]], for both [[BuildApp]] and the
  * in-memory runtime.
  */
object DashboardBuild {

  /** Seed the live dump as the `@fh-home` package (ADR 0010); there is no loose
    * dump file.
    */
  def prepareDumps(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts] = None,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[Unit] =
    RegistryDump.fetch(api).flatMap { dump =>
      val log = loggerFactory.getLoggerFromName("fh.view.build.DashboardBuild")
      // Never fatal: one odd integration must not stop the dump.
      PklDump
        .warnings(dump)
        .traverse_(w => log.warn(s"dump warning: $w")) *>
        IO.blocking(
          DumpPackage
            .seedFromText(dashboardsDir, PklDump.render(dump), bundledLib)
        ).flatMap(_.traverse_(log.info(_)))
    }

  /** `bundledLib` is needed only for the first dump on a fresh workspace. */
  def evaluate(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String,
      bundledLib: Option[LibPackage.Artifacts] = None
  ): IO[SourceEval.Result] =
    prepareDumps(api, dashboardsDir, bundledLib) *> evalSource(
      dashboardsDir,
      entry
    )

  private def evalSource(
      dashboardsDir: os.Path,
      entry: String
  ): IO[SourceEval.Result] =
    IO.blocking(SourceEval.eval(dashboardsDir, entry))
      .flatMap(
        _.leftMap(err => new RuntimeException(s"dashboard eval failed:\n$err"))
          .liftTo[IO]
      )

  val InlineSurfacesKey: String = "inlineSurfaces"

  /** Always keyed: a walk that read only a bare array once stopped at a
    * region-keyed node, and `@@NODE_ID@@` reached the DOM verbatim.
    */
  val RegionsKey: String = "regions"

  val ContentKey: String = "content"

  // A candidate set's edges: it holds no `regions`.
  val MembersKey: String = "members"
  val ClausesKey: String = "clauses"
  val NodeKey: String = "node"

  /** Stands for the node's own id, which only this pass can mint; the
    * `c.openPopup`/`c.tabs` builders embed it.
    */
  val NodeIdToken: String = "@@NODE_ID@@"

  /** Lift inline surfaces into the `surfaces` registry, splicing each node's
    * position-derived id into its [[NodeIdToken]]s. Generic: it knows nothing
    * about popups or tabs. Ids match the renderer's `{{id}}` exactly, or the
    * surface is registered under a key no node asks for.
    */
  def hoistInlineSurfaces(json: Json): Json =
    json.asObject match {
      case None      => json
      case Some(obj) =>
        val (newCard, cardSurfaces) =
          obj("card")
            .map(walk(_, LayoutNode.pathId(Nil)))
            .getOrElse((Json.Null, Nil))
        val existing =
          obj("surfaces").flatMap(_.asObject).getOrElse(JsonObject.empty)
        val rebuilt = existing.toList.map { case (sid, sv) =>
          sv.asObject.flatMap(_(ContentKey)) match {
            case Some(c) =>
              val (nc, extra) =
                walk(c, LayoutNode.surfacePrefix(sid) + LayoutNode.pathId(Nil))
              (sid -> sv.mapObject(_.add(ContentKey, nc)), extra)
            case None => (sid -> sv, Nil)
          }
        }
        val collected =
          cardSurfaces ++ rebuilt.flatMap(_._2)
        // `fromIterable` keeps the last of a repeated key, silently handing a
        // popup someone else's content. Two clauses of one candidate share a
        // member id, so both owning an inline surface collide.
        val clashes = collected.map(_._1).diff(collected.map(_._1).distinct)
        if (clashes.nonEmpty)
          throw FHError.badCondition(
            s"two inline surfaces claim the id ${clashes.distinct.sorted
                .mkString(", ")} — a candidate's clauses share one node id, so " +
              "at most one of its renderings may own a popup. Give the others " +
              "a registered surface and open it by name."
          )
        val merged = JsonObject.fromIterable(
          rebuilt.map(_._1) ++ collected
        )
        Json.fromJsonObject(
          obj.add("card", newCard).add("surfaces", Json.fromJsonObject(merged))
        )
    }

  /** An unresolved token decodes, validates and renders verbatim, so this is
    * checked. A check, not a type: tokens live inside author-composed strings.
    */
  private[build] def unresolvedTokens(j: Json): List[String] = {
    // A payload can be arbitrary (`@@CLASSBIND:busySpin:$b@@`).
    val pattern = """@@[^@]*@@""".r
    def go(j: Json): List[String] =
      j.fold(
        Nil,
        _ => Nil,
        _ => Nil,
        s => pattern.findAllIn(s).toList,
        _.toList.flatMap(go),
        _.toList.flatMap((_, v) => go(v))
      )
    go(j).distinct.sorted
  }

  private def splice(j: Json, token: String, value: String): Json =
    j.fold(
      j,
      _ => j,
      _ => j,
      s => Json.fromString(s.replace(token, value)),
      arr => Json.fromValues(arr.map(splice(_, token, value))),
      obj => Json.fromJsonObject(obj.mapValues(splice(_, token, value)))
    )

  // Off the JSON: this pass runs before decoding.
  private def authoredIdOf(node: Json): Option[String] =
    node.asObject.flatMap(_("id")).flatMap(_.asString)

  // Keep only [[Surface]]'s own fields. The host is derived (`Surface.hostId`),
  // not authored, so it is not lifted.
  private def surfaceOf(defObj: JsonObject): Json =
    Json.fromJsonObject(
      JsonObject.fromIterable(
        defObj(ContentKey).map(ContentKey -> _).toList ++
          List(
            "bakeInto",
            "bakeAs",
            "bakeIndex",
            "activation"
          )
            .flatMap(k => defObj(k).map(k -> _))
      )
    )

  // Both the lifted key and its content's namespace; they must agree.
  private def surfaceId(idBase: String, localKey: String): String =
    s"${idBase}_$localKey"

  /** Ids through `LayoutNode.segment`, as the renderer mints them, with an
    * authored id winning as it does there.
    */
  private def walkRegion(
      region: String,
      children: Json,
      idBase: String
  ): (List[Json], List[(String, Json)]) = {
    val rs = children.asArray.getOrElse(Vector.empty).zipWithIndex.map {
      case (ch, i) =>
        val derived =
          s"${idBase}_${LayoutNode.segment(LayoutNode.Step(region, i))}"
        walk(ch, authoredIdOf(ch).getOrElse(derived))
    }
    (rs.map(_._1).toList, rs.toList.flatMap(_._2))
  }

  /** Clause nodes under [[LayoutNode.memberSegment]], as `MemberGraph` mints
    * them. Both clauses of a candidate get the same id, since one is ever
    * rendered; the collision that causes is refused in [[hoistInlineSurfaces]].
    */
  private def walkMembers(
      obj: JsonObject,
      idBase: String
  ): (JsonObject, List[(String, Json)]) =
    obj(MembersKey).flatMap(_.asObject) match {
      case None          => (obj, Nil)
      case Some(members) =>
        val rs = members.toList.map { case (entityId, m) =>
          val mObj = m.asObject.getOrElse(JsonObject.empty)
          val memberBase = LayoutNode.memberSegment(idBase, entityId)
          val walked = mObj(ClausesKey)
            .flatMap(_.asArray)
            .getOrElse(Vector.empty)
            .map { clause =>
              val cObj = clause.asObject.getOrElse(JsonObject.empty)
              cObj(NodeKey) match {
                case None    => (clause, Nil)
                case Some(n) =>
                  val (walkedNode, surfaces) = walk(n, memberBase)
                  (Json.fromJsonObject(cObj.add(NodeKey, walkedNode)), surfaces)
              }
            }
          (
            entityId -> Json.fromJsonObject(
              mObj.add(ClausesKey, Json.fromValues(walked.map(_._1)))
            ),
            walked.toList.flatMap(_._2)
          )
        }
        (
          obj.add(
            MembersKey,
            Json.fromJsonObject(JsonObject.fromIterable(rs.map(_._1)))
          ),
          rs.flatMap(_._2)
        )
    }

  private def walk(node: Json, idBase: String): (Json, List[(String, Json)]) =
    node.asObject match {
      case None       => (node, Nil)
      case Some(obj0) =>
        // Children first.
        val (obj1, childSurfaces) =
          obj0(RegionsKey).flatMap(_.asObject) match {
            case None          => (obj0, Nil)
            case Some(regions) =>
              val rs = regions.toList.map { case (region, arr) =>
                val (js, ss) = walkRegion(region, arr, idBase)
                (region -> Json.fromValues(js), ss)
              }
              (
                obj0.add(
                  RegionsKey,
                  Json.fromJsonObject(JsonObject.fromIterable(rs.map(_._1)))
                ),
                rs.flatMap(_._2)
              )
          }
        // Sets too: the starter's "Low battery" set gives each sensor an
        // inline more-info popup (ADR 0016), and was once never hoisted.
        val (obj2, setSurfaces) = walkMembers(obj1, idBase)
        obj2(InlineSurfacesKey).flatMap(_.asObject) match {
          case None =>
            (Json.fromJsonObject(obj2), childSurfaces ++ setSurfaces)
          case Some(marker) =>
            // Nested first, so the tokens left belong to this node. Under
            // `surfacePrefix(sid) + pathId(Nil)`, not `<idBase>_<key>_c`: that
            // left tabs inside an `If` branch baking into a node that did not
            // exist.
            val resolved = marker.toList.map { case (key, sd) =>
              val sdObj = sd.asObject.getOrElse(JsonObject.empty)
              val (content, nested) =
                walk(
                  sdObj(ContentKey).getOrElse(Json.Null),
                  LayoutNode.surfacePrefix(surfaceId(idBase, key)) +
                    LayoutNode.pathId(Nil)
                )
              (key, sdObj.add(ContentKey, content), nested)
            }
            val withResolved = obj2.add(
              InlineSurfacesKey,
              Json.fromJsonObject(
                JsonObject.fromIterable(
                  resolved.map(r => r._1 -> Json.fromJsonObject(r._2))
                )
              )
            )
            val spliced =
              splice(Json.fromJsonObject(withResolved), NodeIdToken, idBase)
            val splicedObj = spliced.asObject.getOrElse(JsonObject.empty)
            val lifted = splicedObj(InlineSurfacesKey)
              .flatMap(_.asObject)
              .getOrElse(JsonObject.empty)
              .toList
              .map { case (key, sd) =>
                surfaceId(idBase, key) -> surfaceOf(
                  sd.asObject.getOrElse(JsonObject.empty)
                )
              }
            (
              Json.fromJsonObject(splicedObj.remove(InlineSurfacesKey)),
              childSurfaces ++ setSurfaces ++ resolved.flatMap(_._3) ++ lifted
            )
        }
    }

  /** `sources` only locates bad transforms in their source. */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty,
      // Applied before validation, so a `Validated` is not proven against a
      // slug it no longer has.
      slug: Option[String] = None
  ): IO[Dashboard.Validated] =
    for {
      hoisted <- IO.pure(hoistInlineSurfaces(json))
      _ <- unresolvedTokens(hoisted) match {
        case Nil => IO.unit
        case bad =>
          FHError
            .badCondition(
              "the build left placeholder tokens unresolved, which would " +
                s"render literally into the DOM: ${bad.mkString(", ")}"
            )
            .raiseError[IO, Unit]
      }
      decoded <- hoisted
        .as[Dashboard]
        .leftMap(err =>
          FHError.badCondition(s"dashboard is not a valid Dashboard: $err")
        )
        .liftTo[IO]
      dashboard = slug.fold(decoded)(s => decoded.copy(slug = s))
      validated <- dashboard.validated(
        SourceEval.literalLocator(sources)
      ) match {
        case Right(v)   => IO.pure(v)
        case Left(errs) =>
          FHError
            .badCondition(
              s"dashboard failed validation (${errs.size} error(s)):\n" +
                errs.mkString("\n")
            )
            .raiseError[IO, Dashboard.Validated]
      }
    } yield validated

  /** An evaluation error raises (no slug to blame); one dashboard's error is a
    * `Left` that costs only that slug.
    */
  def evalSite(dashboardsDir: os.Path): IO[(Site.Decoded, Set[os.Path])] =
    evalSource(dashboardsDir, Site.EntryFile).flatMap { r =>
      Site.decode(r.value, r.imports).map(_ -> r.imports)
    }
}
