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

  // mustache.java (spullara), the engine the runtime executes. Its ONE
  // divergence from jmustache — and it took a hostile-value parity suite to
  // find it — is that `escapeHTML` rewrites a newline as `&#10;`; HA values
  // carry newlines (more-info attribute blocks), and a rewritten newline moves
  // every shipped byte that holds one. The override pins jmustache's exact
  // escaping: the five HTML entities, everything else verbatim.
  val factory: DefaultMustacheFactory = new DefaultMustacheFactory() {
    override def encode(s: String, writer: Writer): Unit = {
      var i = 0
      val n = s.length
      while i < n do
        s.charAt(i) match
          case '&'  => writer.write("&amp;")
          case '<'  => writer.write("&lt;")
          case '>'  => writer.write("&gt;")
          case '"'  => writer.write("&quot;")
          case '\'' => writer.write("&#39;")
          case c    => writer.write(c)
        i += 1
    }
  }

  /** Compile one template by name — names make the factory's cache per template
    * instead of per call site.
    */
  def compile(name: String, template: String): com.github.mustachejava.Mustache =
    factory.compile(stringReader(template), name)

  private def stringReader(s: String): Reader = new StringReader(s)

  def from(dashboard: Dashboard): Templates =
    new Templates(
      dashboard.cards.view
        .map { case (name, cd) => name -> compile(name, cd.template) }
        .toMap
    )
}
