package fh.view.build

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Dashboard
import io.circe.Json

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
    * `import 'dump.libsonnet'` resolves), and evaluate `entry` into JSON.
    */
  def evaluate(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[Json] =
    for {
      dump <- DataDump.fetch(api)
      _ <- IO(os.write.over(dashboardsDir / "dump.libsonnet", dump.spaces2))
      json <- JsonnetBuild
        .eval(dashboardsDir, entry)
        .leftMap(err => new RuntimeException(s"jsonnet eval failed:\n$err"))
        .liftTo[IO]
    } yield json

  /** Decode the dashboard JSON into the runtime model and fail fast if any
    * template reference is unknown or an input is unsatisfied.
    */
  def decode(json: Json): IO[Dashboard] =
    for {
      dashboard <- json
        .as[Dashboard]
        .leftMap(err =>
          new RuntimeException(s"dashboard is not a valid Dashboard: $err")
        )
        .liftTo[IO]
      _ <- dashboard.validate match {
        case Nil => IO.unit
        case errs =>
          new RuntimeException(
            s"dashboard failed validation (${errs.size} error(s)):\n" +
              errs.mkString("\n")
          ).raiseError[IO, Unit]
      }
    } yield dashboard

  /** Evaluate + decode + validate in one step (in-memory; no artifact file). */
  def build(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entry: String
  ): IO[Dashboard] =
    evaluate(api, dashboardsDir, entry).flatMap(decode)
}
