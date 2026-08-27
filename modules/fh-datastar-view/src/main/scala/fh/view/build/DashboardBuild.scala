package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{Dashboard, LayoutNode}
import io.circe.{Json, JsonObject}

/** Turns the Pkl dashboard sources into a validated [[Dashboard]].
  *
  * Shared by both phases:
  *   - the build phase ([[BuildApp]]) persists the evaluated JSON as
  *     `dashboard.json`;
  *   - the runtime phase ([[fh.view.runtime.ServerApp]]) evaluates it **in
  *     memory** on startup — no artifact file required.
  */
object DashboardBuild {

  /** Fetch the live entity dump ONCE and seed it as the `@fh-home` content-
    * versioned package ([[DumpPackage.seedFromText]]), so an entry's
    * `import "@fh-home/dump.pkl"` resolves from the workspace cache. There is
    * no loose `home/dump.pkl` on disk (ADR 0010): the dump is only ever a
    * package, pinned via `.fh/pins.json`. This is the build phase's job — it
    * owns fetching + packaging the dump — and the runtime
    * ([[fh.view.runtime.ServerApp]]) calls through here rather than reaching
    * into [[RegistryDump]]/[[PklDump]] directly: it seeds the dump once for all
    * entries, then [[reevaluate]]s each against the cached package.
    */
  def prepareDumps(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts] = None
  ): IO[Unit] =
    RegistryDump.fetch(api).flatMap { dump =>
      // Generation-time complaints about entities HA reported inconsistently
      // (a half-populated capability group). Reported, never fatal: one odd
      // integration must not stop the house's dump from building.
      PklDump
        .warnings(dump)
        .traverse_(w => IO.println(s"dump warning: $w")) *>
        IO.blocking(
          DumpPackage
            .seedFromText(dashboardsDir, PklDump.render(dump), bundledLib)
        ).flatMap(_.traverse_(IO.println))
    }

  /** Fetch + write the live dump ([[prepareDumps]]), then evaluate `entry` into
    * JSON + the set of files read (entry + transitive imports).
    *
    * `bundledLib` is the boot's bundled `@fh-dashboard` artifacts, needed only
    * to seed the VERY FIRST dump on a fresh workspace (no pins yet) — see
    * [[DumpPackage.seedFromText]].
    */
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

  /** Evaluate the entry against the dump ALREADY on disk (no fetch, no write).
    *
    * `SourceEval.eval` reads files and runs pkl-core eagerly, so suspend it in
    * `IO.blocking` (evaluation happens when the IO runs, on the blocking pool)
    * before lifting its Either result.
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
    * layout-tree edge that [[hoistInlineSurfaces]] walks.
    */
  val ChildrenKey: String = "children"

  /** The field naming a surface's (or inline-surface marker's) layout subtree
    * root — part of the surface JSON contract [[hoistInlineSurfaces]] lifts.
    */
  val ContentKey: String = "content"

  /** The literal token an authored node uses to refer to its own backend-minted
    * id — the SAME id the renderer injects as `{{id}}` for that node
    * ([[fh.view.model.LayoutNode.pathId]]). [[hoistInlineSurfaces]] mints it
    * from the node's tree position and splices it in. Authors never type it
    * directly — the `c.openPopup`/`c.tabs` builders embed it (so the authoring
    * layer composes the trigger fully and only borrows the one value it cannot
    * mint: the node's position-derived id).
    */
  val NodeIdToken: String = "@@NODE_ID@@"

  /** Hoist inline surface definitions into the `surfaces` registry.
    *
    * A node may carry an `inlineSurfaces: { <localKey>: { content, bakeInto?,
    * bakeAs?, … } }` marker (the authoring layer can't mint a stable id or
    * mutate the top-level registry, so it inlines the content and refers to the
    * future id via [[NodeIdToken]]). This pass is deliberately generic — it
    * knows nothing about popups, tabs, buttons, signals, or onclick wiring. For
    * each marker-bearing node it:
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
    * highlight) is composed in the authoring layer; the runtime model is always
    * the registry form. Idempotent on marker-free input.
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

  /** Every `@@…@@` placeholder still standing after the build, deduplicated.
    *
    * The authoring layer writes these because the value is not knowable while
    * authoring — [[NodeIdToken]] for a node's own id, `@@CLASSBIND:…@@` for a
    * theme's class list — and a later pass fills them in. Nothing checked that
    * the pass ran: an unresolved token is a plain String, so it decodes, it
    * validates, and it renders into the DOM verbatim. The first symptom is a
    * binding that quietly never matches.
    *
    * A check rather than a type because the tokens live inside arbitrary
    * author-composed strings (an onclick expression, a `data-class` predicate),
    * where the surrounding text is the author's and only the placeholder is
    * ours.
    */
  private[build] def unresolvedTokens(j: Json): List[String] = {
    // `[^@]*` rather than a name charset: a token's payload is arbitrary
    // (`@@CLASSBIND:busySpin:$b@@` carries a Datastar expression), and the
    // delimiters are what identify it. Both `@@` are required, so a lone `@@`
    // in prose and a bare `@post(…)` are not tokens.
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

  // A node's authored `id`, if it declared one — the same field
  // `LayoutNode.Component.id` decodes. Read off the JSON because this pass runs
  // before decoding.
  private def authoredIdOf(node: Json): Option[String] =
    node.asObject.flatMap(_("id")).flatMap(_.asString)

  // Keep only the surface's own fields (content + optional bakeInto/bakeAs/bakeIndex/activation).
  // The host is derived (Surface.hostId), not authored, so "mount" is not lifted;
  // chrome/stack are gone too — every surface is chrome-less (Surface's final 5 fields).
  // The retired flat `defaultOpen` is deliberately NOT lifted: its meaning moved
  // into the `activation` object ({kind:"user", defaultOpen}), and an authoring
  // layer still emitting the flat key is silently ignored (decoder default =
  // user activation, whose no-selection fallback is index 0 — the old semantics).
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

  // The registry id an inline surface is lifted to. Named because it is needed
  // twice — to lift the surface, and to derive the id namespace its content is
  // walked under — and those two MUST agree or the surface's nodes are indexed
  // under ids nothing refers to.
  private def surfaceId(idBase: String, localKey: String): String =
    s"${idBase}_$localKey"

  // Returns the rewritten node and the surfaces collected from it (and its
  // subtree). `idBase` is the node's position-derived id namespace.
  /** One region's children, walked under the ids the RENDERER will give them.
    *
    * The segment comes from `LayoutNode.segment`, not from a local
    * `s"${idBase}_$i"`: that spelling is right for the default region and wrong
    * for every other one, and an id this pass invents is an id nothing else
    * uses — the surface is registered under a key no node has.
    */
  private def walkRegion(
      region: String,
      children: Json,
      idBase: String
  ): (List[Json], List[(String, Json)]) = {
    val rs = children.asArray.getOrElse(Vector.empty).zipWithIndex.map {
      case (ch, i) =>
        // An AUTHORED id replaces the position-derived one here too, or this
        // pass would key a node's inline surfaces off an id the renderer never
        // uses.
        val derived =
          s"${idBase}_${LayoutNode.segment(LayoutNode.Step(region, i))}"
        walk(ch, authoredIdOf(ch).getOrElse(derived))
    }
    (rs.map(_._1).toList, rs.toList.flatMap(_._2))
  }

  private def walk(node: Json, idBase: String): (Json, List[(String, Json)]) =
    node.asObject match {
      case None       => (node, Nil)
      case Some(obj0) =>
        // Recurse into children first — in BOTH wire forms. `children` is a
        // bare array for a node whose card has one region, and an object keyed
        // by region name for one with several (`LayoutNode`'s decoder takes
        // either). Reading only the array form did not merely miss the second:
        // it made this pass STOP at such a node, so nothing under a grouped
        // slider's head or members was hoisted, and any `@@NODE_ID@@` down
        // there survived into the DOM.
        val (obj1, childSurfaces) =
          obj0(ChildrenKey) match {
            case Some(kids) if kids.asArray.isDefined =>
              val (js, ss) = walkRegion(LayoutNode.DefaultRegion, kids, idBase)
              (obj0.add(ChildrenKey, Json.fromValues(js)), ss)
            case Some(kids) if kids.asObject.isDefined =>
              // Region ORDER does not enter an id — the index does, within its
              // own region — so the object's key order is irrelevant here and
              // the result stays keyed exactly as it arrived.
              val rs = kids.asObject.get.toList.map { case (region, arr) =>
                val (js, ss) = walkRegion(region, arr, idBase)
                (region -> Json.fromValues(js), ss)
              }
              (
                obj0.add(
                  ChildrenKey,
                  Json.fromJsonObject(JsonObject.fromIterable(rs.map(_._1)))
                ),
                rs.flatMap(_._2)
              )
            case _ => (obj0, Nil)
          }
        obj1(InlineSurfacesKey).flatMap(_.asObject) match {
          case None         => (Json.fromJsonObject(obj1), childSurfaces)
          case Some(marker) =>
            // Resolve nested inline surfaces inside each panel first, so the
            // only `NodeIdToken`s left in this subtree belong to THIS node.
            //
            // The panel's content is walked under the id namespace the RENDERER
            // will give it — `surfacePrefix(sid) + pathId(Nil)`, exactly as the
            // already-registered branch above does. It is not `<idBase>_<key>_c`:
            // that reads like the same thing and is not, so a surface-owning
            // card nested inside a panel (tabs inside an `If` branch) came out
            // with a `bakeInto` naming a node that does not exist — an unbaked
            // host, a blank `bakeIndex`, and a mount id colliding with the
            // node's own cell.
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
                surfaceId(idBase, key) -> surfaceOf(
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
    * transforms back at their source line; pass `Set.empty` when unavailable.
    */
  def decode(
      json: Json,
      sources: Set[os.Path] = Set.empty,
      // The slug this dashboard is being installed under — the entrypoint key
      // (ADR 0021), or the URL/`--slug` on a push. Applied BEFORE validation so
      // a `Validated` is final: anything derived from the dashboard during
      // validation (the compiled transforms, which carry the slug into a tap's
      // action URL) would otherwise be proven against a name it no longer has.
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

  /** Evaluate the on-disk sources and decode + validate into the runtime model,
    * returning the dashboard and the files it was built from (for watching).
    * Assumes the dump is already written ([[prepareDumps]]).
    */
  private def evalAndDecode(
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard.Validated, Set[os.Path])] =
    evalSource(dashboardsDir, entry).flatMap { r =>
      decode(r.value, r.imports).map(_ -> r.imports)
    }

  /** Fetch + write the dump, then evaluate + decode + validate in one step
    * (in-memory; no artifact file). Returns the proven dashboard and the files
    * it was built from (for watching).
    */
  def build(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String,
      bundledLib: Option[LibPackage.Artifacts] = None
  ): IO[(Dashboard.Validated, Set[os.Path])] =
    prepareDumps(api, dashboardsDir, bundledLib) *> evalAndDecode(
      dashboardsDir,
      entry
    )

  /** Re-evaluate the entry against the dump ALREADY on disk (no HA fetch, no
    * dump rewrite) — used by live reload when only the dashboard sources
    * changed. Returns the proven dashboard + its current import set.
    */
  def reevaluate(
      dashboardsDir: os.Path,
      entry: String
  ): IO[(Dashboard.Validated, Set[os.Path])] =
    evalAndDecode(dashboardsDir, entry)

  /** Evaluate the workspace's ONE entrypoint against the dump already on disk
    * and decode every dashboard it names ([[Site.decode]]) — the whole-site
    * counterpart of [[reevaluate]], and what both boot and live reload run.
    *
    * Failure splits in two, and the split is the point: an evaluation error is
    * raised (nothing can be attributed to a slug — the site did not evaluate),
    * while a single dashboard's decode/validate error is a `Left` inside the
    * result and costs only that slug.
    */
  def evalSite(dashboardsDir: os.Path): IO[(Site.Decoded, Set[os.Path])] =
    evalSource(dashboardsDir, Site.EntryFile).flatMap { r =>
      Site.decode(r.value, r.imports).map(_ -> r.imports)
    }

  /** Fetch + write the dump, then [[evalSite]] — the boot path. */
  def buildSite(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts] = None
  ): IO[(Site.Decoded, Set[os.Path])] =
    prepareDumps(api, dashboardsDir, bundledLib) *> evalSite(dashboardsDir)
}
