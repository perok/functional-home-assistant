package fh.view.testkit

import fh.view.runtime.SseFrame
import io.circe.{Json, JsonObject}
import org.jsoup.Jsoup
import org.jsoup.nodes.{DataNode, Document, Element, Node, TextNode}
import org.jsoup.parser.Parser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** A page as the pinned Datastar bundle keeps it, for the model-based oracle:
  * the page GET parsed, then every frame the stream sent applied in order —
  * `patch-elements` by mode and `patch-signals` into a signal map.
  *
  * Two things a browser does that the oracle depends on are modelled, both off
  * the bundle (v1.0.4): a seed (`data-signals`, `__ifmissing`) applies when its
  * element arrives or the attribute's value changes, so a morph that brings the
  * same seed back leaves the signal where the frames moved it; and the URL a
  * fresh GET is made at is what the page's `data-fh-url` mirrors write.
  *
  * [[view]] is what the oracle compares: `#dashboard` and the popup host with
  * every binding painted from the signal map (see [[FakePage.Paint]]).
  */
final class FakePage private (doc: Document) {

  private var signals: JsonObject = JsonObject.empty
  private val missed = List.newBuilder[String]

  /** Patches that found nothing to apply to (a remove's absence is not one: set
    * member removal relies on it), and any frame carrying a null signal.
    */
  def misses: List[String] = missed.result()

  def signal(path: String): Option[Json] =
    FakePage.lookup(signals, path.split('.').toList)

  def apply(frame: SseFrame): Unit =
    apply(frame.eventType.getOrElse(""), frame.data.getOrElse(""))

  /** A response body or stream read as text: each event in order. */
  def applySse(text: String): Unit =
    FakePage.events(text).foreach((e, d) => apply(e, d))

  /** Every `@post` URL the page's attributes hold, in document order: what a
    * viewer could press. Popup opens and closes included.
    */
  def presses: List[String] =
    doc
      .select("*")
      .asScala
      .toList
      .flatMap(_.attributes.asScala.toList.map(_.getValue))
      .flatMap(FakePage.PostUrl.findAllMatchIn(_).map(_.group(1)))
      // A URL built by concatenation (the popstate handler's) ends at its quote.
      .filterNot(_.endsWith("/"))
      .distinct

  /** The `conn` the page was minted under, off its stream URL. */
  def conn: String =
    "[?&]conn=([^&']+)".r
      .findFirstMatchIn(doc.body.attr("data-init"))
      .map(_.group(1))
      .getOrElse(throw IllegalStateException("no conn in data-init"))

  /** A frame set `_reload`: the browser reloads at [[query]]. */
  def reloadAsked: Boolean =
    signal("_reload").exists(_.asBoolean.contains(true))

  /** The stream request the body's `data-init` makes, as the bundle sends a GET
    * action: its URL plus a `datastar` parameter holding the signals its
    * `filterSignals` lets through. What a reconnect carries.
    */
  def streamRequest: String = {
    val init = doc.body.attr("data-init")
    val url = FakePage.GetUrl
      .findFirstMatchIn(init)
      .map(_.group(1))
      .getOrElse(throw IllegalStateException(s"no stream in data-init: $init"))
    def pattern(name: String) =
      // A JS string literal, unescaped as JS does: the `\.` the server writes
      // reaches RegExp as `.`.
      s"$name:'([^']*)'".r
        .findFirstMatchIn(init)
        .map(_.group(1).replaceAll("""\\(.)""", "$1").r)
    val include = pattern("include").getOrElse(".*".r)
    val exclude = pattern("exclude").getOrElse("(^|\\.)_".r)
    def keep(o: JsonObject, prefix: String): JsonObject =
      JsonObject.fromIterable(o.toList.flatMap { (k, v) =>
        val path = prefix + k
        v.asObject match
          case Some(sub) =>
            val kept = keep(sub, path + ".")
            Option.when(kept.nonEmpty)(k -> Json.fromJsonObject(kept))
          case None =>
            Option.when(
              include.findFirstIn(path).isDefined && exclude
                .findFirstIn(path)
                .isEmpty
            )(k -> v)
      })
    val sep = if (url.contains('?')) "&" else "?"
    s"$url${sep}datastar=${enc(Json.fromJsonObject(keep(signals, "")).noSpaces)}"
  }

  def apply(eventType: String, data: String): Unit = {
    val lines = data.linesIterator.toList.map { l =>
      val i = l.indexOf(' ')
      if (i < 0) l -> "" else l.take(i) -> l.drop(i + 1)
    }
    def one(key: String) = lines.collectFirst { case (`key`, v) => v }
    eventType match
      case "datastar-patch-signals" =>
        val json = io.circe.parser
          .parse(lines.collect { case ("signals", v) => v }.mkString("\n"))
          .fold(e => throw IllegalArgumentException(s"$e in $data"), identity)
        if (json.noSpaces.contains("null"))
          json
            .foldWith(FakePage.NullFinder)
            .foreach(p => missed += s"null signal $p in $data")
        json.asObject.foreach(o =>
          signals =
            FakePage.merge(signals, o, one("onlyIfMissing").contains("true"))
        )
      case "datastar-patch-elements" =>
        elements(
          one("selector"),
          one("mode").getOrElse("outer"),
          lines.collect { case ("elements", v) => v }.mkString("\n")
        )
      case _ => ()
  }

  private def elements(
      selector: Option[String],
      mode: String,
      html: String
  ): Unit = {
    def targets: List[Element] =
      selector.toList.flatMap(s => doc.select(s).asScala.toList)
    def fragment(context: Element): List[Node] =
      Parser.parseFragment(html, context, "").asScala.toList
    def missing(): Unit =
      missed += s"$mode ${selector.getOrElse("(by id)")}: no target for ${html.take(120)}"
    mode match
      case "remove" =>
        selector match
          case Some(_) => targets.foreach(_.remove())
          case None    =>
            topLevel(fragment(doc.body)).foreach(e =>
              Option(doc.getElementById(e.id)).foreach(_.remove())
            )
      case "outer" if selector.isEmpty =>
        val arrived = topLevel(fragment(doc.body))
        if (arrived.isEmpty) missing()
        arrived.foreach { e =>
          Option(doc.getElementById(e.id)) match
            case None    => missing()
            case Some(t) => morph(List(t), () => { t.replaceWith(e); List(e) })
        }
      case _ =>
        val ts = targets
        if (ts.isEmpty) missing()
        ts.foreach { t =>
          mode match
            case "outer" =>
              morph(
                List(t),
                () => {
                  val ns = fragment(t.parent); after(t, ns); t.remove(); ns
                }
              )
            case "replace" =>
              morph(
                Nil,
                () => {
                  val ns = fragment(t.parent); after(t, ns); t.remove(); ns
                }
              )
            case "inner" =>
              morph(
                t.children.asScala.toList,
                () => {
                  val ns = fragment(t); t.empty(); t.appendChildren(ns.asJava);
                  ns
                }
              )
            case "append" =>
              morph(
                Nil,
                () => { val ns = fragment(t); t.appendChildren(ns.asJava); ns }
              )
            case "prepend" =>
              morph(
                Nil,
                () => { val ns = fragment(t); t.prependChildren(ns.asJava); ns }
              )
            case "before" =>
              morph(
                Nil,
                () => {
                  val ns = fragment(t.parent);
                  ns.foreach(n => { val _ = t.before(n) }); ns
                }
              )
            case "after" =>
              morph(
                Nil,
                () => { val ns = fragment(t.parent); after(t, ns); ns }
              )
            case other => missed += s"unknown mode $other"
        }
  }

  private def after(t: Element, ns: List[Node]): Unit =
    ns.reverse.foreach(n => { val _ = t.after(n) })

  private def topLevel(ns: List[Node]): List[Element] =
    ns.collect { case e: Element if e.id.nonEmpty => e }

  /** `replaced` are the subtrees a morph matches new elements against by id: a
    * seed whose value comes back unchanged on the same id is not applied again.
    */
  private def morph(replaced: List[Element], insert: () => List[Node]): Unit = {
    val before: Map[(String, String), String] = replaced
      .flatMap(_.select("[id]").asScala)
      .flatMap(e =>
        FakePage.SeedKeys.filter(e.hasAttr).map(k => (e.id, k) -> e.attr(k))
      )
      .toMap
    insert().foreach {
      case root: Element =>
        root.select("*").asScala.foreach { e =>
          FakePage.SeedKeys.filter(e.hasAttr).foreach { k =>
            val v = e.attr(k)
            if (e.id.isEmpty || !before.get((e.id, k)).contains(v)) seed(k, v)
          }
        }
      case _ => ()
    }
  }

  private def seed(key: String, value: String): Unit =
    signals = FakePage.merge(
      signals,
      FakePage.Seed.parse(value, signals),
      onlyIfMissing = key == "data-signals__ifmissing"
    )

  private def seedAll(): Unit =
    doc
      .select("*")
      .asScala
      .foreach(e =>
        FakePage.SeedKeys.filter(e.hasAttr).foreach(k => seed(k, e.attr(k)))
      )

  /** The query a refresh of this page sends: each `data-fh-url` mirror's param,
    * unless empty or its declared value, sorted.
    */
  def query: String = {
    val params = doc.select("*").asScala.toList.flatMap { e =>
      e.attributes.asScala.toList
        .filter(a =>
          a.getKey == "data-fh-url" || a.getKey
            .startsWith("data-fh-url:") || a.getKey.startsWith("data-fh-url__")
        )
        .flatMap(a => FakePage.Expr.eval(a.getValue, signals))
        .collect {
          case FakePage.JsV.Of(j) if j.isArray => j.asArray.toList.flatten
        }
        .collect { case key :: raw :: declared =>
          val shown = FakePage.JsV.Of(raw).text
          val unchanged = declared.headOption.map(FakePage.JsV.Of(_).text)
          (
            key.asString.getOrElse(""),
            if (unchanged.contains(shown)) "" else shown
          )
        }
    }
    val kept = params.filter(_._2.nonEmpty).distinct.sorted
    if (kept.isEmpty) ""
    else
      kept
        .map((k, v) => s"${enc(k)}=${enc(v)}")
        .mkString("?", "&", "")
  }

  private def enc(s: String) = URLEncoder.encode(s, UTF_8)

  /** The ids of `id`'s element and its siblings, in page order. */
  def siblingIds(id: String): Option[List[String]] =
    Option(doc.getElementById(id))
      .flatMap(e => Option(e.parent))
      .map(_.children.asScala.toList.map(_.id))

  /** `#dashboard`, then the popup host, painted and in canonical form. */
  def view: String = viewLeaving(clientOwned)

  private[testkit] def clientOwned: Set[String] = FakePage.clientOwned(doc)

  /** Two pages are compared leaving out what either's handlers own. */
  private[testkit] def viewLeaving(owned: Set[String]): String =
    List("dashboard", "popups")
      .flatMap(id => Option(doc.getElementById(id)).map(id -> _))
      .map { (id, e) =>
        val c = e.clone()
        FakePage.Paint(c, signals, owned)
        s"#$id\n" + FakePage.canonical(c)
      }
      .mkString("\n")
}

object FakePage {

  def load(html: String): FakePage = {
    val page = new FakePage(Jsoup.parse(html))
    page.seedAll()
    page
  }

  /** The oracle's equality: nothing sent missed its target, and the page is the
    * one `fresh`, a new GET at its [[query]], gives.
    */
  def matches(page: FakePage, fresh: FakePage): hedgehog.Result = {
    val owned = page.clientOwned ++ fresh.clientOwned
    val (a, b) = (page.viewLeaving(owned), fresh.viewLeaving(owned))
    hedgehog.Result
      .assert(page.misses.isEmpty)
      .log(page.misses.mkString("missed:\n", "\n", ""))
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

  /** `(event type, data)` per event of an SSE text, data lines joined. */
  def events(text: String): List[(String, String)] =
    text
      .split("\n\n")
      .toList
      .map(_.linesIterator.toList)
      .flatMap { lines =>
        val ev = lines.collectFirst {
          case l if l.startsWith("event: ") => l.drop(7)
        }
        val data = lines.collect {
          case l if l.startsWith("data: ") => l.drop(6)
        }
        ev.map(_ -> data.mkString("\n"))
      }

  private val GetUrl = """@get\('([^']*)'""".r
  private val Reads = """\$([\w#.]+)""".r
  private val Writes = """\$([\w#.]+)\s*=(?!=)""".r

  /** Every signal a handler on the page assigns, except a choice's pending ask
    * (absent here, so a choice reads as committed) and the server's display
    * signals (`_e.*`, which a drag's effect also writes, ADR 0017).
    */
  private def clientOwned(doc: Document): Set[String] =
    doc
      .select("*")
      .asScala
      .iterator
      .flatMap(_.attributes.asScala.map(_.getValue))
      .flatMap(Writes.findAllMatchIn(_).map(_.group(1)))
      .filterNot(s => s.endsWith("__pending") || s.startsWith("_e."))
      .toSet
  private val PostUrl = """@post\('([^']*)'""".r

  private val SeedKeys = List("data-signals", "data-signals__ifmissing")

  private def lookup(o: JsonObject, path: List[String]): Option[Json] =
    path match
      case Nil       => Some(Json.fromJsonObject(o))
      case k :: Nil  => o(k)
      case k :: rest => o(k).flatMap(_.asObject).flatMap(lookup(_, rest))

  /** Datastar's merge patch, at leaves only; `onlyIfMissing` keeps every leaf
    * already present.
    */
  private def merge(
      into: JsonObject,
      patch: JsonObject,
      onlyIfMissing: Boolean
  ): JsonObject =
    patch.toList.foldLeft(into) { case (acc, (k, v)) =>
      (acc(k).flatMap(_.asObject), v.asObject) match
        case (Some(old), Some(sub)) =>
          acc.add(k, Json.fromJsonObject(merge(old, sub, onlyIfMissing)))
        case _ if v.isNull                         => acc.remove(k)
        case _ if onlyIfMissing && acc.contains(k) => acc
        case _                                     => acc.add(k, v)
    }

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

  /** A JS value as a binding sees it: a missing signal reads `undefined`. */
  enum JsV {
    case Undef
    case Of(j: Json)

    def truthy: Boolean = this match
      case Undef => false
      case Of(j) =>
        j.fold(
          false,
          b => b,
          n => { val d = n.toDouble; d != 0 && !d.isNaN },
          s => s.nonEmpty,
          _ => true,
          _ => true
        )

    /** `String(v)`, as a template literal writes it. */
    def text: String = this match
      case Undef => "undefined"
      case Of(j) =>
        j.fold(
          "null",
          b => b.toString,
          n => {
            val d = n.toDouble
            if (d.isWhole && math.abs(d) < 1e21) d.toLong.toString
            else d.toString
          },
          s => s,
          a => a.map(Of(_).text).mkString(","),
          _ => "[object Object]"
        )

    def strictEq(that: JsV): Boolean = (this, that) match
      case (Undef, Undef) => true
      case (Of(a), Of(b)) =>
        (a.asNumber, b.asNumber) match
          case (Some(x), Some(y)) => x.toDouble == y.toDouble
          case _                  => a.equals(b)
      case _ => false
  }

  /** The `data-signals` seed: a JS object literal, as `Datastar.signalsAttr`
    * and the library write one. Throws on anything else, since a seed the fake
    * page cannot read would make it diverge silently.
    */
  object Seed {
    def parse(src: String, signals: JsonObject): JsonObject = {
      val p = Lit(src, signals)
      p.ws()
      val j = p.value()
      p.ws()
      if (!p.done) p.fail("trailing input")
      j.asObject.getOrElse(p.fail("not an object"))
    }
  }

  private final class Lit(src: String, signals: JsonObject = JsonObject.empty) {
    var i = 0
    def done: Boolean = i >= src.length
    def peek: Char = if (done) '\u0000' else src.charAt(i)
    def fail(why: String): Nothing =
      throw IllegalArgumentException(s"$why at $i in: $src")
    def ws(): Unit = while (!done && peek.isWhitespace) i += 1
    def eat(c: Char): Boolean = {
      ws();
      if (peek == c) { i += 1; true }
      else false
    }
    def expect(c: Char): Unit = if (!eat(c)) fail(s"expected $c")
    def word(w: String): Boolean =
      if (
        src.startsWith(w, i) && !(i + w.length < src.length && src
          .charAt(i + w.length)
          .isLetterOrDigit)
      ) {
        i += w.length; true
      } else false

    def string(): String = {
      val q = peek
      i += 1
      val sb = new StringBuilder
      while (!done && peek != q) {
        if (peek == '\\') { i += 1; sb += peek; i += 1 }
        else { sb += peek; i += 1 }
      }
      if (done) fail("unterminated string")
      i += 1
      sb.toString
    }

    def number(): Option[Json] = {
      val start = i
      if (peek == '-') i += 1
      while (
        !done && (peek.isDigit || peek == '.' || peek == 'e' || peek == 'E')
      ) i += 1
      val s = src.substring(start, i)
      s.toDoubleOption.flatMap(Json.fromDouble).orElse { i = start; None }
    }

    def value(): Json = {
      ws()
      peek match
        case '{'                => i += 1; obj()
        case '['                => i += 1; arr()
        case '\'' | '"'         => Json.fromString(string())
        case _ if word("true")  => Json.True
        case _ if word("false") => Json.False
        case _ if word("null")  => Json.Null
        // A seed may copy a signal (`_x__slide: $_e…`); a missing one reads
        // `undefined`, which assigns nothing, as the merge's null does.
        case '$' =>
          i += 1
          val start = i
          while (!done && (peek.isLetterOrDigit || "_#.".contains(peek))) i += 1
          lookup(signals, src.substring(start, i).split('.').toList)
            .getOrElse(Json.Null)
        case _ => number().getOrElse(fail("value expected"))
    }

    private def obj(): Json = {
      val fields = List.newBuilder[(String, Json)]
      ws()
      while (!eat('}')) {
        ws()
        val key =
          if (peek == '\'' || peek == '"') string()
          else {
            val start = i
            while (!done && peek != ':' && !peek.isWhitespace) i += 1
            src.substring(start, i)
          }
        expect(':')
        fields += key -> value()
        val _ = eat(',')
        ws()
      }
      Json.fromFields(fields.result())
    }

    private def arr(): Json = {
      val items = Vector.newBuilder[Json]
      while (!eat(']')) {
        items += value()
        val _ = eat(',')
      }
      Json.fromValues(items.result())
    }
  }

  /** The binding expressions the renderer writes that read only signals: `$a`,
    * `!`, `&&`, `||`, `===`/`!==`, `String(…)`, literals, arrays. `None` for
    * anything else, which [[Paint]] leaves out rather than guesses.
    */
  object Expr {
    def eval(src: String, signals: JsonObject): Option[JsV] =
      scala.util.Try {
        val p = ExprParser(src, signals)
        val v = p.or()
        p.ws()
        if (!p.done) p.fail("trailing")
        v
      }.toOption
  }

  private final class ExprParser(src: String, signals: JsonObject) {
    private val lit = Lit(src)
    def done: Boolean = lit.done
    def ws(): Unit = lit.ws()
    def fail(why: String): Nothing = lit.fail(why)
    private def op(s: String): Boolean = {
      ws()
      if (src.startsWith(s, lit.i)) { lit.i += s.length; true }
      else false
    }

    def or(): JsV = {
      var l = and()
      while (op("||")) { val r = and(); l = if (l.truthy) l else r }
      l
    }
    private def and(): JsV = {
      var l = eq()
      while (op("&&")) { val r = eq(); l = if (!l.truthy) l else r }
      l
    }
    private def eq(): JsV = {
      var l = unary()
      var go = true
      while (go) {
        if (op("===") || op("==")) {
          val r = unary(); l = JsV.Of(Json.fromBoolean(l.strictEq(r)))
        } else if (op("!==") || op("!=")) {
          val r = unary(); l = JsV.Of(Json.fromBoolean(!l.strictEq(r)))
        } else go = false
      }
      l
    }
    private def unary(): JsV =
      if (op("!")) JsV.Of(Json.fromBoolean(!unary().truthy)) else primary()

    private def primary(): JsV = {
      ws()
      lit.peek match
        case '$' =>
          lit.i += 1
          val start = lit.i
          while (
            !done && (lit.peek.isLetterOrDigit || lit.peek == '_' || lit.peek == '#' || lit.peek == '.')
          ) lit.i += 1
          val path = src.substring(start, lit.i)
          if (path.isEmpty) fail("empty path")
          lookup(signals, path.split('.').toList).fold(JsV.Undef)(JsV.Of(_))
        case '(' =>
          lit.i += 1
          val v = or()
          if (!op(")")) fail("expected )")
          v
        case '[' =>
          lit.i += 1
          val items = Vector.newBuilder[Json]
          while (!op("]")) {
            items += (or() match {
              case JsV.Of(j) => j; case JsV.Undef => Json.Null
            })
            val _ = op(",")
          }
          JsV.Of(Json.fromValues(items.result()))
        case '\'' | '"'                 => JsV.Of(Json.fromString(lit.string()))
        case _ if lit.word("undefined") => JsV.Undef
        case _ if lit.word("String")    =>
          if (!op("(")) fail("expected (")
          val v = or()
          if (!op(")")) fail("expected )")
          JsV.Of(Json.fromString(v.text))
        case _ => JsV.Of(lit.value())
    }
  }

  /** Each binding painted as the bundle would from `signals`; one whose
    * expression [[Expr]] cannot read has what it paints left out instead (its
    * class, attribute, style property or text), so a value only a handler or a
    * computed expression keeps live is not compared at its stale first paint. A
    * `data-bind` control's `value` and `checked` are left out too: the handlers
    * beside it own them. So are the seeds, a morph not having to resend one
    * whose values a signals frame moved.
    *
    * The fake page runs no handler, so a binding reading a signal some handler
    * assigns (`clientOwned`) is left out the same way: a colour wheel's knob
    * paints copies its `data-on-signal-patch` takes of the server's values.
    */
  object Paint {
    def apply(
        root: Element,
        signals: JsonObject,
        clientOwned: Set[String]
    ): Unit =
      root.select("*").asScala.foreach { e =>
        val attrs = e.attributes.asScala.toList.map(a => a.getKey -> a.getValue)
        attrs.foreach { (k, v) =>
          lazy val value =
            if (Reads.findAllMatchIn(v).exists(m => clientOwned(m.group(1))))
              None
            else Expr.eval(v, signals)
          if (k == "data-text")
            value match
              case Some(x) => val _ = e.text(x.text)
              case None    => val _ = e.empty()
          else if (k.startsWith("data-class:"))
            val c = k.stripPrefix("data-class:")
            value match
              case Some(x) =>
                val _ = if (x.truthy) e.addClass(c) else e.removeClass(c)
              case None => val _ = e.removeClass(c)
          else if (k.startsWith("data-attr:"))
            val a = k.stripPrefix("data-attr:")
            value match
              case Some(JsV.Of(j)) if j.isString && j.asString.contains("") =>
                val _ = e.attr(a, "")
              case Some(JsV.Of(j)) if j.isBoolean =>
                if (j.asBoolean.contains(true)) { val _ = e.attr(a, "") }
                else { val _ = e.removeAttr(a) }
              case Some(JsV.Of(j)) if j.isString =>
                val _ = e.attr(a, j.asString.getOrElse(""))
              case Some(JsV.Of(j)) if !j.isNull => val _ = e.attr(a, j.noSpaces)
              case _                            => val _ = e.removeAttr(a)
          else if (k.startsWith("data-style:"))
            val p = k.stripPrefix("data-style:")
            val shown =
              value.filter(x => x.truthy || x.strictEq(JsV.Of(Json.fromInt(0))))
            style(e, p, shown.map(_.text))
          else if (k == "data-show")
            style(e, "display", value.collect { case x if !x.truthy => "none" })
        }
        if (e.hasAttr("data-bind")) {
          val _ = e.removeAttr("value").removeAttr("checked")
        }
        // Folded into `signals`, which is what the page shows through them.
        SeedKeys.foreach(k => { val _ = e.removeAttr(k) })
      }

    private def style(
        e: Element,
        property: String,
        to: Option[String]
    ): Unit = {
      val kept = declarations(e.attr("style")).filterNot(_._1 == property) ++
        to.map(property -> _)
      if (kept.isEmpty) { val _ = e.removeAttr("style") }
      else {
        val _ = e.attr("style", kept.map((k, v) => s"$k: $v").mkString("; "))
      }
    }
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
        .filterNot((k, v) => k == "class" && v.isEmpty)
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
