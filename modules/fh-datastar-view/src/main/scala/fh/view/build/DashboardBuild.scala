package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{Dashboard, LayoutNode}
import io.circe.{Json, JsonObject}
import fh.view.telemetry.Logging
import org.typelevel.log4cats.{Logger, LoggerFactory}

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
    RegistryDump.fetch(api).timed.flatMap { (fetched, dump) =>
      val log = loggerFactory.getLoggerFromName(LoggerName)
      log.info(s"registry fetched in ${fetched.toMillis} ms") *>
        // Never fatal: one odd integration must not stop the dump.
        PklDump
          .warnings(dump)
          .traverse_(w => log.warn(s"dump warning: $w")) *>
        IO.blocking(
          DumpPackage
            .seedFromText(dashboardsDir, PklDump.render(dump), bundledLib)
        ).timed
          .flatMap { (seeded, lines) =>
            lines.traverse_(log.info(_)) *>
              log.info(s"dump rendered and seeded in ${seeded.toMillis} ms")
          }
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

  /** Always keyed: a walk that read only a bare array once stopped at a node
    * keyed by slot, and `@@NODE_ID@@` reached the DOM verbatim. The wire name
    * of [[LayoutNode.Component.slots]].
    */
  val SlotsKey: String = "wireSlots"

  val FillKey: String = "fill"
  val ChoiceKey: String = "choice"

  val ContentKey: String = "content"

  // A set fill's edges: each member a node, or a choice of one by case.
  val MembersKey: String = "members"
  val CasesKey: String = "cases"
  val ValueKey: String = "value"
  val OtherwiseKey: String = "otherwise"

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
          obj("card").map(walkRoot(_, "")).getOrElse((Json.Null, Nil))
        val existing =
          obj("surfaces").flatMap(_.asObject).getOrElse(JsonObject.empty)
        val rebuilt = existing.toList.map { case (sid, sv) =>
          sv.asObject.flatMap(_(ContentKey)) match {
            case Some(c) =>
              val (nc, extra) = walkRoot(c, LayoutNode.surfacePrefix(sid))
              (sid -> sv.mapObject(_.add(ContentKey, nc)), extra)
            case None => (sid -> sv, Nil)
          }
        }
        val collected =
          cardSurfaces ++ rebuilt.flatMap(_._2)
        // `fromIterable` keeps the last of a repeated key, silently handing a
        // popup someone else's content. Two clauses of one candidate share a
        // member id, so both owning a popup collide; a choice's case carries
        // its clause.
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

  // Keep only a popup's own fields.
  private def surfaceOf(defObj: JsonObject): Json =
    Json.fromJsonObject(
      JsonObject.fromIterable(
        List(ContentKey, "activation").flatMap(k => defObj(k).map(k -> _))
      )
    )

  // Both the lifted key and its content's namespace; they must agree.
  private def surfaceId(idBase: String, localKey: String): String =
    s"${idBase}_$localKey"

  /** Ids through `LayoutNode.segment`, as the renderer mints them, with an
    * authored id winning, under its surface's prefix, as it does there
    * (`LayoutNode.childId`).
    */
  private def walkRegion(
      region: String,
      children: Json,
      idBase: String,
      prefix: String,
      clause: Option[Int]
  ): (List[Json], List[(String, Json)]) = {
    val rs = children.asArray.getOrElse(Vector.empty).zipWithIndex.map {
      case (ch, i) =>
        val derived =
          s"${idBase}_${LayoutNode.segment(LayoutNode.Step(region, i))}"
        walk(ch, authoredIdOf(ch).fold(derived)(prefix + _), prefix, clause)
    }
    (rs.map(_._1).toList, rs.toList.flatMap(_._2))
  }

  /** A nodes slot's fixed nodes, under the slot's name as their region, or a
    * set's members; a value slot passes through.
    */
  private def walkSlot(
      name: String,
      slot: Json,
      idBase: String,
      prefix: String,
      clause: Option[Int]
  ): (Json, List[(String, Json)]) =
    (for {
      obj <- slot.asObject
      fill <- obj(FillKey).flatMap(_.asObject)
    } yield fill(ChoiceKey).filter(_.isArray) match {
      case Some(choice) =>
        val (js, ss) = walkRegion(name, choice, idBase, prefix, clause)
        (
          Json.fromJsonObject(
            obj.add(
              FillKey,
              Json.fromJsonObject(fill.add(ChoiceKey, Json.fromValues(js)))
            )
          ),
          ss
        )
      case None if fill(ChoiceKey).exists(_.isObject) =>
        val (walked, ss) = walkChosen(
          fill(ChoiceKey).flatMap(_.asObject).getOrElse(JsonObject.empty),
          idBase,
          clause
        )
        (
          Json.fromJsonObject(
            obj.add(
              FillKey,
              Json.fromJsonObject(
                fill.add(ChoiceKey, Json.fromJsonObject(walked))
              )
            )
          ),
          ss
        )
      case None =>
        val (walked, ss) = walkMembers(fill, idBase, prefix)
        (Json.fromJsonObject(obj.add(FillKey, Json.fromJsonObject(walked))), ss)
    }).getOrElse((slot, Nil))

  /** A `Chosen` fill's cases, each walked as the root of the surface it is
    * served as: case `i` is `<host>_t<i>`, the otherwise `<host>_else`, inside
    * a set member's clause `<host>_k<clause>_…`. The model derives the same ids
    * ([[fh.view.model.Dashboard.picks]]); a second spelling here would leave
    * `@@NODE_ID@@` in the DOM.
    */
  private def walkChosen(
      chosen: JsonObject,
      idBase: String,
      clause: Option[Int]
  ): (JsonObject, List[(String, Json)]) = {
    def sidOf(key: String) =
      clause.fold(surfaceId(idBase, key))(k => surfaceId(s"${idBase}_k$k", key))
    def nodes(key: String, js: Json): (Json, List[(String, Json)]) =
      val rs = js.asArray.getOrElse(Vector.empty).map { n =>
        walkRoot(n, LayoutNode.surfacePrefix(sidOf(key)))
      }
      (Json.fromValues(rs.map(_._1)), rs.toList.flatMap(_._2))
    val cases = chosen(CasesKey).flatMap(_.asArray).getOrElse(Vector.empty)
    val walkedCases = cases.zipWithIndex.map { (kase, i) =>
      val kObj = kase.asObject.getOrElse(JsonObject.empty)
      kObj(ValueKey).fold((kase, List.empty[(String, Json)])) { v =>
        val (js, ss) = nodes(s"t$i", v)
        (Json.fromJsonObject(kObj.add(ValueKey, js)), ss)
      }
    }
    val walkedOtherwise =
      chosen(OtherwiseKey).filter(!_.isNull).map(nodes("else", _))
    val withCases = chosen.add(CasesKey, Json.fromValues(walkedCases.map(_._1)))
    (
      walkedOtherwise.fold(withCases)((js, _) =>
        withCases.add(OtherwiseKey, js)
      ),
      walkedCases.toList.flatMap(_._2) ++ walkedOtherwise.toList.flatMap(_._2)
    )
  }

  /** A set's member nodes under [[LayoutNode.memberSegment]], as `MemberGraph`
    * mints them. Every case of a candidate gets the same id, since one is ever
    * rendered, so a choice inside one carries its case (`Surface.InPick`); two
    * cases' popups collide, which [[hoistInlineSurfaces]] refuses. A member
    * that is a node alone is case 0.
    */
  private def walkMembers(
      fill: JsonObject,
      idBase: String,
      prefix: String
  ): (JsonObject, List[(String, Json)]) =
    fill(MembersKey).flatMap(_.asObject) match {
      case None          => (fill, Nil)
      case Some(members) =>
        val rs = members.toList.map { case (entityId, m) =>
          val memberBase = LayoutNode.memberSegment(idBase, entityId)
          m.asObject.filter(
            _("kind").flatMap(_.asString).contains("Chosen")
          ) match {
            case Some(chosen) =>
              val walked = chosen(CasesKey)
                .flatMap(_.asArray)
                .getOrElse(Vector.empty)
                .zipWithIndex
                .map { (kase, ci) =>
                  val kObj = kase.asObject.getOrElse(JsonObject.empty)
                  kObj(ValueKey) match {
                    case None    => (kase, Nil)
                    case Some(n) =>
                      val (walkedNode, surfaces) =
                        walk(n, memberBase, prefix, Some(ci))
                      (
                        Json.fromJsonObject(kObj.add(ValueKey, walkedNode)),
                        surfaces
                      )
                  }
                }
              (
                entityId -> Json.fromJsonObject(
                  chosen.add(CasesKey, Json.fromValues(walked.map(_._1)))
                ),
                walked.toList.flatMap(_._2)
              )
            case None =>
              val (walkedNode, surfaces) = walk(m, memberBase, prefix, Some(0))
              (entityId -> walkedNode, surfaces)
          }
        }
        (
          fill.add(
            MembersKey,
            Json.fromJsonObject(JsonObject.fromIterable(rs.map(_._1)))
          ),
          rs.flatMap(_._2)
        )
    }

  /** A tree's root, the page's or a surface's, by `LayoutNode.rootId`. */
  private def walkRoot(
      node: Json,
      prefix: String
  ): (Json, List[(String, Json)]) =
    walk(
      node,
      prefix + authoredIdOf(node).getOrElse("c"),
      prefix,
      clause = None
    )

  /** `prefix` is the tree's, which an authored id below still carries. `clause`
    * is set below a set member.
    */
  private def walk(
      node: Json,
      idBase: String,
      prefix: String,
      clause: Option[Int]
  ): (Json, List[(String, Json)]) =
    node.asObject match {
      case None           => (node, Nil)
      case Some(authored) =>
        // Children first.
        val (obj2, childSurfaces) =
          authored(SlotsKey).flatMap(_.asObject) match {
            case None        => (authored, Nil)
            case Some(slots) =>
              val rs = slots.toList.map { case (name, slot) =>
                val (js, ss) = walkSlot(name, slot, idBase, prefix, clause)
                (name -> js, ss)
              }
              (
                authored.add(
                  SlotsKey,
                  Json.fromJsonObject(JsonObject.fromIterable(rs.map(_._1)))
                ),
                rs.flatMap(_._2)
              )
          }
        // A set is a slot's fill, so its members are walked above: the
        // starter's "Low battery" set gives each sensor an inline more-info
        // popup (ADR 0016), and was once never hoisted.
        obj2(InlineSurfacesKey).flatMap(_.asObject) match {
          case None =>
            (Json.fromJsonObject(obj2), childSurfaces)
          case Some(marker) =>
            // A popup keeps `<idBase>_<key>`, in a clause too: its opener
            // spells that (`tap.pkl`'s `_self`). Nested first, so the tokens
            // left belong to this node. Under the popup's own root id, not
            // `<idBase>_<key>_c`.
            val resolved = marker.toList.map { case (key, sd) =>
              val sdObj = sd.asObject.getOrElse(JsonObject.empty)
              val (content, nested) =
                walkRoot(
                  sdObj(ContentKey).getOrElse(Json.Null),
                  LayoutNode.surfacePrefix(surfaceId(idBase, key))
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
                val sdObj = sd.asObject.getOrElse(JsonObject.empty)
                surfaceId(idBase, key) -> surfaceOf(sdObj)
              }
            (
              Json.fromJsonObject(splicedObj.remove(InlineSurfacesKey)),
              childSurfaces ++ resolved.flatMap(_._3) ++ lifted
            )
        }
    }

  /** `sources` only locates bad transforms in their source. */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty,
      // Applied before validation, so a `Validated` is not proven against a
      // slug it no longer has.
      slug: Option[String] = None,
      minifier: Minifier = Minifier.none
  ): IO[Dashboard.Validated] =
    for {
      minified <- minifier.dashboard(json)
      hoisted = hoistInlineSurfaces(minified)
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
  def evalSite(
      dashboardsDir: os.Path,
      log: Logger[IO] = Logging.console.getLoggerFromName(LoggerName),
      minifier: Minifier = Minifier.none
  ): IO[(Site.Decoded, Set[os.Path])] =
    evalSource(dashboardsDir, Site.EntryFile).timed.flatMap { (took, r) =>
      val how = if (r.fromCache) "read from the eval cache" else "evaluated"
      log.info(
        s"${Site.EntryFile} $how in ${took.toMillis} ms " +
          s"(${r.imports.size} workspace files)"
      ) *> Site.decode(r.value, r.imports, log, minifier).map(_ -> r.imports)
    }

  /** Issue #406: on a Pi a boot or a reload takes seconds, and these lines say
    * which phase took them.
    */
  private val LoggerName = "fh.view.build.DashboardBuild"
}
