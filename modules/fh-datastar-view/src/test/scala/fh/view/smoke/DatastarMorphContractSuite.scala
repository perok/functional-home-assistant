package fh.view.smoke

import fh.view.smoke.BrowserSuite.{asJsInt, asJsStrings}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{host, port}
import com.microsoft.playwright.Page
import fh.view.runtime.{Datastar, FrontendAssets, PatchMode, SseFrame}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** The Datastar behaviours the leaf/structure split rests on (ADR 0012), so a
  * failure names exactly what broke. On a bundle upgrade
  * (`src/js/vendor/datastar/`) a failure means the split is unsafe, not that
  * the test needs relaxing.
  *
  *   1. '''Sibling isolation.''' A top-level patch touches only the element
  *      matching its own id. The control patches the parent instead and must
  *      wipe the sibling, so the test cannot pass vacuously.
  *   2. '''`data-ignore-morph` is total.''' A client-owned subtree survives an
  *      ancestor morph, and patches aimed inside it are dropped. The vendored
  *      docs get the second half wrong (`attributes.md:218`).
  *
  * Standalone: a bare page and an SSE stream this test controls, so it measures
  * Datastar and none of our server.
  */
class DatastarMorphContractSuite extends BrowserSuite {

  test("a patch at one child leaves its sibling untouched") {
    val page =
      """<div id="h">
        |  <div id="h_head">OLD</div>
        |  <div id="h_panel"><p id="h_keep">KEEP</p></div>
        |</div>
        |<div id="c">
        |  <div id="c_head">OLD</div>
        |  <div id="c_panel"><p id="c_keep">KEEP</p></div>
        |</div>""".stripMargin

    val patches = List(
      Datastar.patch("""<div id="h_head">NEW</div>"""),
      // Control: patching the structure, with the sibling region rendered
      // empty.
      Datastar.patch(
        """<div id="c"><div id="c_head">NEW</div><div id="c_panel"></div></div>"""
      )
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        head <- text(p, "#h_head")
        _ <- IO(assertEquals(head, "NEW", "the leaf's patch must apply"))

        kept <- text(p, "#h_keep")
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a patch at one child must not touch its sibling"
          )
        )

        // (3) Control: without it (2) could pass vacuously if morphs stopped
        // wiping.
        control <- text(p, "#c_keep")
        _ <- IO(
          assertEquals(
            control,
            "<gone>",
            "control: patching the parent wipes the sibling, so targeting matters"
          )
        )
      } yield ()
    }
  }

  test("ONE patch event morphs several sibling elements, each by its own id") {
    // One SSE event for a whole HA frame needs Datastar to morph each top-level
    // element in `elements` against its own id. The docs say nothing about
    // siblings, so this is the empirical answer.
    val page =
      """<div id="one">OLD1</div>
        |<div id="two">OLD2</div>
        |<div id="three">OLD3</div>""".stripMargin

    val patches = List(
      Datastar.patchElements(
        """<div id="one">NEW1</div><div id="three">NEW3</div>"""
      )
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        one <- text(p, "#one")
        three <- text(p, "#three")
        _ <- IO(assertEquals(one, "NEW1", "the first element must morph"))
        _ <- IO(
          assertEquals(three, "NEW3", "so must the second, by its own id")
        )
        // Not a wholesale replace: an unmentioned element keeps its content and
        // its position.
        two <- text(p, "#two")
        _ <- IO(
          assertEquals(two, "OLD2", "an unmentioned sibling must be untouched")
        )
      } yield ()
    }
  }

  test(
    "data-ignore-morph protects a client-owned subtree, in both directions"
  ) {
    val page =
      """<div id="w">
        |  <span id="w_label">OLD</span>
        |  <div id="w_body" data-ignore-morph><p id="w_keep">KEEP</p></div>
        |</div>""".stripMargin

    val patches = List(
      // The attribute must be on the incoming fragment too: only a literal
      // satisfies the both-sides check.
      Datastar.patch(
        """<div id="w"><span id="w_label">NEW</span>""" +
          """<div id="w_body" data-ignore-morph></div></div>"""
      ),
      Datastar.patch("""<p id="w_keep">CHANGED</p>""")
    )

    served(page, patches).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        label <- text(p, "#w_label")
        _ <- IO(
          assertEquals(label, "NEW", "the ancestor morph must still apply")
        )

        kept <- text(p, "#w_keep")
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a client-owned subtree must survive an ancestor morph"
          )
        )

        // (3) The server cannot patch into it: fatal for a server-filled panel,
        // required for a host whose JS owns the DOM.
        _ <- IO(
          assertEquals(
            kept,
            "KEEP",
            "a patch aimed inside a protected subtree must be dropped"
          )
        )
      } yield ()
    }
  }

  private def text(page: Page, selector: String): IO[String] =
    IO.blocking(
      Option(page.querySelector(selector)).fold("<gone>")(_.innerText)
    )

  private def eventually[A](io: IO[A], timeout: FiniteDuration = 10.seconds)(
      cond: A => Boolean
  ): IO[A] =
    Stream
      .repeatEval(io <* IO.sleep(20.millis))
      .filter(cond)
      .head
      .compile
      .lastOrError
      .timeout(timeout)

  private def shell(body: String) =
    s"""<!doctype html><html><head><script type="module" src="/datastar.js"></script></head>
       |<body data-init="@get('/sse')">
       |$body
       |<div id="done">no</div>
       |</body></html>""".stripMargin

  /** The module production serves ([[fh.view.runtime.Server.DatastarScript]]):
    * the vendored bundle as our build lowers it. Our own attributes in it act
    * only on `data-fh-*`, which these pages never write.
    */
  private val bundle: IO[String] =
    IO.blocking(FrontendAssets.content("datastar"))

  test("an action's datastar frames are applied on 2xx and DROPPED on 4xx") {
    // Whether a 4xx body is parsed (ADR 0025). The bundle's `onopen` suggests
    // it is; running it says otherwise. The 2xx half is the control: same body,
    // route and assertion, so a failure is about the status.
    //
    // So a non-200's body is not a channel, and a refusal that wants to say
    // anything must answer 200 (ADR 0024).
    val page =
      """<button id="ok" data-on:click="@post('/allow')">ok</button>
        |<button id="no" data-on:click="@post('/refuse')">no</button>
        |<div id="allowed" data-text="$allowed"></div>
        |<div id="refused" data-text="$refused"></div>""".stripMargin

    def frame(name: String) =
      fs2.Stream
        .emit(Datastar.patchSignals(s"{$name: 'applied'}"))
        .covary[IO]

    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "allow" =>
        Ok(frame("allowed")).map(
          _.withContentType(`Content-Type`(MediaType.`text/event-stream`))
        )
      case POST -> Root / "refuse" =>
        BadRequest(frame("refused")).map(
          _.withContentType(`Content-Type`(MediaType.`text/event-stream`))
        )
    }

    servedWith(page, Nil, routes).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        _ <- IO.blocking(p.locator("#no").click())
        _ <- IO.blocking(p.locator("#ok").click())
        // Sent second, so once it has landed the 4xx one has had its chance.
        _ <- eventually(text(p, "#allowed"))(_ == "applied")
        refused <- text(p, "#refused")
      } yield assertEquals(
        refused,
        "",
        "a 4xx body's datastar frames must NOT reach the store"
      )
    }
  }

  test(
    "a data-effect that clears the signal it reads settles, and survives a race"
  ) {
    // Pending clears itself once the committed value catches up (ADR 0025). The
    // effect reads and writes the same signal, the shape that loops, so this
    // counts its runs. The clear is derived rather than sent because, with two
    // taps in flight, a commit for the first must leave the second's pending
    // alone.
    val clear =
      "window.__runs = (window.__runs || 0) + 1; " +
        "$_g__pending !== '' && $ui_g == $_g__pending && ($_g__pending = '')"

    val page =
      s"""<div data-signals="{ui_g: '', _g__pending: ''}"></div>
         |<div data-effect="$clear"></div>
         |<div id="shown" data-text="$$_g__pending || $$ui_g"></div>
         |<div id="pending" data-text="$$_g__pending"></div>
         |<div id="committed" data-text="$$ui_g"></div>
         |<button id="tapA" data-on:click="$$_g__pending = 'a'">a</button>
         |<button id="tapB" data-on:click="$$_g__pending = 'b'">b</button>
         |<button id="commitA" data-on:click="@post('/commit/a')">ca</button>
         |<button id="commitB" data-on:click="@post('/commit/b')">cb</button>""".stripMargin

    val routes = HttpRoutes.of[IO] { case POST -> Root / "commit" / which =>
      Ok(
        fs2.Stream
          .emit(Datastar.patchSignals(s"{ui_g: '$which'}"))
          .covary[IO]
      ).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
    }

    def click(p: Page, id: String) = IO.blocking(p.locator(id).click())

    servedWith(page, Nil, routes).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        _ <- click(p, "#tapA")
        _ <- eventually(text(p, "#shown"))(_ == "a")

        _ <- click(p, "#tapB")
        _ <- eventually(text(p, "#shown"))(_ == "b")

        // (3) The first commit lands and pending must survive it. Gated on the
        // committed value: pending is already 'b', so gating on it holds before
        // the frame arrives, passes whether or not `/commit/a` was served, and
        // lets the commits land out of order.
        _ <- click(p, "#commitA")
        _ <- eventually(text(p, "#committed"))(_ == "a")
        held <- text(p, "#pending")
        _ <- IO(
          assertEquals(
            held,
            "b",
            "a commit for an OVERTAKEN tap must not clear the pending one"
          )
        )
        shown <- text(p, "#shown")
        _ <- IO(
          assertEquals(shown, "b", "…so the display still shows what was asked")
        )

        _ <- click(p, "#commitB")
        _ <- eventually(text(p, "#pending"))(_ == "")
        settled <- text(p, "#shown")
        _ <- IO(assertEquals(settled, "b", "the committed value takes over"))

        // (5) A looping effect would still be running; the count stops growing.
        runs <- IO.blocking(p.evaluate("window.__runs").asJsInt)
        _ <- IO.sleep(300.millis)
        later <- IO.blocking(p.evaluate("window.__runs").asJsInt)
        _ <- IO {
          assertEquals(
            later,
            runs,
            s"the effect must stop re-running (ran $runs)"
          )
          assert(runs < 20, s"the effect settled but ran $runs times")
        }
      } yield ()
    }
  }

  test(
    "null DELETES a signal and orphans its bindings; '' is a present attribute"
  ) {
    // Three facts about the pinned bundle, and a control. They decide whether a
    // pending signal may share a name with an ADR 0019 `busy` one (ADR 0025).
    //
    //   1. `data-attr` removes the attribute when an expression evaluates to
    //      null.
    //   2. `''` is present to `data-attr` (`disabled=""`) but falsy to
    //      `data-style`.
    //   3. Assigning null deletes the signal and orphans every binding
    //      (`if (a == null) delete r[o]`). Reading the name re-creates it as
    //      `''`, so the check reads the value, driven from a second signal. A
    //      server-sent `{"s": null}` does the same.
    //
    // Only that one signal dies, which is what makes it hard to spot. The
    // control runs last: a throwing expression does break the page, so it
    // proves "nothing was reported" is a measurement.
    def page(nullBtn: String) =
      s"""<div data-signals="{ s: 'v', probe: 0 }"></div>
         |<input id="i" data-attr:disabled="$$s" />
         |<input id="j" data-attr:disabled="$$s === 'HIDE' ? null : $$s" />
         |<div id="r" data-text="$$probe + ':' + JSON.stringify($$s)"></div>
         |<button id="empty" data-on:click="$$s = ''">e</button>
         |<button id="val" data-on:click="$$s = 'v'">v</button>
         |<button id="hide" data-on:click="$$s = 'HIDE'">h</button>
         |<button id="probe" data-on:click="$$probe = $$probe + 1">p</button>
         |<button id="boom" data-on:click="$$s = JSON.parse('{')">b</button>
         |$nullBtn""".stripMargin

    val clientNull = """<button id="nul" data-on:click="$s = null">n</button>"""
    val serverNull =
      """<button id="nul" data-on:click="@post('/nullify')">n</button>"""

    // One patch carrying both, so `probe` moving proves the null landed. A
    // POST's response says nothing about Datastar having applied it; that race
    // failed on CI (`server: null DELETED the signal`, obtained `1:"v"`).
    val routes = HttpRoutes.of[IO] { case POST -> Root / "nullify" =>
      Ok(
        fs2.Stream
          .emit(Datastar.patchSignals("""{"s": null, "probe": 1}"""))
          .covary[IO]
      ).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
    }

    def attr(p: Page, id: String) =
      IO.blocking(Option(p.locator(id).getAttribute("disabled")))
    def click(p: Page, id: String) = IO.blocking(p.locator(id).click())

    // The page's own report of the state under test, and so the barrier. ADR
    // 0009 bans sleeps.
    def awaitR(p: Page, expected: String) =
      eventually(text(p, "#r"))(_ == expected).void

    def run(body: String, nullIsServerSent: Boolean) =
      servedWith(body, Nil, routes).use { case (p, uri) =>
        for {
          errs <- IO(scala.collection.mutable.ListBuffer.empty[String])
          _ <- IO.blocking(
            p.onConsoleMessage(m =>
              if (m.`type`() == "error") { val _ = errs += m.text() }
            )
          )
          _ <- IO.blocking(p.onPageError(e => { val _ = errs += e }))
          _ <- IO.blocking(p.navigate(uri.renderString))
          _ <- eventually(text(p, "#done"))(_ == "yes")

          start <- attr(p, "#i")
          blank <- click(p, "#empty") *> awaitR(p, """0:""""") *> attr(p, "#i")
          _ <- click(p, "#val") *> awaitR(p, """0:"v"""")
          // (1) The signal moves to "HIDE", so `#r` is a real barrier and the
          // "untouched" read happens after the page has acted.
          byExpr <- click(p, "#hide") *> awaitR(p, """0:"HIDE"""") *> attr(
            p,
            "#j"
          )
          untouched <- attr(p, "#i")
          _ <- click(p, "#val") *> awaitR(p, """0:"v"""")
          // (3) Read `s` through an effect driven by a different signal:
          // anything bound to `s` is orphaned. The client sets `probe` in the
          // same handler that nulls `s`, the server ships both in one patch, so
          // either way "probe moved" implies "the null landed".
          _ <- click(p, "#nul")
          _ <- if (nullIsServerSent) IO.unit else click(p, "#probe")
          readBack <- eventually(text(p, "#r"))(_.startsWith("1:"))
          // `s` is already "" here, so `#empty` moves nothing of its own; probe
          // reaching 2 proves the click was processed.
          orphaned <- click(p, "#empty") *> click(p, "#probe") *>
            awaitR(p, """2:""""") *> attr(p, "#i")
          quiet <- IO(errs.toList)
          // Polling for the error is the assertion; a timeout is the failure.
          _ <- click(p, "#boom") *> eventually(IO(errs.toList))(_.nonEmpty)
          control <- IO(errs.toList)
        } yield (
          start,
          blank,
          byExpr,
          untouched,
          readBack,
          orphaned,
          quiet,
          control
        )
      }

    for {
      fromClient <- run(page(clientNull), nullIsServerSent = false)
      fromServer <- run(page(serverNull), nullIsServerSent = true)
    } yield List(("client", fromClient), ("server", fromServer)).foreach {
      case (
            who,
            (
              start,
              blank,
              byExpr,
              untouched,
              readBack,
              orphaned,
              quiet,
              control
            )
          ) =>
        assertEquals(start, Some("v"), s"$who: a plain string is the value")
        assertEquals(blank, Some(""), s"$who: '' PRESENTS the attribute")
        assertEquals(
          byExpr,
          None,
          s"$who: an expression yielding null removes it"
        )
        assertEquals(
          untouched,
          Some("HIDE"),
          s"$who: and never touched the signal"
        )
        // The page is alive and `s` came back as "", not "v": the key was
        // removed and re-created.
        assertEquals(readBack, """1:""""", s"$who: null DELETED the signal")
        assertEquals(
          orphaned,
          Some("v"),
          s"$who: and every binding on it is orphaned — this rewrite never lands"
        )
        assertEquals(quiet, Nil, s"$who: all of it with nothing reported")
        assert(
          control.nonEmpty,
          s"$who: CONTROL — a throwing expression IS reported, so that silence is real"
        )
    }
  }

  test("__ifmissing loses to an earlier READER, and a parent's seed cannot") {
    // Why the `ui.<id>` param went missing on a slow load
    // (`UiSmokeSuite."tabs: a tap the server REFUSES"`), read out of the
    // pinned bundle:
    //
    //   - the store proxy's `get` creates a missing key:
    //     `(!x(r,o) || r[o]()==null) && (r[o]=he(""), K(t+o,""), …)`
    //   - `__ifmissing`'s merge then declines, because the key exists:
    //     `Nt=(e,t,n,r,s)=> … s && x(n,t) || (n[t]=e)`
    //
    // So any read before the seed applies wins the key and the signal is `''`
    // for the life of the page. Forced here by DOM order, so it is a fact
    // rather than a flake.
    val page =
      """<div id="early" data-text="'r=' + JSON.stringify($sibling)"></div>
        |<div data-signals__ifmissing="{ sibling: 7 }"></div>
        |<div data-signals__ifmissing="{ nested: 7 }">
        |  <div id="inner" data-text="'r=' + JSON.stringify($nested)"></div>
        |</div>
        |<div id="late" data-text="'r=' + JSON.stringify($sibling)"></div>""".stripMargin

    served(page, Nil).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        sibling <- text(p, "#late")
        nested <- text(p, "#inner")
      } yield {
        assertEquals(
          sibling,
          """r=""""",
          "a reader BEFORE the seed takes the key, and __ifmissing then declines — " +
            "the seed value is lost for the life of the page"
        )
        assertEquals(
          nested,
          "r=7",
          "a seed on an ANCESTOR is applied before its descendants read, so it always wins"
        )
      }
    }
  }

  test("data-bind makes a co-located data-attr:value inert from the start") {
    // A range input's position, measured. `value` is a content attribute, inert
    // once the dirty-value flag is set through the IDL, and `data-bind` writes
    // `.value` on its first pass, so on an input carrying both bindings the
    // attribute never moves the thumb: inert at t=0, not after a drag. The
    // slider carries both (`data-attr:value` committed, `data-bind` for
    // `_slide`).
    val page =
      """<div data-signals="{ a: '20', b: '80' }"></div>
        |<input id="both" type="range" min="0" max="100" data-attr:value="$a" data-bind="b" />
        |<input id="attr" type="range" min="0" max="100" data-attr:value="$a" />
        |<button id="bumpA" data-on:click="$a = '55'">a</button>
        |<button id="bumpB" data-on:click="$a = '56'">b</button>""".stripMargin

    def prop(p: Page, id: String) =
      IO.blocking(p.locator(id).evaluate("el => el.value").toString)
    def attrOf(p: Page, id: String) =
      IO.blocking(Option(p.locator(id).getAttribute("value")))

    // The attribute is what each bump provably moves, so it is the barrier.
    // `bumpB` sends a different value: an unchanged signal writes nothing, and
    // waiting on it could never fail.
    def awaitAttr(p: Page, id: String, expected: String) =
      eventually(attrOf(p, id))(_.contains(expected)).void

    served(page, Nil).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")

        bothStart <- prop(p, "#both")
        attrStart <- prop(p, "#attr")
        // The attribute is written on both; it loses on the bound one.
        bothAttr <- attrOf(p, "#both")
        bothAfter <- IO.blocking(p.locator("#bumpA").click()) *>
          awaitAttr(p, "#attr", "55") *> prop(p, "#both")
        // On the unbound input the same write moves it, so `data-attr:value` is
        // not broken in general.
        attrAfter <- prop(p, "#attr")
        // ...until set through the IDL: the dirty flag, as the spec says.
        _ <- IO.blocking(p.locator("#attr").fill("10"))
        attrDirty <- IO.blocking(p.locator("#bumpB").click()) *>
          awaitAttr(p, "#attr", "56") *> prop(p, "#attr")
      } yield {
        assertEquals(
          bothStart,
          "80",
          "data-bind wins on first paint — the attribute never positions a bound input"
        )
        assertEquals(
          bothAttr,
          Some("20"),
          "and data-attr DID write it; the attribute is present and simply ignored"
        )
        assertEquals(
          bothAfter,
          "80",
          "a later write to the attr-bound signal still moves nothing"
        )
        assertEquals(
          attrStart,
          "20",
          "CONTROL: unbound, the attribute does position the thumb"
        )
        assertEquals(
          attrAfter,
          "55",
          "CONTROL: and keeps positioning it while the input stays clean"
        )
        assertEquals(
          attrDirty,
          "10",
          "CONTROL: once the value is set through the IDL, the dirty flag makes it inert"
        )
      }
    }
  }

  private def served(
      body: String,
      patches: List[SseFrame],
      spacing: FiniteDuration = 50.millis
  ): Resource[IO, (Page, Uri)] =
    servedWith(body, patches, HttpRoutes.empty[IO], spacing)

  private def servedWith(
      body: String,
      patches: List[SseFrame],
      extra: HttpRoutes[IO],
      spacing: FiniteDuration = 50.millis
  ): Resource[IO, (Page, Uri)] =
    for {
      js <- bundle.toResource
      // Patched last, so its arrival means the whole sequence was processed.
      all = patches :+ Datastar.patch("""<div id="done">yes</div>""")
      routes = HttpRoutes.of[IO] {
        case GET -> Root =>
          Ok(shell(body)).map(
            _.withContentType(`Content-Type`(MediaType.text.html))
          )
        case GET -> Root / "datastar.js" =>
          Ok(js).map(
            _.withContentType(`Content-Type`(MediaType.application.javascript))
          )
        case GET -> Root / "sse" =>
          Ok(
            Stream
              .emits(all)
              .covary[IO]
              .metered(spacing)
              .append(Stream.never[IO])
          )
      }
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp((extra <+> routes).orNotFound)
        .withShutdownTimeout(0.seconds)
        .build
      context <- Resource.make(IO.blocking(browser.newContext()))(c =>
        IO.blocking(c.close())
      )
      page <- Resource.make(IO.blocking(context.newPage()))(p =>
        IO.blocking(p.close())
      )
    } yield (page, bound.baseUri)

  test("__ifmissing only seeds a signal nothing has read yet") {
    // Why the tabs seed asserts instead of initialising: a tabs bar reads
    // `$ui_<id>` and renders before the panel that seeds it, so an
    // `__ifmissing` seed finds the key present, as "", and declines.
    val page =
      """<div id="reader" data-text="$late"></div>
        |<div data-signals__ifmissing="{ late: 1 }"></div>
        |<div id="reader2" data-text="$asserted"></div>
        |<div data-signals="{ asserted: 1 }"></div>
        |<div id="together" data-signals__ifmissing="{ own: 1 }" data-text="$own"></div>
        |<div data-signals__ifmissing="{ kid: 1 }"><span id="child" data-text="$kid"></span></div>""".stripMargin

    served(page, Nil).use { case (p, uri) =>
      for {
        _ <- IO.blocking(p.navigate(uri.renderString))
        _ <- eventually(text(p, "#done"))(_ == "yes")
        late <- text(p, "#reader")
        asserted <- text(p, "#reader2")
        own <- text(p, "#together")
        kid <- text(p, "#child")
        _ <- IO {
          assertEquals(
            late,
            "",
            "__ifmissing must not seed an already-read signal"
          )
          assertEquals(
            asserted,
            "1",
            "a plain seed asserts regardless of readers"
          )
          // On one element, signals apply before the reader.
          assertEquals(own, "1", "same-element seed beats its own reader")
          println(s"SPIKE|parent-seeds-child-reads = '$kid'")
        }
      } yield ()
    }
  }

  /** Why `Patches.signalFrame` goes first in a batch. A member insert carries
    * no seed (ADR 0017), so the frame is its only value; with `signalFrame`
    * last every other test stays green while an inserted card flashes blank.
    *
    * A `MutationObserver` reports `["", "42"]` for both orders, since the DOM
    * always passes through blank, so this samples per animation frame: what is
    * painted. The spacing is far wider than production's (see [[fillOrder]]),
    * so read "elements-first flashes" as the direction of the risk, not its
    * size.
    */
  private val recorder =
    """window.__seen = [];
      |window.__frames = [];
      |const sample = () => {
      |  const el = document.querySelector('#filled');
      |  const t = el ? el.textContent : null;
      |  if (t !== null && window.__frames[window.__frames.length - 1] !== t) {
      |    window.__frames.push(t);
      |  }
      |  requestAnimationFrame(sample);
      |};
      |requestAnimationFrame(sample);
      |new MutationObserver(() => {
      |  const el = document.querySelector('#filled');
      |  if (el) {
      |    const t = el.textContent;
      |    if (window.__seen[window.__seen.length - 1] !== t) window.__seen.push(t);
      |  }
      |}).observe(document, {subtree: true, childList: true, characterData: true});""".stripMargin

  /** Spaced far wider than the rest of the suite: rAF is throttled hard on a
    * loaded headless runner, and at the shared 50 ms CI reported `painted=[42]`
    * while the observer had `seen=["", "42"]`.
    */
  private def fillOrder(
      patches: List[SseFrame]
  ): IO[(String, List[String], List[String])] =
    served("""<div id="host"></div>""", patches, spacing = 500.millis).use {
      case (p, uri) =>
        def strings(js: String) = IO.blocking(p.evaluate(js).asJsStrings)
        for {
          _ <- IO.blocking(p.addInitScript(recorder))
          _ <- IO.blocking(p.navigate(uri.renderString))
          _ <- eventually(text(p, "#done"))(_ == "yes")
          shown <- text(p, "#filled")
          seen <- strings("() => window.__seen")
          frames <- strings("() => window.__frames")
        } yield (shown, seen, frames)
    }

  private val frame =
    Datastar.patchSignals("""{"_e":{"sensor":{"x":{"state":"42"}}}}""")

  private val fill = Datastar.patch(
    """<div id="filled" data-text="$_e.sensor.x.state"></div>""",
    PatchMode.Inner,
    Some("#host")
  )

  test(
    "a signal patched before anything reads it survives, and a later fill paints it once"
  ) {
    fillOrder(List(frame, fill)).map { case (shown, seen, painted) =>
      // The premise of patch-form fills: the store keeps a value nothing is
      // bound to yet, and the binding reads it when it mounts.
      assertEquals(shown, "42", s"a later-mounted binding must read the store")
      // No blank is painted, though the DOM passes through one.
      assertEquals(
        painted,
        List("42"),
        s"painted a blank; mutations were $seen"
      )
    }
  }

  test("the same two patches in the other order paint a blank first") {
    fillOrder(List(fill, frame)).map { case (shown, seen, painted) =>
      assertEquals(shown, "42", "the frame must still correct it")
      assert(
        painted.headOption.contains(""),
        s"expected a blank paint before the value; painted=$painted seen=$seen"
      )
    }
  }
}
