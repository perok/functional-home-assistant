package fh.view.runtime

import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.model.{Dashboard, NodeId, SlotRead}
import fh.view.testkit.{FixtureEntries, PklFixture, TextSnapshot}

/** What the test dashboards render, checked in: each one's body, every surface,
  * and every node's patch, at the house as fixtured, with every on/off flipped,
  * and unavailable. The wire snapshots say what Pkl built; these say what a
  * viewer is sent, so a change to how a slot's value is modelled can be shown
  * to leave the bytes alone, or name the ones it moves. A section the other
  * houses render as the fixtured one does is named, not repeated.
  *
  * A display signal's name carries a hash of its transform's key, so each hash
  * is replaced by its order of first appearance: the goldens hold the sharing,
  * not the key's spelling. A line break before each cell, card and `data-`
  * attribute is the goldens' own, so a diff names what moved.
  */
class RenderGoldenSuite extends munit.FunSuite {

  private val Fixtured = "as fixtured"

  private def houses(
      e: FixtureEntries.Entry
  ): List[(String, Map[String, EntityState])] = {
    val base = e.entities.map(x => x.entityId -> x.toEntityState).toMap
    val flip = Map("on" -> "off", "off" -> "on")
    List(
      Fixtured -> base,
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

  private def golden(e: FixtureEntries.Entry): String = {
    val d = PklFixture.buildDashboard(e.slug, e.source, e.dump)
    val r = Renderer(
      d.validated().fold(errs => fail(errs.mkString("; ")), identity)
    )
    val surfaceIds = d.surfaces.keys.toList.sorted
    val out = new StringBuilder
    val fixtured = scala.collection.mutable.Map.empty[String, String]
    def section(house: String, part: String, body: String): Unit =
      if (house != Fixtured && fixtured.get(part).contains(body)) {
        val _ = out.append(s"<!-- ==== $house · $part: as fixtured ==== -->\n")
      } else {
        if (house == Fixtured) fixtured(part) = body
        val _ = out.append(s"<!-- ==== $house · $part ==== -->\n").append(body)
        if (!body.endsWith("\n")) { val _ = out.append('\n') }
      }
    for ((houseName, states) <- houses(e)) {
      val shared = Shared.of(states)
      val body =
        r.renderBodyTraced(
          shared,
          Selections.none,
          RenderGoldenSuite.answers(r, d, shared, Nil)
        )
      val nodes =
        (body.own.keys ++ surfaceIds.flatMap(
          r.surfaceNodeIds
        )).toList.distinct.sorted
      val f = RenderGoldenSuite.answers(r, d, shared, nodes)
      section(houseName, "body", body.html)
      surfaceIds.foreach { sid =>
        r.renderSurfaceTraced(sid, shared, Selections.none, f)
          .foreach(t => section(houseName, s"surface $sid", t.html))
      }
      nodes.foreach { id =>
        r.renderNodeById(id, shared, Selections.none, SlotForm.Patch, f)
          .foreach(html => section(houseName, s"patch $id", html))
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

  test("every test dashboard renders as its golden") {
    for (e <- FixtureEntries.all)
      TextSnapshot.check(
        TextSnapshot.resources / "goldens" / s"${e.slug}.html",
        golden(e)
      )
  }
}

object RenderGoldenSuite {

  import scala.util.matching.Regex.quoteReplacement

  /** Every read anything below asks, answered alike: what a chart draws is the
    * stage's business, not the slot's.
    */
  def answers(
      r: Renderer,
      d: Dashboard,
      shared: Shared,
      nodes: List[NodeId]
  ): FetchSnapshot = {
    val env = r.vars.env(Map.empty)
    val reads: List[SlotRead] =
      r.fetchesForPage(r.surfaces.selectedSurfaces(), shared, env) ++
        d.surfaces.keys.toList.flatMap(
          r.fetchesForSurface(_, shared, Selections.none, env)
        ) ++
        r.readsForPull(nodes, Nil, shared, Selections.none, env)
    FetchSnapshot.of(reads.map(_ -> Staged(1L, "<svg/>")).toMap, env)
  }

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
