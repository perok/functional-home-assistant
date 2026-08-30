package fh.view.runtime

import com.github.mustachejava.DefaultMustacheFactory
import com.samskivert.mustache.Mustache
import fh.view.model.{CardDef, Dashboard}

import java.io.{Reader, StringReader, Writer}

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
    val components: Map[String, com.github.mustachejava.Mustache]
)

object Templates {

  // jmustache, the REFERENCE engine: `MustacheEngineParitySuite` renders every
  // template through it and through [[factory]] and demands byte equality, so
  // the engine we ship can never drift from the engine we tested against. The
  // flags are the configuration the runtime was authored against:
  // `emptyStringIsFalse` makes `{{#x}}…{{/x}}` sections vanish when `x`
  // resolves to "" — so optional pieces (secondary, tap) render only when
  // present; `defaultValue("")` renders a missing key as empty.
  val compiler: Mustache.Compiler =
    Mustache
      .compiler()
      .escapeHTML(true)
      .defaultValue("")
      .emptyStringIsFalse(true)

  // mustache.java (spullara), the engine the runtime executes. Two seams are
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

  private class FhObjectHandler
      extends com.github.mustachejava.reflect.SimpleObjectHandler() {
    override def get(name: String, scope: AnyRef): AnyRef = scope match
      case nc: FhScope => nc.fhGet(name)
      case _           => super.get(name, scope)
  }

  /** Compile one template by name — names make the factory's cache per template
    * instead of per call site.
    */
  def compile(
      name: String,
      template: String
  ): com.github.mustachejava.Mustache =
    factory.compile(stringReader(template), name)

  // One scope, zero allocation on the steady state: mustache.java wraps the
  // scopes in a fresh `InternalArrayList` on EVERY execute (~200 B, its own
  // comment says so) unless it is handed one already. The renderer executes a
  // template per node with exactly one scope, the engine pushes and pops its
  // descent tracking balanced, and nothing escapes — so one list per thread,
  // cleared and refilled per call, is the same list the engine would have
  // allocated and thrown away.
  private val scopes =
    new ThreadLocal[com.github.mustachejava.util.InternalArrayList[AnyRef]]:
      override def initialValue()
          : com.github.mustachejava.util.InternalArrayList[AnyRef] =
        new com.github.mustachejava.util.InternalArrayList[AnyRef]()

  /** Execute `tpl` with the single scope against `writer` — the call the whole
    * runtime uses (Renderer.executeInto, the chrome, the bench's engine cell).
    */
  def run(
      tpl: com.github.mustachejava.Mustache,
      writer: Writer,
      scope: AnyRef
  ): Unit = {
    val s = scopes.get()
    s.clear()
    val _ = s.add(scope)
    tpl.execute(writer, s)
  }

  private def stringReader(s: String): Reader = new StringReader(s)

  def from(dashboard: Dashboard): Templates =
    new Templates(
      dashboard.cards.view.map { case (name, cd) =>
        name -> compile(name, cd.template)
      }.toMap
    )
}
