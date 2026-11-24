package fh.view.testkit

import cats.effect.{IO, Resource}
import fh.view.runtime.{JsIsolate, SseFrame}
import io.circe.{Json, JsonObject}
import org.graalvm.polyglot.{Context, Engine, Source, Value}
import org.jsoup.Jsoup
import org.jsoup.nodes.{DataNode, Document, Element, Node, TextNode}

import scala.jdk.CollectionConverters.*

/** A page for the model-based oracle, run by the page's own shell and the
  * vendored Datastar on a linkedom document in a GraalJS context
  * (`src/test/js/oracle`): the page GET loaded, then every frame the stream
  * sent applied in order, each handler, seed, binding and URL mirror doing what
  * it does in a browser.
  *
  * Nothing happens between calls: the page's timers are one queue that every
  * call here runs dry before it returns, so a page is always settled, and its
  * fetches wait for the host. The stream (`data-init`'s `@get`) is held open
  * and never answered; its frames arrive through [[apply]]. Any other request
  * waits in [[requests]] for [[answer]].
  *
  * [[view]] is what the oracle compares: `#dashboard` and the popup host as the
  * page shows them, in canonical form.
  */
final class FakePage private (context: Context, page: Value)
    extends AutoCloseable {

  private def call(name: String, args: AnyRef*): Value =
    page.getMember(name).execute(args*)

  private def settle(): Unit = {
    var steps = 0
    while (call("step").asBoolean) {
      steps += 1
      if (steps > FakePage.MaxSteps)
        throw IllegalStateException("the page's timers never settle")
    }
  }

  private val nullSignals = List.newBuilder[String]

  /** What the page logged as a warning or error — a patch that found no target
    * (a remove's absence is not one: set member removal relies on it), an
    * expression that threw — and any frame carrying a null signal.
    */
  def misses: List[String] =
    nullSignals.result() ++ io.circe.parser
      .decode[List[String]](call("problems").asString)
      .fold(throw _, identity)

  def signal(path: String): Option[Json] =
    Option(call("signal", path))
      .filterNot(_.isNull)
      .map(v => io.circe.parser.parse(v.asString).fold(throw _, identity))

  def apply(frame: SseFrame): Unit =
    apply(frame.eventType.getOrElse(""), frame.data.getOrElse(""))

  def apply(eventType: String, data: String): Unit = {
    if (eventType == "datastar-patch-signals")
      io.circe.parser
        .parse(
          data.linesIterator
            .collect { case l if l.startsWith("signals ") => l.drop(8) }
            .mkString("\n")
        )
        .toOption
        .foreach(
          _.foldWith(FakePage.NullFinder)
            .foreach(p => nullSignals += s"null signal $p in $data")
        )
    val _ = call("apply", eventType, data)
    settle()
  }

  def html: String = call("html").asString

  private def doc: Document = Jsoup.parse(html)

  /** Every `@post` URL a click handler on the page holds, in document order:
    * what a viewer could press. Popup opens and closes included.
    */
  def presses: List[String] =
    doc
      .select("*")
      .asScala
      .toList
      .flatMap(
        _.attributes.asScala.toList
          .filter(_.getKey.startsWith("data-on:click"))
          .map(_.getValue)
      )
      .flatMap(FakePage.PostUrl.findAllMatchIn(_).map(_.group(1)))
      .distinct

  /** Clicks the first element whose click handler posts to `path`. */
  def press(path: String): Unit = {
    if (!call("press", path).asBoolean)
      throw IllegalArgumentException(s"nothing on the page posts to $path")
    settle()
  }

  /** The requests the page made that wait for an answer. */
  def requests: List[FakePage.Request] =
    io.circe.parser
      .parse(call("requests").asString)
      .flatMap(_.as[List[JsonObject]])
      .fold(throw _, identity)
      .map(o =>
        FakePage.Request(
          o("id").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(-1),
          o("method").flatMap(_.asString).getOrElse(""),
          o("path").flatMap(_.asString).getOrElse(""),
          o("body").flatMap(_.asString).getOrElse("")
        )
      )

  def answer(id: Int, status: Int, body: String): Unit = {
    val _ = call("answer", Int.box(id), Int.box(status), body)
    settle()
  }

  /** The request finds no server: its fetch rejects. */
  def unreachable(id: Int): Unit = {
    val _ = call("unreachable", Int.box(id))
    settle()
  }

  /** The `conn` the page was minted under. */
  def conn: String =
    signal("conn")
      .flatMap(_.asString)
      .getOrElse(throw IllegalStateException("no conn signal"))

  /** A handler asked the browser to reload: it does, at [[query]]. */
  def reloadAsked: Boolean = call("reloaded").asBoolean

  /** The stream request the page makes next, as Datastar builds it from the
    * signals its `filterSignals` lets through: the load's own, or, once that is
    * taken, the retry the stream's end makes. What a reconnect carries.
    */
  def streamRequest: String = {
    def take = Option(call("takeStream")).filterNot(_.isNull).map(_.asString)
    take.getOrElse {
      val _ = call("endStreams")
      settle()
      take.getOrElse(throw IllegalStateException("the page made no stream"))
    }
  }

  /** The query a refresh of this page sends: what its URL mirrors wrote. */
  def query: String =
    Option(java.net.URI.create(call("url").asString).getRawQuery)
      .filter(_.nonEmpty)
      .fold("")("?" + _)

  /** The tab's history, oldest first: each entry's query, and whether the URL
    * mirror pushed it.
    */
  def entries: List[(String, Boolean)] =
    io.circe.parser
      .parse(call("entries").asString)
      .flatMap(_.as[List[JsonObject]])
      .fold(throw _, identity)
      .map(o =>
        (
          o("query").flatMap(_.asString).getOrElse(""),
          o("pushed").flatMap(_.asBoolean).getOrElse(false)
        )
      )

  /** The ids of `id`'s element and its siblings, in page order. */
  def siblingIds(id: String): Option[List[String]] =
    Option(doc.getElementById(id))
      .flatMap(e => Option(e.parent))
      .map(_.children.asScala.toList.map(_.id))

  /** `#dashboard`, then the popup host, in canonical form. A seed is left out,
    * a morph not having to resend one whose values a signals frame moved; so
    * are a `data-bind` control's `value` and `checked`, which its handlers own.
    */
  def view: String = {
    val d = doc
    List("dashboard", "popups")
      .flatMap(id => Option(d.getElementById(id)).map(id -> _))
      .map { (id, e) =>
        val c = e.clone()
        c.select("*").asScala.foreach { el =>
          el.attributes.asScala.toList
            .map(_.getKey)
            .filter(_.startsWith("data-signals"))
            .foreach(k => { val _ = el.removeAttr(k) })
          if (el.hasAttr("data-bind")) {
            val _ = el.removeAttr("value").removeAttr("checked")
          }
        }
        s"#$id\n" + FakePage.canonical(c)
      }
      .mkString("\n")
  }

  def close(): Unit = context.close()
}

object FakePage {

  case class Request(id: Int, method: String, path: String, body: String)

  /** One engine and the parsed page script, for every page a suite loads. A
    * context per page keeps no state between pages; against a shared engine it
    * costs tens of milliseconds.
    */
  final class Loader private[FakePage] (engine: Engine, source: Source) {

    /** The page `html`, as served at `url` (absolute), settled. */
    def load(html: String, url: String): FakePage = {
      val context = Context.newBuilder("js").engine(engine).build()
      try {
        val bindings = context.getBindings("js")
        bindings.putMember("__html", html)
        bindings.putMember("__url", url)
        context.eval(source)
        val page = new FakePage(context, bindings.getMember("__page"))
        page.settle()
        page
      } catch {
        case e: Throwable => context.close(); throw e
      }
    }
  }

  /** An isolate where there is one; in-heap elsewhere (macOS), much slower. */
  val runtime: Resource[IO, Loader] =
    JsIsolate
      .engineOrInHeap(e =>
        IO.println(
          s"FakePage: no GraalJS isolate, running in-heap: ${e.getMessage}"
        )
      )
      .evalMap(engine =>
        IO.blocking {
          val text = Option(getClass.getResourceAsStream("/fh/oracle-page.js"))
            .map(in =>
              try new String(in.readAllBytes, "UTF-8")
              finally in.close()
            )
            .getOrElse(
              throw IllegalStateException(
                "fh/oracle-page.js is not on the test classpath (frontendTestBundle)"
              )
            )
          Loader(
            engine,
            Source.newBuilder("js", text, "oracle-page.js").cached(true).build()
          )
        }
      )

  private val MaxSteps = 100000

  /** The oracle's equality: nothing sent missed its target, and the page is the
    * one `fresh`, a new GET at its [[query]], gives.
    */
  def matches(page: FakePage, fresh: FakePage): hedgehog.Result = {
    val (a, b) = (page.view, fresh.view)
    val missed = page.misses
    hedgehog.Result
      .assert(missed.isEmpty)
      .log(missed.mkString("missed:\n", "\n", ""))
      .and(
        hedgehog.Result
          .diff(a, b)(_ == _)
          .log(firstDifference(a, b))
      )
  }

  private def firstDifference(a: String, b: String): String = {
    val (as, bs) = (a.linesIterator.toVector, b.linesIterator.toVector)
    val i =
      as.indices.find(i => i >= bs.size || as(i) != bs(i)).getOrElse(as.size)
    val from = math.max(0, i - 8)
    s"first difference at line $i (patched / fresh):\n" +
      as.slice(from, i + 4).mkString("\n") + "\n-----\n" + bs
        .slice(from, i + 4)
        .mkString("\n")
  }

  private val PostUrl = """@post\('([^']*)'""".r

  private object NullFinder extends Json.Folder[List[String]] {
    def onNull = List("")
    def onBoolean(value: Boolean) = Nil
    def onNumber(value: io.circe.JsonNumber) = Nil
    def onString(value: String) = Nil
    def onArray(value: Vector[Json]) = value.toList.flatMap(_.foldWith(this))
    def onObject(value: JsonObject) =
      value.toList.flatMap((k, v) =>
        v.foldWith(this).map(p => if (p.isEmpty) k else s"$k.$p")
      )
  }

  private def declarations(style: String): List[(String, String)] =
    style
      .split(';')
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { d =>
        val i = d.indexOf(':')
        if (i < 0) d -> "" else d.take(i).trim -> d.drop(i + 1).trim
      }

  /** One node per line, attributes sorted, a class list and a style as sets,
    * whitespace collapsed: what a browser shows does not depend on any of it.
    */
  def canonical(root: Element): String = {
    val sb = new StringBuilder
    def attrs(e: Element): String =
      e.attributes.asScala.toList
        .map(a =>
          a.getKey -> (a.getKey match
            case "class" =>
              a.getValue
                .split("\\s+")
                .filter(_.nonEmpty)
                .sorted
                .distinct
                .mkString(" ")
            case "style" =>
              declarations(a.getValue).sorted
                .map((k, v) => s"$k: $v")
                .mkString("; ")
            case _ => a.getValue)
        )
        .filterNot((k, v) => (k == "class" || k == "style") && v.isEmpty)
        .sortBy(_._1)
        .map((k, v) => s""" $k="$v"""")
        .mkString
    def line(depth: Int, s: String): Unit = {
      val _ = sb.append("  " * depth).append(s).append('\n')
    }
    def collapsed(s: String) = s.replaceAll("\\s+", " ").trim
    def walk(n: Node, depth: Int): Unit = n match
      case e: Element =>
        line(depth, s"<${e.tagName}${attrs(e)}>")
        e.childNodes.asScala.foreach(walk(_, depth + 1))
      case t: TextNode =>
        val s = collapsed(t.getWholeText)
        if (s.nonEmpty) line(depth, s"\"$s\"")
      case d: DataNode =>
        val s = collapsed(d.getWholeData)
        if (s.nonEmpty) line(depth, s"data \"$s\"")
      case _ => ()
    walk(root, 0)
    sb.toString
  }
}
