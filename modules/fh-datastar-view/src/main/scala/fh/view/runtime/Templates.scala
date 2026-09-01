package fh.view.runtime

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache as JavaMustache
import com.github.mustachejava.DefaultMustacheVisitor
import com.github.mustachejava.MustacheVisitor
import com.github.mustachejava.TemplateContext
import fh.view.model.{CardDef, Dashboard, NodeId}

import java.io.{Reader, StringReader, Writer}
import scala.jdk.CollectionConverters.*

/** The shared template library, pre-compiled once at startup, never on the hot
  * path. Templates escape their `{{slot}}` values because HA values contain
  * `<`, `&` and quotes; raw author values (action URLs, ids) use `{{{...}}}`.
  * Missing slots render as empty strings rather than throwing.
  *
  * @param components
  *   the whole card, every region hole included. There is one template per card
  *   now: a leaf's IS its patch fragment, and structure is never patched.
  */
class Templates private (
    val components: Map[String, JavaMustache],
    // Per card: the region names whose loops the walk can render INLINE — the
    // visitor wraps only `{{#name}}` sections whose body is exactly `{{{html}}}`.
    // A card absent here has no inline regions and renders exactly as before.
    val inlineRegions: Map[String, Set[String]]
)

object Templates {

  // mustache.java (spullara), the engine the runtime executes. Its section
  // truthiness skips a `{{#x}}…{{/x}}` section when `x` resolves to "" — the
  // emptyStringIsFalse semantics the runtime was authored against — so optional
  // pieces (secondary, tap) render only when present, and a missing key renders
  // as empty; both are pinned in [[TemplatesBehaviourSuite]]. Two seams are
  // ours, both deliberate:
  //
  // 1. The ObjectHandler: the renderer's context ([[Renderer.NodeContext]])
  //    answers names READ IN PLACE — vars, region children, patch-form
  //    blanking — and mustache.java resolves Map scopes through `entrySet`,
  //    not `get` (a get-only AbstractMap resolves every name empty; that cost
  //    a probe suite to find). [[FhObjectHandler]] resolves a [[FhScope]]
  //    scope directly and delegates everything else to the reflective
  //    default, so Map scopes (the region-loop items) keep engine behavior.
  // 2. `encode` — see the comment below.
  val factory: DefaultMustacheFactory = {
    val f = new DefaultMustacheFactory() {
      override def createMustacheVisitor(): MustacheVisitor = new FhVisitor(
        this
      )
      // jmustache's exact escaping, written in RUNS: `Writer.write(int)`
      // allocates a one-char array per character (java.io.Writer's default),
      // which made every escaped value pay for its own length; a run between
      // specials costs nothing.
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
            case _ => i += 1
        flush(n)
      }
    }
    f.setObjectHandler(new FhObjectHandler)
    f
  }

  /** A scope the renderer resolves itself, bypassing reflection entirely. */
  trait FhScope:
    def fhGet(name: String): AnyRef

    /** Region name -> render that region's children INTO the writer. Present
      * only where the document walk can supply it ([[Renderer.tracedInto]]);
      * absent for patch renders and the member walk, which keep the
      * string-splice path. The walk's trace lands in the caller's shared
      * accumulator, so nothing comes back.
      */
    def regionWalk: Map[String, Writer => Unit]

    /** Raw VARIABLE name -> write that hole's bytes INTO the writer, the same
      * trick [[regionWalk]] plays for a section. `{{{body}}}` in a theme's
      * chrome is the customer: handing mustache the body as a String to splice
      * is a full copy of the document for a hole that is written once. Empty
      * everywhere else, and an absent name falls back to the ordinary value
      * lookup, so a theme is unaffected.
      */
    def writerHoles: Map[String, Writer => Unit] = Map.empty

  private class FhObjectHandler
      extends com.github.mustachejava.reflect.SimpleObjectHandler() {
    override def get(name: String, scope: AnyRef): AnyRef = scope match
      case nc: FhScope => nc.fhGet(name)
      case _           => super.get(name, scope)
  }

  // The visitor wraps every `{{#…}}` section whose body is EXACTLY `{{{html}}}`
  // — one raw hole, nothing else — in a region code: at execute time, when the
  // walk has handed the context a renderer for that region, the children are
  // traced INTO the writer and no child String is ever built. Any other body
  // keeps the standard iterable, so a template written slightly differently
  // loses speed, never bytes. The check is engine-typed (a single ValueCode
  // for `html`, unencoded), not text matching.
  private class FhVisitor(df: DefaultMustacheFactory)
      extends DefaultMustacheVisitor(df) {
    val inlined = scala.collection.mutable.Set.empty[String]

    /** The variable counterpart of [[FhRegionCode]]: an UNENCODED `{{{name}}}`
      * whose bytes the caller would rather write than hand over as a String.
      * Encoded holes are left alone — escaping is the whole point of them, and
      * nothing that needs escaping is large enough for this to matter.
      */
    override def value(
        tc: TemplateContext,
        variable: String,
        encoded: Boolean
    ): Unit =
      if encoded then super.value(tc, variable, encoded)
      else {
        val _ = list.add(new FhValueCode(tc, df, variable))
      }

    override def iterable(
        tc: TemplateContext,
        variable: String,
        mustache: JavaMustache
    ): Unit = {
      // The exact-body check, via the engine's own record of the original
      // text (`Code.identity` writes the tag as authored): exactly one hole,
      // `{{{html}}}`, nothing else — no literals, no trim slop.
      val sw = new java.io.StringWriter()
      val codes = mustache.getCodes()
      if codes.length == 1 then codes(0).identity(sw)
      val exact = codes.length == 1 && sw.toString == "{{{html}}}"
      if exact then {
        inlined += variable
        val _ = list.add(new FhRegionCode(tc, df, mustache, variable))
      } else {
        val _ = list.add(
          new com.github.mustachejava.codes.IterableCode(
            tc,
            df,
            mustache,
            variable
          )
        )
      }
    }
  }

  /** A raw `{{{name}}}` whose scope offers a writer for it: the bytes go
    * straight into the writer instead of being built as a String and spliced.
    * Falls back to the standard `ValueCode` when no scope offers one, which is
    * every hole but the chrome's body today.
    */
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
      // Same trailing-literal rule as [[FhRegionCode]]: the parser folds the
      // text after the tag into this code's `appended`, so skipping
      // appendText drops it.
      if wrote != null then appendText(wrote)
      else super.execute(writer, scopes)
    }
  }

  /** `{{#region}}` with the exact `{{{html}}}` body. When the innermost scope
    * carries a renderer for this region (the document walk), the children are
    * traced into the writer — the child bytes are written where they land, in
    * place of the String splicing `IterableCode` would do. Otherwise the
    * standard iterable runs, byte-identical to before.
    */
  private class FhRegionCode(
      tc: TemplateContext,
      df: DefaultMustacheFactory,
      body: JavaMustache,
      name: String
  ) extends com.github.mustachejava.codes.IterableCode(tc, df, body, name) {
    override def execute(
        writer: Writer,
        scopes: java.util.List[AnyRef]
    ): Writer = {
      var walked: Writer = null
      var i = scopes.size() - 1
      while walked == null && i >= 0 do
        scopes.get(i) match
          case s: FhScope if s.regionWalk.contains(name) =>
            // The walk's own-bytes flow back through its closure, not the
            // writer; the callback returns them and they are already taken.
            val _ = s.regionWalk(name)(writer)
            walked = writer
          case _ => i -= 1
      // The parser folds the literal AFTER the section into this code's
      // `appended` text (`DefaultCode.append`) — skipping appendText dropped
      // every template's trailing bytes, which the suites caught immediately.
      if walked != null then appendText(walked)
      else super.execute(writer, scopes)
    }
  }

  /** Compile one template by name. Returns the template plus the region names
    * the visitor made inline for it — read off the compiled CODE TREE, because
    * the factory caches by name and only the first compile would ever run the
    * visitor; the name is also uniquified so that cache can never hand a later
    * compile a previous dashboard's template (a live reload renames nothing and
    * changes templates).
    */
  def compile(name: String, template: String): (JavaMustache, Set[String]) = {
    val tpl = factory.compile(
      stringReader(template),
      s"$name#${compileCounter.incrementAndGet()}"
    )
    (tpl, collectInline(tpl.getCodes(), Set.empty))
  }

  private val compileCounter = new java.util.concurrent.atomic.AtomicLong

  /** The region names wrapped in [[FhRegionCode]]s, walking the code tree. */
  private def collectInline(
      codes: Array[com.github.mustachejava.Code],
      acc: Set[String]
  ): Set[String] =
    if codes == null then acc
    else
      codes.foldLeft(acc) { (acc, code) =>
        val acc2 = code match
          case r: FhRegionCode => acc + r.getName
          case _               => acc
        collectInline(code.getCodes(), acc2)
      }

  // The walk is RECURSIVE THROUGH THE ENGINE now — a container's execute runs
  // region codes that execute the CHILDREN's templates — so a single reused
  // scopes list per thread would be cleared under the outer execute's feet
  // (that truncated every nested rendering; the suites caught it immediately).
  // A per-thread pool instead: each run takes a recycled list, the engine's
  // push/pop of scope descent is balanced, and the list returns to the pool —
  // allocation-free steady state, correct at any depth.
  private val scopePool =
    new ThreadLocal[scala.collection.mutable.ArrayStack[
      com.github.mustachejava.util.InternalArrayList[AnyRef]
    ]]:
      override def initialValue(): scala.collection.mutable.ArrayStack[
        com.github.mustachejava.util.InternalArrayList[AnyRef]
      ] = scala.collection.mutable.ArrayStack.empty

  /** Execute `tpl` with the single scope against `writer` — the call the whole
    * runtime uses (Renderer.executeInto, the chrome, the bench's engine cell).
    */
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

  def from(dashboard: Dashboard): Templates = {
    val compiled = dashboard.cards.view.map { case (name, cd) =>
      name -> compile(name, cd.template)
    }.toMap
    new Templates(
      compiled.view.mapValues(_._1).toMap,
      compiled.view.mapValues(_._2).toMap
    )
  }
}
