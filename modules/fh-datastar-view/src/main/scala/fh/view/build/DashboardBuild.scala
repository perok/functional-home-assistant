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

  /** Decode the dashboard JSON into the runtime model and fail fast if any card
    * reference is unknown or an input is unsatisfied.
    *
    * `sources` (the entry + transitive imports) is used only to point invalid
    * transforms back at their jsonnet line; pass `Set.empty` when unavailable.
    */
  def decode(json: Json, sources: Set[os.Path] = Set.empty): IO[Dashboard] =
    for {
      dashboard <- normalizeChildren(json)
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
