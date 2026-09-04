package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

import java.lang.management.ManagementFactory
import javax.management.ObjectName
import scala.util.control.NonFatal

/** What the add-on is spending on the machine, answerable over HTTP.
  *
  * The question this exists for is "the add-on is using 10% of the Pi's RAM —
  * is that expected?", and the honest answer needs two numbers side by side:
  * what the SUPERVISOR sees (a container figure that includes page cache) and
  * what the JVM sees (a heap that grew to fit its workload, plus metaspace,
  * code cache and GC structures). Reported apart, each one invites the wrong
  * conclusion.
  *
  * Everything here is read IN PROCESS. The alternative — `docker exec … jcmd` —
  * needs the SSH add-on with protection mode off, so on a stock HAOS install
  * the answer is gated behind a thing most people do not have, for a question
  * they are asking from the UI.
  *
  * The container half is read from the cgroup rather than from the Supervisor's
  * `/addons/self/stats`: it is the same number by construction (that is where
  * the Supervisor gets it), it needs no `hassio_api` permission and no network
  * call, and it carries the anon/file split — which matters, because page cache
  * counts toward the figure the UI shows and is not the JVM's doing.
  */
object Diagnostics {

  /** cgroup v2's mount point inside the container. A parameter only so
    * [[DiagnosticsSuite]] can point at a fixture directory.
    */
  val CgroupRoot: os.Path = os.root / "sys" / "fs" / "cgroup"

  def report(cgroupRoot: os.Path = CgroupRoot): IO[Json] =
    (cgroup(cgroupRoot), jvm, nmt).mapN { (container, vm, tracking) =>
      Json.obj(
        "container" -> container.getOrElse(Json.Null),
        "jvm" -> vm,
        "nmt" -> tracking.fold(Json.Null)(Json.fromString)
      )
    }

  /** The JVM's own accounting, via the platform MXBeans — no flags, no agent,
    * and `java.management` has always been in the add-on's jlink set.
    *
    * The pools ARE the breakdown people reach for NMT to get: Metaspace,
    * Compressed Class Space and the three CodeHeaps are memory pools like the
    * heap generations, so everything but GC-native and thread stacks is here
    * for free.
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
      // -1 for a pool with no ceiling; reported as null rather than as a
      // number that would read as a real limit.
      "max" -> (if (usage.getMax < 0) Json.Null
                else Json.fromLong(usage.getMax))
    )

  /** The Native Memory Tracking summary, through the platform's own
    * `DiagnosticCommand` MBean — the same text `jcmd VM.native_memory summary`
    * prints, without a jcmd or an attach.
    *
    * `None` unless the add-on was started with `-XX:NativeMemoryTracking` (the
    * `memory_tracking` option), because NMT cannot be switched on in a running
    * JVM. The MBean answers with a sentence saying so rather than failing, so
    * the "is it on" test is on the TEXT.
    */
  private def nmt: IO[Option[String]] =
    nmtText.map(_.filter(_.contains("Native Memory Tracking:")))

  /** The MBean's answer VERBATIM, whether or not NMT is enabled — `None` only
    * when the call itself failed.
    *
    * Split out from [[nmt]] for the suite's benefit, and it is not a gratuitous
    * seam: [[nmt]] answers `None` both when tracking is off and when the
    * invocation is wrong, so a test written against it alone passes just as
    * happily with a broken operation name or signature. Asserting this one is
    * defined is what actually pins the mechanism.
    */
  private[runtime] def nmtText: IO[Option[String]] =
    diagnosticCommand("vmNativeMemory", Array("summary"))

  /** One `jcmd` command, run in process.
    *
    * The platform registers a DIAGNOSTIC COMMAND MBean whose operations are the
    * jcmd commands, named in camel case — `VM.native_memory` is
    * `vmNativeMemory`, `Thread.print` is `threadPrint`. Every one of them takes
    * a single `String[]` of arguments, which is why the signature below is
    * spelled out rather than inferred: the MBean is dynamic, so a wrong
    * operation name or argument type is found at RUN time, not compile time.
    */
  private def diagnosticCommand(
      operation: String,
      args: Array[String]
  ): IO[Option[String]] = IO {
    val server = ManagementFactory.getPlatformMBeanServer
    val name = new ObjectName("com.sun.management:type=DiagnosticCommand")
    // Matched rather than cast: the operation's declared return type is
    // `Object` and nothing but this pattern says we expect text back.
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

  /** A JVM thread dump — the same text `jcmd <pid> Thread.print` prints, via
    * the same `DiagnosticCommand` MBean [[nmtText]] uses.
    *
    * Not folded into [[report]]: it is large, it is meant to be read rather
    * than parsed, and producing it pauses every thread — which is not a price
    * to pay for asking how much memory is in use.
    */
  def threadDump: IO[String] =
    diagnosticCommand("threadPrint", Array("-l")).map(
      _.getOrElse("thread dump unavailable")
    )

  /** A cats-effect fiber dump — the same snapshot the runtime prints on
    * SIGUSR1.
    *
    * The JVM's thread dump cannot answer this. Almost all of this server's work
    * runs as FIBERS multiplexed over a handful of carrier threads, so a thread
    * dump taken while a dashboard is stuck shows a work-stealing pool sitting
    * idle and says nothing about which fiber is parked or where. This is the
    * one that names it.
    *
    * Read through cats-effect's own MBean rather than off the `IORuntime`, for
    * the plain reason that `IO.runtime` is `private[effect]` — and the
    * alternative, `IORuntime.global`, is worse than inaccessible: when no
    * global has been installed it CREATES one, so a diagnostic endpoint would
    * spin up a second thread pool as a side effect of being asked a question.
    *
    * The object name carries an incrementing id
    * (`…:type=LiveFiberSnapshotTrigger-3`), so it is matched by pattern; a
    * runtime that registered no trigger simply has no match, which is reported
    * rather than raised.
    */
  def fiberDump: IO[String] = IO {
    val server = ManagementFactory.getPlatformMBeanServer
    val pattern =
      new ObjectName(
        "cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-*"
      )
    val triggers =
      server.queryNames(pattern, null).asScalaSet.toList.sortBy(_.toString)
    // Guarded PER TRIGGER, because a process can hold several runtimes and one
    // of them failing must not take the answer down with it. Two ways one
    // does, both hit for real: a runtime shutting down unregisters its trigger
    // between the query and the call, and a monitor not backed by a
    // work-stealing pool has a null `fiberBag` and throws NPE from inside
    // cats-effect.
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

  /** The container figure, from cgroup v2. `None` off Linux, or wherever the
    * files are not readable — a local `dashboardServe` on a laptop reports the
    * JVM half only rather than failing.
    */
  private def cgroup(root: os.Path): IO[Option[Json]] =
    IO.blocking {
      def read(name: String): Option[String] =
        Option.when(os.exists(root / name))(os.read(root / name).trim)

      (read("memory.current"), read("memory.stat")).mapN { (current, stat) =>
        parseCgroup(current, read("memory.max"), stat)
      }
    }.handleError(_ => None)

  /** Pure, so the parsing is testable without a cgroup.
    *
    * `memory.max` is reported VERBATIM, including the literal `"max"` that
    * means no limit — which is the single most useful field here. An add-on
    * gets no memory limit from the supervisor, and an unlimited cgroup is
    * exactly why a JVM sizing itself as a percentage of "available" memory
    * sizes itself against the whole machine (see `home-addon/run.sh`).
    */
  private[runtime] def parseCgroup(
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
      // The half of `current` that is the JVM's own memory...
      "anon" -> bytes("anon"),
      // ...and the half that is page cache, which the add-on is charged for
      // but did not allocate.
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
