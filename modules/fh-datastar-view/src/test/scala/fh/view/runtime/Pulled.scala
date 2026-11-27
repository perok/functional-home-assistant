package fh.view.runtime

/** A live pull for a test or a bench, minted as `Server.owed` mints it: over a
  * log the renderer numbers, by a session it painted.
  */
object Pulled {

  def log(r: Renderer): FragmentLog = FragmentLog("pulled", Some(r.generation))

  def from(r: Renderer, log: FragmentLog, v: Long): Patches.Coverage =
    Patches.Watched
      .of(log, Painter.By(r.generation), v - 1)
      .fold(sys.error(s"the log does not reach ${v - 1}"))(
        Patches.Coverage.Logged(_)
      )
}
