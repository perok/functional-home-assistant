package fh.view.runtime

import fh.view.query.QuerySnapshot
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  SlotSource
}
import fh.view.testkit.TestIds.given

import scala.concurrent.duration.*

/** What the per-slug recorder does with one frame. What it costs across viewers
  * is `RenderCacheContentionSuite`'s.
  */
class SharedPassSuite extends ServerHarness {

  // One entity changes during the connect handshake and never again, so nothing
  // later heals it; the other is a barrier proving the connection is live.
  private def twoLeafDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        List("sensor.a", "sensor.b").map(e =>
          LayoutNode.Component(
            "card",
            slots = Map("state" -> SlotSource(Some(e)))
          )
        )*
      )
    )
  )

  private val ab0 = Map(
    "sensor.a" -> es("sensor.a", "a0"),
    "sensor.b" -> es("sensor.b", "b0")
  )

  test("a session records what its own connection was actually sent") {
    // The digest kept must be of the bytes that went out, and the position the
    // version they were rendered at.
    live(twoLeafDash, ab0) { ts =>
      for {
        _ <- ts.connect()
        _ <- ts.change("sensor.a", "a1")
        session <- ts.sessions.forSlug(ts.slug).map(_.head)
        at <- session.position.get
        held <- session.holds.get
        now <- ts.store.current
      } yield {
        val renderer = Renderer.create(twoLeafDash)
        def rendered(id: NodeId) =
          renderer
            .renderNodeById(id, now.entities, fragments = QuerySnapshot.empty)
            .map(Held.of)
        // The repaint claimed everything it painted, and the tick re-claimed
        // the one node that moved at its new bytes: a record of what this
        // connection was sent.
        assertEquals(held.get("c_0"), rendered("c_0"), clue = held)
        // So the claim above is not "everything, re-derived".
        assertEquals(held.get("c_1"), rendered("c_1"), clue = held)
        assertEquals(at, now.version)
      }
    }
  }

  test(
    "a change published during the connect handshake still reaches the connection"
  ) {
    // The route computes the opening and returns before the body is pulled. A
    // change in that window must still arrive, or the client shows a
    // pre-connect value with nothing to say so.
    val missed = "gap_value_xq"
    val barrier = "barrier_value_xq"
    live(twoLeafDash, ab0) { ts =>
      for {
        _ <- ts.awaitChangeSubscribers(1)
        slug <- ts.server.liveSlug(ts.slug)
        rung <- slug.doorbell.get
        resp <- ts.sse()
        _ <- ts.fake.emit("sensor.a", missed)
        // The record proves the frame was written before the body was pulled,
        // rather than the test racing a slow recorder.
        _ <- (IO.sleep(5.millis) *> slug.doorbell.get).iterateUntil(_ > rung)
        seen <- Ref[IO].of("")
        // Pulling the body registers the subscription.
        reader <- resp.body
          .through(fs2.text.utf8.decode)
          .evalMap(chunk => seen.updateAndGet(_ + chunk))
          .exists(_.contains(barrier))
          .compile
          .drain
          .start
        _ <- ts.awaitSharedSubscribers(1)
        _ <- ts.fake.emit("sensor.b", barrier)
        _ <- reader.joinWithNever
        text <- seen.get
      } yield assert(text.contains(missed), clue = text)
    }
  }

  test("a connection that stops reading cannot stall the store") {
    // `Topic.publish1` blocks on a full subscriber, so a bounded per-connection
    // subscription would let one stalled browser freeze the HA feed for
    // everyone.
    //
    // A per-session node (the tabs host bakes the client's panel), so the
    // per-connection pass really emits.
    live(tabsDash, Map("sensor.a" -> es("sensor.a", "a0"))) { ts =>
      ts.sse().flatMap { resp =>
        IO.deferred[Unit].flatMap { stop =>
          val reads = resp.body
            .evalTap(_ =>
              stop.tryGet.flatMap(s => IO.sleep(1.minute).whenA(s.isDefined))
            )
            .compile
            .drain
          reads.background.surround {
            for {
              _ <- ts.awaitChangeSubscribers(1)
              _ <- stop.complete(())
              // The reader stalls only on the next event, and the keepalive is
              // too slow.
              _ <- ts.fake.emit("sensor.a", "engage-the-stall")
              _ <- IO.sleep(1.second)
              _ <- (1 to 300).toList
                .traverse_(i => ts.fake.emit("sensor.a", s"v$i"))
              // The fake queues whatever the store does, so the claim is the
              // store taking the last one.
              _ <- (IO.sleep(10.millis) *> ts.store.current)
                .iterateUntil(
                  _.entities.get("sensor.a").exists(_.state == "v300")
                )
                .timeout(15.seconds)
            } yield ()
          }
        }
      }
    }
  }
}
