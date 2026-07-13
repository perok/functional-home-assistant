package fh.view.runtime

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{host, port}
import org.http4s.ember.server.EmberServerBuilder

/** Dev-only harness: serve just the editor surface (`/edit`, `/edit/files`,
  * `/edit/file`, `/lsp/pkl`) with NO Home Assistant dependency, so the editor +
  * pkl-lsp bridge can be exercised without a live HA instance / valid token.
  *
  * `sbt 'fh-datastar-view/Test/runMain fh.view.runtime.EditorDevApp'`
  */
object EditorDevApp extends IOApp.Simple {
  private val base =
    os.Path("/home/kanper/Dropbox/dev/functional-home-assistant") /
      "modules" / "fh-datastar-view"

  def run: IO[Unit] = {
    val dir = base / "src" / "main" / "resources" / "dashboards"
    val jar = base / ".pkl-lsp" / "pkl-lsp-0.8.0.jar"
    val editor = new EditorRoutes(dir, Some(jar), "pkl-demo")
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"8090")
      .withHttpWebSocketApp(wsb => editor.routes(wsb).orNotFound)
      .build
      .use(_ =>
        IO.println(
          "editor dev harness on http://127.0.0.1:8090/edit"
        ) *> IO.never
      )
  }
}
