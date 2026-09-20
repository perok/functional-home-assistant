package fh.view.query

import cats.effect.IO
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Reads,
  SignalBind,
  SlotQuery,
  SlotShape,
  SlotSource
}
import fh.view.runtime.RenderInputs

import java.time.Instant

/** What a node that reads a query contributes to the pipeline: nothing to
  * candidacy, everything to the render key.
  */
class QueryRenderInputsSuite extends munit.CatsEffectSuite {

  private def chart(window: String = "24h") =
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> window))

  private def chartNode(window: String = "24h") =
    LayoutNode.Component(
      card = "historyChart",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.t")),
        "chart" -> SlotSource(query = Some(chart(window))),
        "name" -> SlotSource(transform = "state")
      )
    )

  // --- Candidacy ------------------------------------------------------------

  test("a query slot does not make its node a live candidate") {
    // The claim the whole design rests on: a chart is not re-rendered by a
    // state tick, so a sensor moving every second does not re-fetch its own
    // history every second. Nothing arranges it — both entity lists are built
    // from STATE slots, and a query slot is not one, so it has no entity to
    // contribute even though it leaves `reads` at its `live` default.
    assertEquals(chartNode().liveEntities, List("sensor.t")) // from `name`
    assertEquals(
      LayoutNode
        .Component(
          card = "historyChart",
          slots = Map(
            "entity_id" -> SlotSource(literal = Some("sensor.t")),
            "chart" -> SlotSource(query = Some(chart()))
          )
        )
        .liveEntities,
      Nil
    )
  }

  test("a query slot is not a signal slot, whatever it says") {
    // Not a rule that rejects the combination — a `SlotShape.Query` has no
    // signal to read, so the signal path cannot reach it.
    val src = SlotSource(
      query = Some(chart()),
      signal = Some(SignalBind.Text),
      reads = Reads.Live
    )
    assertEquals(src.shape, SlotShape.Query(chart()))
    assertEquals(
      LayoutNode
        .Component(card = "c", slots = Map("chart" -> src))
        .liveEntitiesAsBytes,
      Nil
    )
  }

  test("a node's queries are its query slots, deduplicated") {
    assertEquals(chartNode().queries, List(chart()))
    val two = LayoutNode.Component(
      card = "twoCharts",
      slots = Map(
        "a" -> SlotSource(query = Some(chart("1h"))),
        "b" -> SlotSource(query = Some(chart("1h"))),
        "c" -> SlotSource(query = Some(chart("30d")))
      )
    )
    assertEquals(two.queries.toSet, Set(chart("1h"), chart("30d")))
  }

  // --- The snapshot ---------------------------------------------------------

  test("a query with no fragment is absent from the key, not zero") {
    // Absent and zero are different renders: one happened before the answer
    // arrived, the other after it arrived empty. Collapsing them would serve
    // the empty one to someone whose data had landed.
    val q = chart()
    assertEquals(Fragments.none.forQueries(List(q)), Map.empty)
    assertEquals(Fragments.none.html(q), None)
    val f = Fragments(Map(q -> Fragment(100L, "<svg/>")))
    assertEquals(f.forQueries(List(q)), Map(q -> 100L))
    assertEquals(f.html(q), Some("<svg/>"))
  }

  test("a failing query leaves a hole, and the others still resolve") {
    val ok = chart("24h")
    val bad = chart("1h")
    val prepared = Map[SlotQuery, PreparedQuery](
      ok -> ((_, _) => IO.pure(Fragment(1L, "<svg/>"))),
      bad -> ((_, _) => IO.raiseError(RuntimeException("recorder is down")))
    )
    Fragments
      .resolve(prepared, List(ok, bad), QueryIdentity.Instance, Instant.EPOCH)
      .map { f =>
        assertEquals(f.html(ok), Some("<svg/>"))
        assertEquals(f.html(bad), None)
        assertEquals(f.forQueries(List(ok, bad)), Map(ok -> 1L))
      }
  }

  // --- The partial order ----------------------------------------------------

  private def key(queries: (SlotQuery, Long)*) =
    RenderInputs(Map("sensor.t" -> 1L), queries.toMap)

  test("a later version is ahead of an earlier one") {
    assert(key(chart() -> 200L).isAtLeast(key(chart() -> 100L)))
    assert(!key(chart() -> 100L).isAtLeast(key(chart() -> 200L)))
    assert(key(chart() -> 100L).isAtLeast(key(chart() -> 100L)))
  }

  test("different queries are not ordered, so neither overwrites the other") {
    // Two viewers on different windows read different queries. Unordered
    // means separate generations — the alternative is one of them being served
    // a chart of the wrong span, which is the silent failure `RenderInputs`
    // warns about in its own doc.
    assert(!key(chart("24h") -> 100L).isAtLeast(key(chart("1h") -> 100L)))
    assert(!key(chart("1h") -> 100L).isAtLeast(key(chart("24h") -> 100L)))
  }

  test("gaining a query is not being ahead") {
    assert(!key(chart() -> 100L).isAtLeast(key()))
    assert(!key().isAtLeast(key(chart() -> 100L)))
  }

  test("a moved query alone moves the key, with state standing still") {
    assertNotEquals(key(chart() -> 100L), key(chart() -> 200L))
  }

  // --- Validation -----------------------------------------------------------

  private def dashboard(src: SlotSource) =
    Dashboard(
      cards = Map("chart" -> CardDef("""<div id="{{id}}">{{{chart}}}</div>""")),
      card = LayoutNode.Component(card = "chart", slots = Map("chart" -> src))
    )

  test("an unregistered provider is a build error naming it") {
    // Silent otherwise: no provider, no fragment, so the slot renders empty
    // forever and never enters the render key — a blank chart with nothing
    // anywhere saying why.
    val errs = dashboard(SlotSource(query = Some(chart()))).validate()
    assert(errs.exists(_.contains("history")), clue = errs)
    assert(errs.exists(_.contains("unknown query provider")), clue = errs)
  }

  test("an ESCAPED hole for a query slot is a build error") {
    // Silent otherwise, and visibly wrong only to whoever opens the page:
    // `{{chart}}` renders `&lt;svg …` as text.
    val stub = new QueryProvider {
      def name = "history"
      def parse(params: Map[String, String]) =
        Right((_, _) => IO.pure(Fragment(1L, "<svg/>")))
    }
    def errsFor(hole: String) =
      Dashboard(
        cards = Map("chart" -> CardDef(s"""<div id="{{id}}">$hole</div>""")),
        card = LayoutNode.Component(
          card = "chart",
          slots = Map("chart" -> SlotSource(query = Some(chart())))
        )
      ).validate(providers = QueryProviders.of(stub))

    assert(
      errsFor("{{chart}}").exists(_.contains("ESCAPED hole")),
      clue = errsFor("{{chart}}")
    )
    assertEquals(errsFor("{{{chart}}}"), Nil)
    // Mustache allows the whitespace, so the check must too.
    assertEquals(errsFor("{{{ chart }}}"), Nil)
  }

  test("a registered provider's own parse error is the build error") {
    val stub = new QueryProvider {
      def name = "history"
      def parse(params: Map[String, String]) =
        Left(s"needs a 'window' (got ${params.keys.toList.sorted})")
    }
    val errs = dashboard(SlotSource(query = Some(SlotQuery("history", Map()))))
      .validate(providers = QueryProviders.of(stub))
    assert(errs.exists(_.contains("needs a 'window'")), clue = errs)
  }
}
