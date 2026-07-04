package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Dashboard, LayoutNode}
import io.circe.{Json, JsonObject}

/** Turns the jsonnet dashboard sources into a validated [[Dashboard]].
  *
  * Shared by both phases:
  *   - the build phase ([[BuildApp]]) persists the evaluated JSON as
  *     `dashboard.json`;
  *   - the runtime phase ([[fh.view.runtime.ServerApp]]) evaluates it **in
  *     memory** on startup — no artifact file required.
  */
object DashboardBuild {

  /** Fetch the live entity dump ONCE and write it next to the dashboard sources
    * in both authoring languages (so `import 'dump.libsonnet'` and
    * `import "lib/dump.pkl"` resolve). This is the build phase's job: it owns
    * fetching + writing the dumps, and the runtime
    * ([[fh.view.runtime.ServerApp]]) calls through here rather than reaching
    * into [[DataDump]]/[[PklDump]] directly — the runtime writes the dumps once
    * for all entries, then [[reevaluate]]s each against the on-disk copy.
    */
  def prepareDumps(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path
  ): IO[Unit] =
    DataDump.fetch(api).flatMap { dump =>
      IO.blocking {
        os.write.over(dashboardsDir / "dump.libsonnet", dump.spaces2)
        os.write.over(
          dashboardsDir / "lib" / "dump.pkl",
          PklDump.render(dump),
          createFolders = true
        )
      }
    }

  /** Fetch + write the live dump ([[prepareDumps]]), then evaluate `entry` into
    * JSON + the set of files read (entry + transitive imports).
    */
  def evaluate(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[SourceEval.Result] =
    prepareDumps(api, dashboardsDir) *> evalSource(dashboardsDir, entry)

  /** Evaluate the entry against the dump ALREADY on disk (no fetch, no write).
    *
    * `SourceEval.eval` reads files and runs sjsonnet/pkl-core eagerly, so
    * suspend it in `IO.blocking` (evaluation happens when the IO runs, on the
    * blocking pool) before lifting its Either result.
    */
  private def evalSource(
      dashboardsDir: os.Path,
      entry: String
  ): IO[SourceEval.Result] =
    IO.blocking(SourceEval.eval(dashboardsDir, entry))
      .flatMap(
        _.leftMap(err => new RuntimeException(s"dashboard eval failed:\n$err"))
          .liftTo[IO]
      )

  /** The marker key an authored node carries to inline its surface definitions;
    * [[hoistInlineSurfaces]] lifts them into the top-level `surfaces` registry
    * and drops the key. Part of the authored-node JSON contract.
    */
  val InlineSurfacesKey: String = "inlineSurfaces"

  /** The layout-node field naming a container's child nodes — the recursive
    * layout-tree edge that [[normalizeChildren]] and [[hoistInlineSurfaces]]
    * walk.
    */
  val ChildrenKey: String = "children"

  /** The field naming a surface's (or inline-surface marker's) layout subtree
    * root — part of the surface JSON contract [[hoistInlineSurfaces]] lifts.
    */
  val ContentKey: String = "content"

  /** Accept a node's `children` written as a single node (not an array): wrap
    * any object-valued `children` into a one-element list so authors can write
    * `c.row(child)` as well as `c.row([child])`. Recurses the whole tree.
    */
  def normalizeChildren(json: Json): Json =
    json.fold(
      json,
      _ => json,
      _ => json,
      _ => json,
      arr => Json.fromValues(arr.map(normalizeChildren)),
      obj =>
        Json.fromJsonObject(
          obj.toIterable.foldLeft(JsonObject.empty) { case (acc, (k, v)) =>
            val nv = normalizeChildren(v)
            val fixed =
              if (k == ChildrenKey && nv.asArray.isEmpty && !nv.isNull)
                Json.arr(nv)
              else nv
            acc.add(k, fixed)
          }
        )
    )

  /** The literal token an authored node uses to refer to its own backend-minted
    * id — the SAME id the renderer injects as `{{id}}` for that node
    * ([[fh.view.model.LayoutNode.pathId]]). [[hoistInlineSurfaces]] mints it
    * from the node's tree position and splices it in. Authors never type it
    * directly — the `c.openPopup`/`c.tabs` builders embed it (so jsonnet
    * composes the trigger fully and only borrows the one value it cannot mint:
    * the node's position-derived id).
    */
  val NodeIdToken: String = "@@NODE_ID@@"

  /** Hoist inline surface definitions into the `surfaces` registry.
    *
    * A node may carry an `inlineSurfaces: { <localKey>: { content, bakeInto?,
    * bakeAs?, … } }` marker (jsonnet can't mint a stable id or mutate the
    * top-level registry, so it inlines the content and refers to the future id
    * via [[NodeIdToken]]). This pass is deliberately generic — it knows nothing
    * about popups, tabs, buttons, signals, or onclick wiring. For each
    * marker-bearing node it:
    *
    *   1. mints a stable `idBase` from the node's position;
    *   2. recurses each surface's `content` (nested inline surfaces resolve
    *      first, bottom-up);
    *   3. splices `idBase` into every [[NodeIdToken]] in the node's subtree, so
    *      the author-composed onclick / active-binding / `initial` that
    *      reference `<token>_<localKey>` now point at the real ids;
    *   4. lifts each surface to `surfaces["<idBase>_<localKey>"]` and drops the
    *      marker.
    *
    * All trigger structure (which template, the click expression, any
    * highlight) is composed in jsonnet; the runtime model is always the
    * registry form. Idempotent on marker-free input.
    */
  def hoistInlineSurfaces(json: Json): Json =
    json.asObject match {
      case None      => json
      case Some(obj) =>
        // The card root's id namespace is the renderer's root `pathId` ("c"), so
        // a node's hoist-time idBase equals its render-time `{{id}}` — one id
        // story shared with `LayoutNode.pathId`/`surfacePrefix`.
        val (newCard, cardSurfaces) =
          obj("card")
            .map(walk(_, LayoutNode.pathId(Nil)))
            .getOrElse((Json.Null, Nil))
        // Existing registered surfaces may themselves contain inline triggers.
        val existing =
          obj("surfaces").flatMap(_.asObject).getOrElse(JsonObject.empty)
        val rebuilt = existing.toList.map { case (sid, sv) =>
          sv.asObject.flatMap(_(ContentKey)) match {
            case Some(c) =>
              // A surface's content root carries the renderer's surface-scoped id
              // (`s_<sid>__c`), so a nested inline trigger's idBase equals what
              // the renderer injects there — same one id story as the main tree.
              val (nc, extra) =
                walk(c, LayoutNode.surfacePrefix(sid) + LayoutNode.pathId(Nil))
              (sid -> sv.mapObject(_.add(ContentKey, nc)), extra)
            case None => (sid -> sv, Nil)
          }
        }
        val collected =
          cardSurfaces ++ rebuilt.flatMap(_._2)
        val merged = JsonObject.fromIterable(
          rebuilt.map(_._1) ++ collected
        )
        Json.fromJsonObject(
          obj.add("card", newCard).add("surfaces", Json.fromJsonObject(merged))
        )
    }

  // Replace every occurrence of `token` in every String leaf of `j`.
  private def splice(j: Json, token: String, value: String): Json =
    j.fold(
      j,
      _ => j,
      _ => j,
      s => Json.fromString(s.replace(token, value)),
      arr => Json.fromValues(arr.map(splice(_, token, value))),
      obj => Json.fromJsonObject(obj.mapValues(splice(_, token, value)))
    )

  // Keep only the surface's own fields (content + optional bakeInto/bakeAs/bakeIndex/defaultOpen).
  // The host is derived (Surface.hostId), not authored, so "mount" is not lifted;
  // chrome/stack are gone too — every surface is chrome-less (Surface's final 5 fields).
  private def surfaceOf(defObj: JsonObject): Json =
    Json.fromJsonObject(
      JsonObject.fromIterable(
        defObj(ContentKey).map(ContentKey -> _).toList ++
          List(
            "bakeInto",
            "bakeAs",
            "bakeIndex",
            "defaultOpen"
          )
            .flatMap(k => defObj(k).map(k -> _))
      )
    )

  // Returns the rewritten node and the surfaces collected from it (and its
  // subtree). `idBase` is the node's position-derived id namespace.
  private def walk(node: Json, idBase: String): (Json, List[(String, Json)]) =
    node.asObject match {
      case None       => (node, Nil)
      case Some(obj0) =>
        // Recurse into children first.
        val (obj1, childSurfaces) =
          obj0(ChildrenKey).flatMap(_.asArray) match {
            case Some(arr) =>
              val rs = arr.zipWithIndex.map { case (ch, i) =>
                walk(ch, s"${idBase}_$i")
              }
              (
                obj0.add(ChildrenKey, Json.fromValues(rs.map(_._1))),
                rs.toList.flatMap(_._2)
              )
            case None => (obj0, Nil)
          }
        obj1(InlineSurfacesKey).flatMap(_.asObject) match {
          case None         => (Json.fromJsonObject(obj1), childSurfaces)
          case Some(marker) =>
            // Resolve nested inline surfaces inside each panel first, so the
            // only `NodeIdToken`s left in this subtree belong to THIS node.
            val resolved = marker.toList.map { case (key, sd) =>
              val sdObj = sd.asObject.getOrElse(JsonObject.empty)
              val (content, nested) =
                walk(
                  sdObj(ContentKey).getOrElse(Json.Null),
                  s"${idBase}_${key}_c"
                )
              (key, sdObj.add(ContentKey, content), nested)
            }
            val withResolved = obj1.add(
              InlineSurfacesKey,
              Json.fromJsonObject(
                JsonObject.fromIterable(
                  resolved.map(r => r._1 -> Json.fromJsonObject(r._2))
                )
              )
            )
            // Splice this node's real id into the author-composed trigger.
            val spliced =
              splice(Json.fromJsonObject(withResolved), NodeIdToken, idBase)
            val splicedObj = spliced.asObject.getOrElse(JsonObject.empty)
            val lifted = splicedObj(InlineSurfacesKey)
              .flatMap(_.asObject)
              .getOrElse(JsonObject.empty)
              .toList
              .map { case (key, sd) =>
                s"${idBase}_$key" -> surfaceOf(
                  sd.asObject.getOrElse(JsonObject.empty)
                )
              }
            (
              Json.fromJsonObject(splicedObj.remove(InlineSurfacesKey)),
              childSurfaces ++ resolved.flatMap(_._3) ++ lifted
            )
        }
    }

  /** Decode the dashboard JSON into the runtime model and fail fast if any card
    * reference is unknown, an input is unsatisfied, or a slot transform fails
    * to compile. Failing here means the dashboard does NOT load (live-reload
    * keeps the previous working renderer), which beats swapping in a render
    * whose values silently blank out.
    *
    * `sources` (the entry + transitive imports) is used only to point invalid
    * transforms back at their jsonnet line; pass `Set.empty` when unavailable.
    */
  def decode(json: Json, sources: Set[os.Path] = Set.empty): IO[Dashboard] =
    for {
      dashboard <- hoistInlineSurfaces(normalizeChildren(json))
        .as[Dashboard]
        .leftMap(err =>
          new RuntimeException(s"dashboard is not a valid Dashboard: $err")
        )
        .liftTo[IO]
      _ <- dashboard.validate(SourceEval.literalLocator(sources)) match {
        case Nil => IO.unit
        case errs =>
          new RuntimeException(
            s"dashboard failed validation (${errs.size} error(s)):\n" +
              errs.mkString("\n")
          ).raiseError[IO, Unit]
      }
    } yield dashboard

  /** Evaluate the on-disk sources and decode + validate into the runtime model,
    * returning the dashboard and the files it was built from (for watching).
    * Assumes the dumps are already written ([[prepareDumps]]).
    */
  private def evalAndDecode(
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard, Set[os.Path])] =
    evalSource(dashboardsDir, entry).flatMap { r =>
      decode(r.value, r.imports).map(_ -> r.imports)
    }

  /** Fetch + write the dump, then evaluate + decode + validate in one step
    * (in-memory; no artifact file). Returns the dashboard and the files it was
    * built from (for watching).
    */
  def build(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard, Set[os.Path])] =
    prepareDumps(api, dashboardsDir) *> evalAndDecode(dashboardsDir, entry)

  /** Re-evaluate the entry against the dump ALREADY on disk (no HA fetch, no
    * dump rewrite) — used by live reload when only the dashboard sources
    * changed. Returns the dashboard + its current import set.
    */
  def reevaluate(
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard, Set[os.Path])] =
    evalAndDecode(dashboardsDir, entry)
}
