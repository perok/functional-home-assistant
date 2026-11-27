package fh.view.runtime

class OpeningSuite extends munit.FunSuite {

  private val serving = Renderer.Stamp("h", "s")
  private val other = Renderer.Stamp("h", "s")
  // Reaches a cursor at 4 or later.
  private val log = FragmentLog("L", Some(serving)).seeded(4)
  private val cursor = Server.Cursor("h", "s", "L", 7)
  private val session =
    SessionState.initial.copy(told = 6, painter = Painter.By(serving))

  private case class Row(
      serving: Option[Renderer.Stamp] = Some(serving),
      cursor: Option[Server.Cursor] = Some(cursor),
      reconnect: Boolean = true,
      log: FragmentLog = log,
      storeVersion: Long = 10,
      session: SessionState = session
  ) {
    def opening: Opening =
      Opening.of(serving, cursor, reconnect, log, storeVersion, session)
  }

  import RepaintReason.*

  private val table: List[(String, Row, Opening)] = List(
    ("a reconnect resumes from its version", Row(), Opening.Resume(7)),
    (
      "a document resumes after its version",
      Row(reconnect = false),
      Opening.Resume(8)
    ),
    (
      "a moved style alone still resumes",
      Row(cursor = Some(cursor.copy(styleHash = "s2"))),
      Opening.Resume(7)
    ),
    (
      "a session not yet painted resumes",
      Row(session = session.copy(painter = Painter.Unpainted)),
      Opening.Resume(7)
    ),
    ("a failed slug reloads", Row(serving = None), Opening.Reload),
    (
      "a moved head reloads",
      Row(cursor = Some(cursor.copy(headHash = "h2"))),
      Opening.Reload
    ),
    ("no cursor", Row(cursor = None), Opening.Repaint(NoCursor)),
    (
      "another log's cursor",
      Row(cursor = Some(cursor.copy(logId = "M"))),
      Opening.Repaint(OtherLog)
    ),
    (
      "a log the serving renderer does not number",
      Row(log = log.copy(stamp = Some(other))),
      Opening.Repaint(OtherLog)
    ),
    (
      "a cursor ahead of the store",
      Row(storeVersion = 6),
      Opening.Repaint(AheadOfStore)
    ),
    (
      "a cursor before a gap nobody watched",
      Row(
        cursor = Some(cursor.copy(version = 2)),
        session = session.copy(told = 2)
      ),
      Opening.Repaint(BeforeGap)
    ),
    (
      "a cursor behind told",
      Row(cursor = Some(cursor.copy(version = 5))),
      Opening.Repaint(BehindTold)
    ),
    (
      "holds painted by another renderer",
      Row(session = session.copy(painter = Painter.By(other))),
      Opening.Repaint(HeldElsewhere)
    )
  )

  table.foreach((name, row, expected) =>
    test(name)(assertEquals(row.opening, expected))
  )
}
