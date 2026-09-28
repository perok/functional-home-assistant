package fh.view.telemetry

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

import java.lang.management.ManagementFactory
import javax.management.ObjectName
import scala.util.control.NonFatal

/** What the add-on costs the machine, in process: the container figure (which
  * includes page cache) beside the JVM's, since either alone misleads. In
  * process because `docker exec … jcmd` needs the SSH add-on with protection
  * mode off.
  *
  * The container half is read from the cgroup, the same number the Supervisor's
  * stats report, but with no `hassio_api` permission and with the anon/file
  * split.
  */
object Diagnostics {

  val CgroupRoot: os.Path = os.root / "sys" / "fs" / "cgroup"

  def report(cgroupRoot: os.Path = CgroupRoot): IO[Json] =
    (cgroup(cgroupRoot), jvm, nmt).mapN { (container, vm, tracking) =>
      Json.obj(
        "container" -> container.getOrElse(Json.Null),
        "jvm" -> vm,
        "nmt" -> tracking.fold(Json.Null)(Json.fromString)
      )
    }

  /** The pools already break out metaspace and the code heaps, so all but
    * GC-native and thread stacks is here without NMT.
    */
  private def jvm: IO[Json] = IO {
    val memory = ManagementFactory.getMemoryMXBean
    val heap = memory.getHeapMemoryUsage
    val nonHeap = memory.getNonHeapMemoryUsage

    val pools = ManagementFactory.getMemoryPoolMXBeans.asScalaList.map { pool =>
      val usage = pool.getUsage
      pool.getName -> Json.obj(
        "used" -> Json.fromLong(usage.getUsed),
        "committed" -> Json.fromLong(usage.getCommitted)
      )
    }

    val collectors = ManagementFactory.getGarbageCollectorMXBeans.asScalaList
      .map(gc =>
        Json.obj(
          "name" -> Json.fromString(gc.getName),
          "count" -> Json.fromLong(gc.getCollectionCount),
          "ms" -> Json.fromLong(gc.getCollectionTime)
        )
      )

    Json.obj(
      "heap" -> usageJson(heap),
      "nonHeap" -> usageJson(nonHeap),
      "pools" -> Json.obj(pools*),
      "gc" -> Json.arr(collectors*),
      "threads" -> Json.fromInt(
        ManagementFactory.getThreadMXBean.getThreadCount
      ),
      "uptimeMs" -> Json.fromLong(ManagementFactory.getRuntimeMXBean.getUptime)
    )
  }

  private def usageJson(usage: java.lang.management.MemoryUsage): Json =
    Json.obj(
      "used" -> Json.fromLong(usage.getUsed),
      "committed" -> Json.fromLong(usage.getCommitted),
      // -1 means no ceiling, which a number would misstate as a limit.
      "max" -> (if (usage.getMax < 0) Json.Null
                else Json.fromLong(usage.getMax))
    )

  /** `None` unless started with `-XX:NativeMemoryTracking` (the
    * `memory_tracking` option). Tested on the text: with NMT off the MBean
    * answers a sentence rather than failing.
    */
  private def nmt: IO[Option[String]] =
    nmtText.map(_.filter(_.contains("Native Memory Tracking:")))

  /** `None` only when the call failed. [[nmt]] is `None` for a broken
    * invocation too, so the suite asserts on this to pin the mechanism.
    */
  private[telemetry] def nmtText: IO[Option[String]] =
    diagnosticCommand("vmNativeMemory", Array("summary"))

  /** A jcmd command by its camel-case operation name. The MBean is dynamic, so
    * a wrong name or signature only fails at run time.
    */
  private def diagnosticCommand(
      operation: String,
      args: Array[String]
  ): IO[Option[String]] = IO {
    val server = ManagementFactory.getPlatformMBeanServer
    val name = new ObjectName("com.sun.management:type=DiagnosticCommand")
    server.invoke(
      name,
      operation,
      Array[Object](args),
      Array(classOf[Array[String]].getName)
    ) match {
      case text: String => Some(text)
      case _            => None
    }
  }.handleError(_ => None)

  /** Not part of [[report]]: it is large and pauses every thread. */
  def threadDump: IO[String] =
    diagnosticCommand("threadPrint", Array("-l")).map(
      _.getOrElse("thread dump unavailable")
    )

  /** What a thread dump cannot show: which fiber is parked where.
    *
    * Through cats-effect's MBean because `IO.runtime` is `private[effect]`, and
    * `IORuntime.global` would create a second runtime when none is installed.
    * The name carries an incrementing id, hence the pattern.
    */
  def fiberDump: IO[String] = IO {
    val server = ManagementFactory.getPlatformMBeanServer
    val pattern =
      new ObjectName(
        "cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-*"
      )
    val triggers =
      server.queryNames(pattern, null).asScalaSet.toList.sortBy(_.toString)
    // Per trigger, both failures seen for real: a runtime shutting down
    // unregisters between query and call, and a monitor without a
    // work-stealing pool throws NPE on its null `fiberBag`.
    val sections = triggers.flatMap(name =>
      try
        server.invoke(name, "liveFiberSnapshot", null, null) match {
          case lines: Array[?] => lines.toList.map(String.valueOf)
          case other           => List(String.valueOf(other))
        }
      catch { case NonFatal(_) => Nil }
    )
    if (triggers.isEmpty) "no fiber monitor is registered on this runtime"
    else if (sections.isEmpty) "no fiber monitor could answer"
    else sections.mkString("\n")
  }.handleError(e => s"fiber dump unavailable: ${e.getMessage}")

  /** `None` off Linux, so a laptop reports the JVM half only. */
  private def cgroup(root: os.Path): IO[Option[Json]] =
    IO.blocking {
      def read(name: String): Option[String] =
        Option.when(os.exists(root / name))(os.read(root / name).trim)

      (read("memory.current"), read("memory.stat")).mapN { (current, stat) =>
        parseCgroup(current, read("memory.max"), stat)
      }
    }.handleError(_ => None)

  /** `memory.max` verbatim, including `"max"`: an add-on gets no limit, which
    * is why a percentage-sized heap sizes against the whole machine (see
    * `home-addon/run.sh`).
    */
  private[telemetry] def parseCgroup(
      current: String,
      max: Option[String],
      stat: String
  ): Json = {
    val fields = stat.linesIterator
      .map(_.split(' '))
      .collect { case Array(key, value) => key -> value }
      .toMap

    def bytes(key: String): Json =
      fields.get(key).flatMap(_.toLongOption).fold(Json.Null)(Json.fromLong)

    Json.obj(
      // What the supervisor's UI percentage is computed from.
      "current" -> current.toLongOption.fold(Json.Null)(Json.fromLong),
      "max" -> max.fold(Json.Null)(Json.fromString),
      "anon" -> bytes("anon"),
      // Page cache: charged to the add-on, not allocated by it.
      "file" -> bytes("file")
    )
  }

  extension [A](list: java.util.List[A])
    private def asScalaList: List[A] = {
      val builder = List.newBuilder[A]
      list.forEach(a => builder += a)
      builder.result()
    }

  extension [A](set: java.util.Set[A])
    private def asScalaSet: Set[A] = {
      val builder = Set.newBuilder[A]
      set.forEach(a => builder += a)
      builder.result()
    }
}
