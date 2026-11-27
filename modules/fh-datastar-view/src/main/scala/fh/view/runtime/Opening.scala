package fh.view.runtime

/** What a (re)connecting client is sent first (ADR 0011): a '''reload''' when
  * the head's unpatchable part moved ([[Renderer.headHash]]) or the slug
  * failed, a '''resume''' when the cursor provably names what this DOM holds,
  * else a '''repaint''' — always correct, where a wrong resume is silently
  * stale forever.
  */
private[runtime] enum Opening {
  case Reload
  case Resume(from: Long)
  case Repaint(reason: RepaintReason)
}

/** Why a cursor does not name what its DOM holds. `Patches.resume` is total, so
  * only these global reasons repaint.
  */
private[runtime] enum RepaintReason {
  case NoCursor

  /** A restart's or a swap's: the cursor quotes another log, or the log read is
    * one the serving renderer does not number (the next renderer's).
    */
  case OtherLog

  /** A rewound counter. */
  case AheadOfStore

  /** From before a stretch nobody watched ([[FragmentLog.reaches]]). */
  case BeforeGap

  /** `holds` records what was SENT, and a cursor rides last in its batch, so an
    * echo behind `told` means claimed bytes were lost.
    */
  case BehindTold

  /** `holds` is one renderer's, painted under another than the serving one. */
  case HeldElsewhere
}

private[runtime] object Opening {

  def of(
      serving: Option[Renderer.Stamp],
      cursor: Option[Server.Cursor],
      reconnect: Boolean,
      log: FragmentLog,
      storeVersion: Long,
      s: SessionState
  ): Opening =
    serving.fold(Reload) { r =>
      cursor match
        case Some(c) if c.headHash != r.headHash => Reload
        case None    => Repaint(RepaintReason.NoCursor)
        case Some(c) =>
          val heldElsewhere = s.painter match
            case Painter.By(p) => !(p eq r)
            case _             => false
          if (c.logId != log.id || !log.numberedBy(r))
            Repaint(RepaintReason.OtherLog)
          else if (c.version > storeVersion) Repaint(RepaintReason.AheadOfStore)
          else if (!log.reaches(c.version)) Repaint(RepaintReason.BeforeGap)
          else if (c.version < s.told) Repaint(RepaintReason.BehindTold)
          else if (heldElsewhere) Repaint(RepaintReason.HeldElsewhere)
          else Resume(resumeFrom(c, reconnect))
    }

  /** A reconnect's cursor (signals) may hold only part of version V — one
    * version can produce several batches — so it resumes from V. A document's
    * (query params) was rendered from one snapshot and has all of V, so it
    * resumes from V + 1.
    */
  def resumeFrom(c: Server.Cursor, reconnect: Boolean): Long =
    if (reconnect) c.version else c.version + 1
}
