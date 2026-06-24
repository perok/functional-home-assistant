package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Dashboard
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

  /** Fetch the live entity dump, write it next to the jsonnet sources (so the
    * `import 'dump.libsonnet'` resolves), and evaluate `entry` into JSON + the
    * set of files read (entry + transitive imports).
    */
  def evaluate(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[JsonnetBuild.Result] =
    for {
      dump <- DataDump.fetch(api)
      _ <- IO(os.write.over(dashboardsDir / "dump.libsonnet", dump.spaces2))
      result <- JsonnetBuild
        .eval(dashboardsDir, entry)
        .leftMap(err => new RuntimeException(s"jsonnet eval failed:\n$err"))
        .liftTo[IO]
    } yield result

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
              if (k == "children" && nv.asArray.isEmpty && !nv.isNull)
                Json.arr(nv)
              else nv
            acc.add(k, fixed)
          }
        )
    )

  /** The literal token an authored node uses to refer to its own backend-minted
    * id namespace; [[hoistInlineSurfaces]] splices the real id in. Authors
    * never type it directly — the `c.openPopup`/`c.tabs` builders embed it (so
    * jsonnet composes the trigger fully and only borrows the id it cannot
    * mint).
    */
  val NodeIdToken: String = "@@NODE@@"

  /** Hoist inline surface definitions into the `surfaces` registry.
    *
    * A node may carry an `inlineSurfaces: { <localKey>: { content, group?,
    * mount? }, … }` marker (jsonnet can't mint a stable id or mutate the
    * top-level registry, so it inlines the content and refers to the future id
    * via [[NodeIdToken]]). This pass is deliberately generic — it knows nothing
    * about popups, tabs, buttons, signals, or onclick wiring. For each
    * marker-bearing node it:
    *
    *   1. mints a stable `idBase` from the node's position;
    *   2. recurses each surface's `content` (nested inline surfaces resolve
    *      first, bottom-up);
    *   3. splices `idBase` into every [[NodeIdToken]] in the node's subtree, so
    *      the author-composed onclick / active-binding / `initial` / mount that
    *      reference `<token>_<localKey>` now point at the real ids;
    *   4. lifts each surface to `surfaces["<idBase>_<localKey>"]` and drops the
    *      marker.
    *
    * All trigger structure (which template, the click expression, any
    * highlight) is composed in jsonnet; the runtime model is always the
    * registry form. Idempotent on marker-free input.
    */
  def hoistInlineSurfaces(json: Json): Json = {
    // Replace every occurrence of `token` in every String leaf of `j`.
    def splice(j: Json, token: String, value: String): Json =
      j.fold(
        j,
        _ => j,
        _ => j,
        s => Json.fromString(s.replace(token, value)),
        arr => Json.fromValues(arr.map(splice(_, token, value))),
        obj => Json.fromJsonObject(obj.mapValues(splice(_, token, value)))
      )

    // Keep only the surface's own fields (content + optional group/mount).
    def surfaceOf(defObj: JsonObject): Json =
      Json.fromJsonObject(
        JsonObject.fromIterable(
          defObj("content").map("content" -> _).toList ++
            List("group", "mount").flatMap(k => defObj(k).map(k -> _))
        )
      )

    // Returns the rewritten node and the surfaces collected from it (and its
    // subtree). `idBase` is the node's position-derived id namespace.
    def walk(node: Json, idBase: String): (Json, List[(String, Json)]) =
      node.asObject match {
        case None       => (node, Nil)
        case Some(obj0) =>
          // Recurse into children first.
          val (obj1, childSurfaces) =
            obj0("children").flatMap(_.asArray) match {
              case Some(arr) =>
                val rs = arr.zipWithIndex.map { case (ch, i) =>
                  walk(ch, s"${idBase}_$i")
                }
                (
                  obj0.add("children", Json.fromValues(rs.map(_._1))),
                  rs.toList.flatMap(_._2)
                )
              case None => (obj0, Nil)
            }
          obj1("inlineSurfaces").flatMap(_.asObject) match {
            case None         => (Json.fromJsonObject(obj1), childSurfaces)
            case Some(marker) =>
              // Resolve nested inline surfaces inside each panel first, so the
              // only `NodeIdToken`s left in this subtree belong to THIS node.
              val resolved = marker.toList.map { case (key, sd) =>
                val sdObj = sd.asObject.getOrElse(JsonObject.empty)
                val (content, nested) =
                  walk(
                    sdObj("content").getOrElse(Json.Null),
                    s"${idBase}_${key}_c"
                  )
                (key, sdObj.add("content", content), nested)
              }
              val withResolved = obj1.add(
                "inlineSurfaces",
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
              val lifted = splicedObj("inlineSurfaces")
                .flatMap(_.asObject)
                .getOrElse(JsonObject.empty)
                .toList
                .map { case (key, sd) =>
                  s"${idBase}_$key" -> surfaceOf(
                    sd.asObject.getOrElse(JsonObject.empty)
                  )
                }
              (
                Json.fromJsonObject(splicedObj.remove("inlineSurfaces")),
                childSurfaces ++ resolved.flatMap(_._3) ++ lifted
              )
          }
      }

    json.asObject match {
      case None => json
      case Some(obj) =>
        val (newCard, cardSurfaces) =
          obj("card").map(walk(_, "inline")).getOrElse((Json.Null, Nil))
        // Existing registered surfaces may themselves contain inline triggers.
        val existing =
          obj("surfaces").flatMap(_.asObject).getOrElse(JsonObject.empty)
        val rebuilt = existing.toList.map { case (sid, sv) =>
          sv.asObject.flatMap(_("content")) match {
            case Some(c) =>
              val (nc, extra) = walk(c, s"inline_$sid")
              (sid -> sv.mapObject(_.add("content", nc)), extra)
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
      _ <- dashboard.validate(JsonnetBuild.literalLocator(sources)) match {
        case Nil => IO.unit
        case errs =>
          new RuntimeException(
            s"dashboard failed validation (${errs.size} error(s)):\n" +
              errs.mkString("\n")
          ).raiseError[IO, Unit]
      }
    } yield dashboard

  /** Evaluate + decode + validate in one step (in-memory; no artifact file).
    * Returns the dashboard and the files it was built from (for watching).
    */
  def build(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard, Set[os.Path])] =
    evaluate(api, dashboardsDir, entry).flatMap { r =>
      decode(r.value, r.imports).map(_ -> r.imports)
    }

  /** Re-evaluate the jsonnet against the dump ALREADY on disk (no HA fetch, no
    * rewrite of `dump.libsonnet`) — used by live reload when only the dashboard
    * sources changed. Returns the dashboard + its current import set.
    */
  def reevaluate(
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard, Set[os.Path])] =
    JsonnetBuild
      .eval(dashboardsDir, entry)
      .leftMap(err => new RuntimeException(s"jsonnet eval failed:\n$err"))
      .liftTo[IO]
      .flatMap(r => decode(r.value, r.imports).map(_ -> r.imports))
}
