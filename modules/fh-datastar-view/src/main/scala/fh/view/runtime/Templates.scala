package fh.view.runtime

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache as JavaMustache
import com.github.mustachejava.DefaultMustacheVisitor
import com.github.mustachejava.MustacheVisitor
import com.github.mustachejava.TemplateContext

import java.io.{Reader, StringReader, Writer}

/** Card templates, compiled once by validation ([[Templates.card]]). A missing
  * slot renders empty.
  */
final class Templates(cards: Map[String, Templates.Card]):
  /** One template per card: a leaf's is its patch fragment. */
  val components: Map[String, JavaMustache] =
    cards.view.mapValues(_.mustache).toMap

object Templates {

  // mustache.java. A section whose value is "" is skipped, so optional pieces
  // render only when present (`TemplatesBehaviourSuite`). Two seams are ours:
  // [[FhObjectHandler]] reads an [[FhScope]] directly (Map scopes resolve via
  // `entrySet`, so a get-only map answers everything empty), and `encode`.
  val factory: DefaultMustacheFactory = {
    val f = new DefaultMustacheFactory() {
      override def createMustacheVisitor(): MustacheVisitor = new FhVisitor(
        this
      )
      // Written in runs: `Writer.write(int)` allocates a one-char array per
      // character. A newline is escaped because a patch collapses literal ones
      // (`Datastar.collapse`), which flattened the more-info `<pre>` after its
      // first update.
      override def encode(s: String, writer: Writer): Unit = {
        val n = s.length
        var start = 0
        var i = 0
        def flush(upto: Int): Unit =
          if upto > start then writer.write(s, start, upto - start)
        while i < n do
          s.charAt(i) match
            case '&' =>
              flush(i); writer.write("&amp;"); i += 1; start = i
            case '<' =>
              flush(i); writer.write("&lt;"); i += 1; start = i
            case '>' =>
              flush(i); writer.write("&gt;"); i += 1; start = i
            case '"' =>
              flush(i); writer.write("&quot;"); i += 1; start = i
            case '\'' =>
              flush(i); writer.write("&#39;"); i += 1; start = i
            case '\n' =>
              flush(i); writer.write("&#10;"); i += 1; start = i
            case '\r' =>
              flush(i); writer.write("&#13;"); i += 1; start = i
            case _ => i += 1
        flush(n)
      }
    }
    f.setObjectHandler(new FhObjectHandler)
    f
  }

  /** A way a template reaches a slot besides its value: `{{{x.bind}}}`,
    * `{{#x.has}}`, `{{{x.jsValue}}}`, `{{x.signal}}` (ADR 0017).
    */
  enum View(val spelling: String):
    case Bind extends View("bind")
    case Has extends View("has")
    case JsValue extends View("jsValue")
    case Signal extends View("signal")

  object View:
    private val bySpelling: Map[String, View] =
      values.map(v => v.spelling -> v).toMap

    /** `x.bind` as its slot and view; `None` for a name with no `.`. */
    def parse(name: String): Option[Either[String, (String, View)]] =
      name.indexOf('.') match
        case i if i <= 0 => None
        case i           =>
          val view = name.substring(i + 1)
          Some(
            bySpelling
              .get(view)
              .map(name.substring(0, i) -> _)
              .toRight(view)
          )

  trait FhScope:
    def fhGet(name: String): AnyRef

    def fhView(slot: String, view: View): AnyRef = null

    /** A raw `{{{name}}}` written straight into the writer: a region's
      * children, the chrome's body. A string would be a full copy of them.
      */
    def writerHoles: Map[String, Writer => Unit] = Map.empty

  private class FhObjectHandler
      extends com.github.mustachejava.reflect.SimpleObjectHandler() {
    override def get(name: String, scope: AnyRef): AnyRef = scope match
      case nc: FhScope => nc.fhGet(name)
      case _           => super.get(name, scope)

    // A view is split once, here. The inherited lookup splits a dotted name at
    // every call and would ask the slot's string for `bind`.
    override def createBinding(
        name: String,
        tc: TemplateContext,
        code: com.github.mustachejava.Code
    ): com.github.mustachejava.Binding =
      // `name` is null for the codes that name nothing (the template's root).
      Option(name).flatMap(View.parse) match
        case Some(Right((slot, view))) =>
          scopes =>
            var i = scopes.size() - 1
            var v: AnyRef = null
            while i >= 0 do
              scopes.get(i) match
                case s: FhScope => v = s.fhView(slot, view); i = -1
                case _          => i -= 1
            v
        case _ => super.createBinding(name, tc, code)
  }

  private class FhVisitor(df: DefaultMustacheFactory)
      extends DefaultMustacheVisitor(df) {
    // Encoded holes are left alone: nothing escaped is large enough to matter.
    override def value(
        tc: TemplateContext,
        variable: String,
        encoded: Boolean
    ): Unit =
      if encoded then super.value(tc, variable, encoded)
      else {
        val _ = list.add(new FhValueCode(tc, df, variable))
      }
  }

  private class FhValueCode(
      tc: TemplateContext,
      df: DefaultMustacheFactory,
      name: String
  ) extends com.github.mustachejava.codes.ValueCode(tc, df, name, false) {
    override def execute(
        writer: Writer,
        scopes: java.util.List[AnyRef]
    ): Writer = {
      var wrote: Writer = null
      var i = scopes.size() - 1
      while wrote == null && i >= 0 do
        scopes.get(i) match
          case s: FhScope if s.writerHoles.contains(name) =>
            val _ = s.writerHoles(name)(writer)
            wrote = writer
          case _ => i -= 1
      // The literal after the hole lives in this code's `appended` text;
      // skipping `appendText` dropped every template's trailing bytes.
      if wrote != null then appendText(wrote)
      else super.execute(writer, scopes)
    }
  }

  /** The name is uniquified: the factory caches by name, so a reload would get
    * the old template.
    */
  def compile(name: String, template: String): JavaMustache =
    factory.compile(
      stringReader(template),
      s"$name#${compileCounter.incrementAndGet()}"
    )

  private val compileCounter = new java.util.concurrent.atomic.AtomicLong

  /** What a template reads, off its code tree.
    *
    * @param names
    *   every name read but a region's, `{{x}}` and `{{#x}}` alike; a slot's
    *   value is a string, so inside a section a name is still the node's
    * @param required
    *   the `{{x}}`/`{{{x}}}` outside every section, which a node must set
    * @param raw
    *   the names placed unescaped, `{{{x}}}`
    * @param regionHoles
    *   how often each declared region is placed as `{{{name}}}` outside every
    *   section
    * @param misplacedRegions
    *   the declared regions also placed any other way: escaped, as a section,
    *   or inside one
    * @param views
    *   each slot's views, `{{{x.bind}}}`; the slot is in [[names]] too
    * @param boundInOwnSection
    *   the slots whose `x.bind` sits inside `{{#x}}` or `{{^x}}`, which the
    *   patch form's withheld value closes (ADR 0017)
    * @param unknownViews
    *   the dotted names whose view is none of [[View]]
    */
  final case class Reads(
      names: Set[String],
      required: Set[String],
      raw: Set[String],
      regionHoles: Map[String, Int],
      misplacedRegions: Set[String],
      views: Set[(String, View)],
      boundInOwnSection: Set[String],
      unknownViews: Set[String]
  )

  private object Reads:
    val empty: Reads = Reads(
      Set.empty,
      Set.empty,
      Set.empty,
      Map.empty,
      Set.empty,
      Set.empty,
      Set.empty,
      Set.empty
    )

  /** A card's template as it renders, and what validation reads off it. */
  final case class Card(mustache: JavaMustache, reads: Reads)

  /** Read off the rendering tree itself, so what is checked is what renders. */
  def card(
      name: String,
      template: String,
      regions: Set[String]
  ): Either[String, Card] =
    try
      val tpl = compile(name, template)
      Right(
        Card(
          tpl,
          readCodes(tpl.getCodes(), regions, Nil, Reads.empty)
        )
      )
    catch
      case e: com.github.mustachejava.MustacheException =>
        Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  private def readCodes(
      codes: Array[com.github.mustachejava.Code],
      regions: Set[String],
      guards: List[String],
      acc: Reads
  ): Reads =
    import com.github.mustachejava.codes.{IterableCode, ValueCode}
    if codes == null then acc
    else
      codes.foldLeft(acc) { (acc, code) =>
        code match
          case s: IterableCode =>
            val here =
              if regions.contains(s.getName) then
                acc.copy(misplacedRegions = acc.misplacedRegions + s.getName)
              else read(s.getName, guards, value = false, raw = false, acc)
            readCodes(s.getCodes(), regions, s.getName :: guards, here)
          case v: ValueCode if regions.contains(v.getName) =>
            if v.isInstanceOf[FhValueCode] && guards.isEmpty then
              acc.copy(regionHoles =
                acc.regionHoles.updatedWith(v.getName)(n =>
                  Some(n.getOrElse(0) + 1)
                )
              )
            else acc.copy(misplacedRegions = acc.misplacedRegions + v.getName)
          case v: ValueCode =>
            read(
              v.getName,
              guards,
              value = true,
              raw = v.isInstanceOf[FhValueCode],
              acc
            )
          case other => readCodes(other.getCodes(), regions, guards, acc)
      }

  // `{{.}}` is the section's own value, and names nothing.
  private def read(
      tag: String,
      guards: List[String],
      value: Boolean,
      raw: Boolean,
      acc: Reads
  ): Reads =
    val required = value && guards.isEmpty
    View.parse(tag) match
      case None if tag == "." || tag.isEmpty => acc
      case None                              =>
        acc.copy(
          names = acc.names + tag,
          required = if required then acc.required + tag else acc.required,
          raw = if raw then acc.raw + tag else acc.raw
        )
      case Some(Right((slot, view))) =>
        acc.copy(
          names = acc.names + slot,
          required = if required then acc.required + slot else acc.required,
          views = acc.views + (slot -> view),
          boundInOwnSection =
            if view == View.Bind && guards.contains(slot) then
              acc.boundInOwnSection + slot
            else acc.boundInOwnSection
        )
      case Some(Left(_)) => acc.copy(unknownViews = acc.unknownViews + tag)

  // A pool, not one list per thread: execution recurses through the engine,
  // and a shared list was cleared under the outer execute, truncating nested
  // renders. Safe per thread because an execute never suspends.
  private val scopePool =
    new ThreadLocal[scala.collection.mutable.Stack[
      com.github.mustachejava.util.InternalArrayList[AnyRef]
    ]]:
      override def initialValue(): scala.collection.mutable.Stack[
        com.github.mustachejava.util.InternalArrayList[AnyRef]
      ] = scala.collection.mutable.Stack.empty

  def run(
      tpl: com.github.mustachejava.Mustache,
      writer: Writer,
      scope: AnyRef
  ): Unit = {
    val stack = scopePool.get()
    val s =
      if stack.nonEmpty then stack.pop()
      else new com.github.mustachejava.util.InternalArrayList[AnyRef]()
    s.clear()
    val _ = s.add(scope)
    tpl.execute(writer, s)
    val _ = stack.push(s)
  }

  private def stringReader(s: String): Reader = new StringReader(s)
}
