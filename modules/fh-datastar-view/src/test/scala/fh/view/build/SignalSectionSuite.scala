package fh.view.build

/** A shipped card may not put a slot's signal binding inside a Mustache section
  * keyed on that slot's value. A signal slot is blank in the patch form (ADR
  * 0017), and blank is a false section, so the first patch touching the node
  * deletes the element and the binding that would refill it, silently, until a
  * reload. It shipped twice: it bites only when something else about the node
  * changes, usually the `busy` flag a tap sets. "It disappears when I click
  * it."
  *
  * `slotMod.ifSlot(name)` is the guard: it keys on `<slot>__has`, emitted for
  * every declared slot in both forms. The sources are read, not the registry,
  * because the template text is where the author makes the mistake.
  */
class SignalSectionSuite extends munit.FunSuite {

  /** The same name never nests, so the first close tag ends the section. */
  private def sections(src: String): List[(String, String)] =
    """\{\{#([a-zA-Z_][a-zA-Z0-9_]*)\}\}""".r
      .findAllMatchIn(src)
      .toList
      .flatMap { m =>
        val name = m.group(1)
        val from = m.end
        val close = src.indexOf(s"{{/$name}}", from)
        Option.when(close >= 0)(name -> src.substring(from, close))
      }

  test("no card sections a signal binding on its own slot's value") {
    val offenders = BundledLib
      .entries()
      .filter { case (name, _) => name.endsWith(".pkl") }
      .flatMap { case (name, bytes) =>
        val src = new String(bytes, "UTF-8")
        sections(src).collect {
          case (slot, body) if body.contains(s"""signalBind("$slot")""") =>
            s"$name: {{#$slot}} wraps signalBind(\"$slot\") — " +
              s"""use ifSlot("$slot") instead"""
        }
      }
    assertEquals(offenders.toList, Nil)
  }

  test("the scan reaches the shipped templates at all") {
    // The offender scan passes trivially on an empty lib or no matched module.
    val scanned = BundledLib
      .entries()
      .filter { case (name, _) => name.endsWith(".pkl") }
    assert(scanned.sizeIs > 20, clue = scanned.map(_._1))
    val found = scanned.flatMap { case (_, b) =>
      sections(new String(b, "UTF-8")).map(_._1)
    }.distinct
    assert(found.contains("busy"), clue = found)
    assert(found.sizeIs > 10, clue = found)
  }

  test("the check would have caught the two that shipped") {
    // A control: if `sections` parsed nothing, the test above would pass for
    // any library. These are the shapes that reached 0.1.38.
    val entityCard =
      """<i class="{{icon}}"{{#liveIcon}} \(slotMod.signalBind("icon")){{/liveIcon}}></i>"""
    val brokenIcon = s"""<header>{{#icon}}$entityCard{{/icon}}</header>"""
    assertEquals(
      sections(brokenIcon).collect {
        case (slot, body) if body.contains(s"""signalBind("$slot")""") => slot
      },
      List("icon")
    )

    val brokenState =
      """{{#state}}<span class="fh-reading" \(slotMod.signalBind("state"))>{{state}}</span>{{/state}}"""
    assertEquals(
      sections(brokenState).collect {
        case (slot, body) if body.contains(s"""signalBind("$slot")""") => slot
      },
      List("state")
    )

    // The safe shape control.pkl uses: the section covers only an inline
    // attribute, the binding sits outside.
    val safe =
      """{{#hasInert}}{{#inert}} disabled{{/inert}} \(slotMod.signalBind("inert")){{/hasInert}}"""
    assertEquals(
      sections(safe).collect {
        case (slot, body) if body.contains(s"""signalBind("$slot")""") => slot
      },
      Nil
    )
  }
}
