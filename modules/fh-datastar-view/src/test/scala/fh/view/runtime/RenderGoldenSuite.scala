package fh.view.runtime

import fh.view.build.AddonBootstrap
import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.model.{Dashboard, NodeId, SlotRead}
import fh.view.testkit.{
  FixtureEntity,
  FixtureEntries,
  HouseFixture,
  PklFixture,
  SmokeDashboard,
  TextSnapshot
}
import io.circe.Json

/** What the fixture dashboards render, checked in: each one's body, every
  * surface, and every node's patch, at the house as fixtured and with every
  * on/off flipped. The wire snapshots say what Pkl built; these say what a
  * viewer is sent, so a change to how a slot's value is modelled can be shown
  * to leave the bytes alone, or name the ones it moves.
  *
  * A display signal's name carries a hash of its transform's key, so each hash
  * is replaced by its order of first appearance: the goldens hold the sharing,
  * not the key's spelling. A line break before each cell, card and `data-`
  * attribute is the goldens' own, so a diff names what moved.
  */
class RenderGoldenSuite extends munit.FunSuite {

  private val battery = FixtureEntity(
    "sensor.remote_battery",
    "7",
    Map(
      "friendly_name" -> Json.fromString("Remote Battery"),
      "device_class" -> Json.fromString("battery"),
      "unit_of_measurement" -> Json.fromString("%")
    )
  )

  /** Every entity any of the dashboards below is built against. One that a
    * dashboard does not name changes nothing it renders.
    */
  private val house: List[FixtureEntity] =
    HouseFixture.all ++ List(
      battery,
      SmokeDashboard.switchLight,
      SmokeDashboard.colourLamp,
      SmokeDashboard.colourLamp2,
      SmokeDashboard.groupDimmer,
      SmokeDashboard.longNameLight
    )

  private def entry(slug: String, source: String) =
    slug -> PklFixture.buildDashboard(slug, source)

  private lazy val dashboards: List[(String, Dashboard)] = List(
    entry("fixture-features", FixtureEntries.features),
    entry("fixture-surfaces", FixtureEntries.surfaces),
    entry("fixture-slots", FixtureEntries.slots),
    "starter" -> PklFixture.buildSiteDashboard(
      "home",
      AddonBootstrap.starterSite,
      HouseFixture.dumpWith(battery)
    ),
    "smoke-house" -> SmokeDashboard.dashboard,
    "smoke-appliance" -> SmokeDashboard.appliance,
    "smoke-percent-slider" -> SmokeDashboard.percentSlider,
    "smoke-busy-icon" -> SmokeDashboard.busyIcon,
    "smoke-long-label-rows" -> SmokeDashboard.longLabelRows,
    "smoke-switch-cards" -> SmokeDashboard.switchCards,
    "smoke-light-slider" -> SmokeDashboard.lightSlider,
    "smoke-light-slider-by-mode" -> SmokeDashboard.lightSliderByMode,
    "smoke-light-slider-dropdown" -> SmokeDashboard.lightSliderDropdown,
    "smoke-light-slider-group" -> SmokeDashboard.lightSliderGroup,
    "smoke-light-slider-rows" -> SmokeDashboard.lightSliderRows,
    "smoke-pill-row-in-column" -> SmokeDashboard.pillRowInColumn,
    "smoke-badge-looks" -> SmokeDashboard.badgeLooks,
    "smoke-switch-slider" -> SmokeDashboard.switchSlider,
    "smoke-long-title-cards" -> SmokeDashboard.longTitleCards
  )

  private val houses: List[(String, Map[String, EntityState])] = {
    val base = house.map(e => e.entityId -> e.toEntityState).toMap
    val flip = Map("on" -> "off", "off" -> "on")
    List(
      "as fixtured" -> base,
      "flipped" -> base.view
        .mapValues(s => s.copy(state = flip.getOrElse(s.state, s.state)))
        .toMap,
      // As HA reports an entity its integration lost: the word for a state,
      // and only what the registry still knows about it.
      "unavailable" -> base.view
        .mapValues(s =>
          s.copy(
            state = "unavailable",
            attributes = s.attributes.view
              .filterKeys(RenderGoldenSuite.RegistryAttributes)
              .toMap
          )
        )
        .toMap
    )
  }

  /** Every read anything below asks, answered alike: what a chart draws is the
    * stage's business, not the slot's.
    */
  private def answers(
      r: Renderer,
      d: Dashboard,
      states: Map[String, EntityState],
      nodes: List[NodeId]
  ): FetchSnapshot = {
    val env = r.vars.env(Map.empty)
    val reads: List[SlotRead] =
      r.fetchesForPage(r.surfaces.selectedSurfaces(), states, env) ++
        d.surfaces.keys.toList.flatMap(
          r.fetchesForSurface(_, states, Selections.none, env)
        ) ++
        r.readsForPull(nodes, Nil, states, Selections.none, env)
    FetchSnapshot.of(reads.map(_ -> Staged(1L, "<svg/>")).toMap, env)
  }

  private def golden(d: Dashboard): String = {
    val r = Renderer(d.validated().fold(e => fail(e.mkString("; ")), identity))
    val surfaceIds = d.surfaces.keys.toList.sorted
    val out = new StringBuilder
    def section(title: String, body: String): Unit = {
      val _ = out.append(s"<!-- ==== $title ==== -->\n").append(body)
      if (!body.endsWith("\n")) { val _ = out.append('\n') }
    }
    for ((houseName, states) <- houses) {
      val body =
        r.renderBodyTraced(states, Selections.none, answers(r, d, states, Nil))
      val nodes =
        (body.own.keys ++ surfaceIds.flatMap(
          r.surfaceNodeIds
        )).toList.distinct.sorted
      val f = answers(r, d, states, nodes)
      section(s"$houseName · body", body.html)
      surfaceIds.foreach { sid =>
        r.renderSurfaceTraced(sid, states, Selections.none, f)
          .foreach(t => section(s"$houseName · surface $sid", t.html))
      }
      nodes.foreach { id =>
        r.renderNodeById(id, states, Selections.none, SlotForm.Patch, f)
          .foreach(html => section(s"$houseName · patch $id", html))
      }
    }
    RenderGoldenSuite
      .normaliseHashes(out.result())
      .replaceAll("""(?<=>)(?=<(div class="fh-cell|article))""", "\n")
      .replaceAll("""(?<=") (?=data-)""", "\n  ")
  }

  test("a new transform hash reorders a seed but not its golden") {
    def page(a: String, b: String, seed: String) =
      s"""<div data-signals="{_e: {light: {k: {$seed}}}}" data-text="$$_e.light.k.$b" data-x="$$_e.light.k.$a"></div>"""
    assertEquals(
      RenderGoldenSuite.normaliseHashes(
        page(
          "t0000000a",
          "t0000000b",
          "state: 'on', t0000000a: true, t0000000b: 'x, y'"
        )
      ),
      RenderGoldenSuite.normaliseHashes(
        page(
          "tffffffff",
          "t00000001",
          "state: 'on', t00000001: 'x, y', tffffffff: true"
        )
      )
    )
  }

  test("every fixture dashboard renders as its golden") {
    for ((name, d) <- dashboards)
      TextSnapshot.check(
        TextSnapshot.resources / "goldens" / s"$name.html",
        golden(d)
      )
  }
}

object RenderGoldenSuite {

  import scala.util.matching.Regex.quoteReplacement

  /** What HA keeps on an entity whose integration it lost. */
  val RegistryAttributes: Set[String] = Set(
    "friendly_name",
    "icon",
    "device_class",
    "unit_of_measurement",
    "supported_features",
    "supported_color_modes"
  )

  private val TransformHash = """\bt[0-9a-f]{8}\b""".r
  private val Seed = """data-signals="[^"]*"""".r
  private val Leaf = """\{([^{}]*)\}""".r
  private val Entry = """(\S+?): ('(?:\\.|[^'\\])*'|true|false)""".r

  /** `t<hash>` becomes `t#<n>`, `n` counting distinct hashes in the order the
    * text outside the seeds first names them: a seed sorts its paths, so the
    * hashes under one entity come in hash order, and would renumber everything
    * after them when they move. The seeds' entries are then put in `n` order.
    */
  def normaliseHashes(text: String): String = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    def n(hash: String) = seen.getOrElseUpdate(hash, seen.size + 1)
    TransformHash.findAllIn(Seed.replaceAllIn(text, "")).foreach(n)
    val named = TransformHash.replaceAllIn(text, m => s"t#${n(m.matched)}")
    Seed.replaceAllIn(named, m => quoteReplacement(sortLeaves(m.matched)))
  }

  /** `state` sorts before every `t…` in a seed, so it stays first. */
  private def sortLeaves(seed: String): String =
    Leaf.replaceAllIn(
      seed,
      m => {
        val entries = Entry.findAllMatchIn(m.group(1)).toList
        // Left alone unless every byte between the braces is an entry.
        quoteReplacement(
          if (entries.map(_.matched).mkString(", ") != m.group(1)) m.matched
          else
            entries
              .sortBy(_.group(1).stripPrefix("t#").toIntOption.getOrElse(0))
              .map(_.matched)
              .mkString("{", ", ", "}")
        )
      }
    )
}
