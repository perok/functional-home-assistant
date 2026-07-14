package fh.view.smoke

import cats.effect.IO
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, Scene, ServiceCall, SmokeDashboard}
import io.circe.Json

/** "Click -> HA -> back": the control->service->feed->browser loop, driven
  * through an actual mouse click rather than a raw `POST` (the Scala functional
  * suite's level) — proving the `data-on:click` wiring itself, not just the
  * route it targets.
  */
class ControlSmokeSuite extends SmokeSuite {

  private val scene = Scene.of(SmokeDashboard.dashboard)

  private def clickToggle(page: com.microsoft.playwright.Page): IO[Unit] =
    IO.blocking(page.getByText("Toggle Kitchen").click())

  test("clicking a control calls the service back into HA") {
    withPage(scene) { (page, ts) =>
      for {
        _ <- clickToggle(page)
        calls <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
      } yield assertEquals(
        calls,
        Vector(
          ServiceCall(
            "light",
            "toggle",
            HouseFixture.kitchenLight.entityId,
            Json.obj()
          )
        )
      )
    }
  }

  test("round-trip: a click's consequent state reaches the browser") {
    withPage(scene) { (page, ts) =>
      val kitchenState = page
        .locator(
          "article.entity",
          new com.microsoft.playwright.Page.LocatorOptions()
            .setHasText("Kitchen")
        )
        .locator(".state")
      for {
        _ <- ts.awaitLive()
        _ <- clickToggle(page)
        _ <- eventually(ts.fake.recordedCalls)(_.nonEmpty)
        _ <- ts.fake.emit(HouseFixture.kitchenLight.entityId, "off", Map.empty)
        _ <- IO.blocking(assertThat(kitchenState).hasText("off"))
      } yield ()
    }
  }
}
