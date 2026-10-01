package fh.view.smoke

import fh.view.smoke.BrowserSuite.asJsBoolean

import cats.effect.IO
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import fh.view.testkit.{HouseFixture, PklFixture, Scene}
import io.circe.Json

/** What the wire suite cannot show: that Datastar applies a live cell class and
  * an ORed `disabled` to the page, and that a morph of the node itself leaves
  * them standing.
  */
class LiveCellClassSmokeSuite extends SmokeSuite {

  private val kitchen = HouseFixture.kitchenLight
  private val lock = HouseFixture.frontLock

  private val scene = Scene.of(
    PklFixture.buildDashboard(
      "cell-class-smoke",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-dashboard/core/slot.pkl" as slotMod
         |import "@fh-dashboard/core/simple.pkl" as simpleMod
         |import "@fh-home/dump.pkl" as dump
         |
         |local kitchenOn = new slotMod.Slot {
         |  entityId = dump.entities.${kitchen.dumpKey}.entity_id
         |  transform = simpleMod.stateIn(new Listing { "on" })
         |  bypassUnavailable = false
         |}
         |
         |card = (c.column) {
         |  children {
         |    // The label is BYTES, so a rename re-renders this node while the
         |    // class's own signal stands still.
         |    ((c.button("", c.tap.call("light/toggle", dump.entities.${kitchen.dumpKey}))) {
         |      label = c.exprOf(dump.entities.${kitchen.dumpKey}, "attr[?'friendly_name'].orValue('')")
         |    }).classWhen("warm", kitchenOn)
         |    (c.button("Lock", c.tap.toggle(dump.entities.${lock.dumpKey}))) {
         |      disabled = kitchenOn
         |    }
         |  }
         |}
         |""".stripMargin
    )
  )

  private def warmCell(page: Page) =
    page.locator(".fh-cell:has(> button:not(:has-text('Lock')))").last()

  private def hasWarm(page: Page): IO[Boolean] =
    IO.blocking(
      warmCell(page).evaluate("el => el.classList.contains('warm')").asJsBoolean
    )

  private def lockButton(page: Page) =
    page.locator("button", new Page.LocatorOptions().setHasText("Lock"))

  private def named(name: String) =
    Map(
      "friendly_name" -> Json.fromString(name),
      "brightness" -> Json.fromInt(180)
    )

  test(
    "a live cell class follows its reading, and survives a morph of its node"
  ) {
    withPage(scene) { (page, ts) =>
      for {
        _ <- ts.awaitLive()
        _ <- eventually(hasWarm(page))(identity)
        _ <- ts.fake.emit(kitchen.entityId, "off", named("Kitchen"))
        _ <- eventually(hasWarm(page))(!_)
        _ <- ts.fake.emit(kitchen.entityId, "on", named("Kitchen"))
        _ <- eventually(hasWarm(page))(identity)
        // Same state, new name: the node is patched and the class signal is
        // not, so only the morph could take the class away.
        _ <- ts.fake.emit(kitchen.entityId, "on", named("Galley"))
        _ <- IO.blocking(assertThat(warmCell(page)).containsText("Galley"))
        warm <- hasWarm(page)
      } yield assert(warm, "the morph dropped the live class")
    }
  }

  test("a button's own disabled and its tap's refusal each disable it") {
    withPage(scene) { (page, ts) =>
      def disabled: IO[Boolean] = IO.blocking(lockButton(page).isDisabled())
      def becomes(want: Boolean, step: String): IO[Unit] =
        eventually(disabled)(_ == want).void.adaptError { case e =>
          new AssertionError(s"$step: disabled never became $want", e)
        }
      for {
        _ <- ts.awaitLive()
        _ <- becomes(true, "kitchen on, the button's own reason")
        _ <- ts.fake.emit(kitchen.entityId, "off", named("Kitchen"))
        _ <- becomes(false, "kitchen off, lock locked")
        _ <- ts.fake.emit(lock.entityId, "locking", lock.attributes)
        _ <- becomes(true, "lock mid-move, the tap's reason")
        _ <- ts.fake.emit(lock.entityId, "locked", lock.attributes)
        _ <- becomes(false, "lock settled")
      } yield ()
    }
  }
}
