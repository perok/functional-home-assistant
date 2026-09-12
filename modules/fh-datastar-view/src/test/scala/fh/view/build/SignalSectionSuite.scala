package fh.view.build

/** A shipped card may not put a slot's SIGNAL BINDING inside a Mustache section
  * keyed on that same slot's value.
  *
  * The bug it prevents, which shipped twice: a signal slot's value is blank in
  * the PATCH form (ADR 0017 — the value travels in the signals frame instead),
  * and a blank value is a FALSE Mustache section. So the first patch that
  * touches the node deletes the element AND the binding that would have
  * refilled it. Nothing errors, the rest of the page keeps working, and the
  * element never returns until a reload — which is why it reached a release:
  * the pure-signal case sends no element patch at all, so it only bites when
  * something ELSE about the node changes, and on a tile the usual something
  * else is the `busy` flag a tap sets. "It disappears when I click it."
  *
  * `slotMod.ifSlot(name)` is the correct guard: it keys on the renderer-derived
  * `<slot>__has`, which is emitted for every DECLARED slot in both forms, so a
  * card never branches on which tier filled a slot.
  *
  * Reading the SOURCES rather than the evaluated registry is deliberate: the
  * mistake is one an author makes in the template text, and this is the form
  * they will see it in.
  */
class SignalSectionSuite extends munit.FunSuite {

  /** `{{#name}}` … `{{/name}}` bodies in one module, by slot name. Nesting of
    * the SAME name never occurs (and would not mean anything), so the first
    * close tag ends the section.
    */
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
    // The other half of the control: the offender scan passes trivially if the
    // lib came back empty, or if no module matched the section grammar.
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
    // A control, because a passing scan proves nothing on its own: if
    // `sections` silently parsed nothing, the test above would pass for every
    // possible library. These are the exact shapes that reached 0.1.38.
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
      """{{#state}}<span class="state" \(slotMod.signalBind("state"))>{{state}}</span>{{/state}}"""
    assertEquals(
      sections(brokenState).collect {
        case (slot, body) if body.contains(s"""signalBind("$slot")""") => slot
      },
      List("state")
    )

    // ...and does NOT flag the safe shape control.pkl uses, where the section
    // covers only an inline attribute and the binding sits outside it.
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
