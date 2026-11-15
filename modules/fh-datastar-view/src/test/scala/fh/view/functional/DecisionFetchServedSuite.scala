package fh.view.functional

import fh.view.testkit.TestCard
import api.homeassistant.ws.domain.HistoryPoint
import cats.effect.IO
import fh.view.history.Windows
import fh.view.model.*
import fh.view.runtime.TestServer
import fh.view.testkit.{FakeConfig, HouseFixture}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** A set's clause over a fetch, served: the server answers it before the page
  * decides who is a member.
  */
class DecisionFetchServedSuite extends CatsEffectSuite {

  private val left = HouseFixture.washerRemaining

  // A member while the washer has any history in the last hour.
  private val dashboard = Dashboard(
    cards = Map(
      "item" -> TestCard("<i>{{label}}</i>"),
      "col" -> TestCard(
        "<div>{{{children}}}</div>",
        regions = Map(LayoutNode.DefaultRegion -> Region())
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.SetNode(
          candidates = List(left.entityId),
          members = Map(
            left.entityId -> LayoutNode.SetMember(
              List(
                LayoutNode.SetClause(
                  Some(
                    ValueExpr(
                      Value.Cel(
                        "size(past) > 0",
                        Map(
                          "past" -> ValueExpr(
                            Value.Fetch(
                              FetchTemplate(
                                left.entityId,
                                Choice.Fixed(Windows.hour)
                              )
                            )
                          )
                        )
                      )
                    )
                  ),
                  LayoutNode.Component(
                    "item",
                    Map("label" -> TemplateSlot(ValueExpr.literal("washing")))
                  )
                )
              )
            )
          )
        )
      )
    )
  )

  private def page(recorded: Boolean): IO[String] =
    TestServer
      .resource(
        dashboard,
        List(left),
        config = FakeConfig(recorder =
          Some((from, _, _) =>
            IO.pure(
              if (recorded) List(HistoryPoint("101", from.plusSeconds(60)))
              else Nil
            )
          )
        )
      )
      .use(_.page())
      .timeout(60.seconds)

  test("a page's set decides its clause by the recorder's answer") {
    for {
      member <- page(recorded = true)
      none <- page(recorded = false)
    } yield {
      assert(member.contains("washing"), clue = member)
      assert(!none.contains("washing"), clue = none)
    }
  }
}
