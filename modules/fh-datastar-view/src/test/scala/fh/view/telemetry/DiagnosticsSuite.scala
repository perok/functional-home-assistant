package fh.view.telemetry

import cats.effect.IO
import io.circe.Json

import scala.concurrent.duration.*

/** The report `GET /system/diagnostics` answers with. The cgroup half reads a
  * fixture directory, since the real `/sys/fs/cgroup` numbers are the
  * machine's; the JVM half reads the running JVM, since the claim is that the
  * platform MXBeans answer with no flag or agent.
  */
class DiagnosticsSuite extends munit.CatsEffectSuite {

  private def cgroupDir(
      current: String,
      max: Option[String],
      stat: String
  ): os.Path = {
    val dir = os.temp.dir()
    os.write(dir / "memory.current", current)
    os.write(dir / "memory.stat", stat)
    max.foreach(m => os.write(dir / "memory.max", m))
    dir
  }

  private def field(json: Json, path: String*): Json =
    path.foldLeft(json)((j, key) =>
      j.hcursor.downField(key).focus.getOrElse(Json.Null)
    )

  test("an unlimited cgroup reports its limit verbatim, not as a number") {
    // The supervisor gives an add-on no memory limit, so `memory.max` is "max":
    // the explanation for a JVM sizing itself against the whole Pi.
    val dir = cgroupDir(
      "12660985856",
      Some("max"),
      "anon 11912589312\nfile 464429056\n"
    )
    Diagnostics.report(dir).map { json =>
      assertEquals(field(json, "container", "max"), Json.fromString("max"))
      assertEquals(
        field(json, "container", "current"),
        Json.fromLong(12660985856L)
      )
    }
  }

  test(
    "the container figure separates what the JVM allocated from page cache"
  ) {
    // `current`, which the supervisor's percentage uses, charges page cache the
    // add-on did not allocate; a total alone would invite blaming the JVM.
    val dir = cgroupDir("500", Some("max"), "anon 300\nfile 200\nslab 12\n")
    Diagnostics.report(dir).map { json =>
      assertEquals(field(json, "container", "anon"), Json.fromLong(300L))
      assertEquals(field(json, "container", "file"), Json.fromLong(200L))
    }
  }

  test("a missing memory.max is absent, not a fabricated limit") {
    val dir = cgroupDir("500", None, "anon 300\nfile 200\n")
    Diagnostics
      .report(dir)
      .map(json => assertEquals(field(json, "container", "max"), Json.Null))
  }

  test("no cgroup at all still reports the JVM half") {
    // A laptop or non-Linux host: the unknown container half must not cost the
    // knowable one.
    Diagnostics.report(os.temp.dir() / "absent").map { json =>
      assertEquals(field(json, "container"), Json.Null)
      assert(field(json, "jvm", "heap", "committed").asNumber.isDefined)
    }
  }

  test("the JVM half needs no flag: heap, the pools, and GC all answer") {
    Diagnostics.report(os.temp.dir() / "absent").map { json =>
      val heap = field(json, "jvm", "heap")
      assert(heap.hcursor.get[Long]("used").isRight, "heap.used")
      assert(heap.hcursor.get[Long]("committed").isRight, "heap.committed")

      // Metaspace and the code cache are ordinary pools, so the breakdown
      // people reach for NMT to get is here without it.
      val pools =
        field(json, "jvm", "pools").asObject.map(_.keys.toList).getOrElse(Nil)
      assert(
        pools.exists(_.contains("Metaspace")),
        s"no Metaspace pool in $pools"
      )
      assert(
        pools.exists(_.contains("CodeHeap")),
        s"no CodeHeap pool in $pools"
      )

      assert(
        field(json, "jvm", "gc").asArray.exists(_.nonEmpty),
        "no garbage collectors reported"
      )
      assert(field(json, "jvm", "threads").asNumber.isDefined, "threads")
    }
  }

  test(
    "the DiagnosticCommand MBean answers — which is the whole endpoint's premise"
  ) {
    // What replaces `docker exec … jcmd`: `vmNativeMemory` invoked in process.
    // Asserted on the raw answer, since the reported field is `None` both when
    // tracking is off and when the operation name is wrong.
    Diagnostics.nmtText.map(text =>
      assert(text.isDefined, "the DiagnosticCommand MBean did not answer")
    )
  }

  test("NMT is the summary or absent, never the 'not enabled' sentence") {
    // With tracking off the MBean answers with prose, which must not land in a
    // field read as the summary. Which arm runs depends on the JVM's flags.
    Diagnostics.report(os.temp.dir() / "absent").map { json =>
      field(json, "nmt") match {
        case Json.Null => ()
        case other     =>
          assert(
            other.asString.exists(_.contains("Native Memory Tracking:")),
            s"nmt should be the summary or null, was: $other"
          )
      }
    }
  }

  test("the thread dump is a real one, not the unavailable placeholder") {
    // A wrong operation name would fall into `handleError` and return a
    // plausible string, so this asserts content every dump has.
    Diagnostics.threadDump.map { dump =>
      assert(dump.contains("\"main\""), s"no main thread in dump: $dump")
      assert(dump.contains("java.lang.Thread.State"), "no thread states")
    }
  }

  test("the thread dump includes lock info, which is what finds a deadlock") {
    // Without `-l` there is no ownable-synchronizer section and a deadlock is
    // invisible.
    Diagnostics.threadDump.map(dump =>
      assert(
        dump.contains("Locked ownable synchronizers") ||
          dump.contains("locked <"),
        "no lock information — was the -l argument dropped?"
      )
    )
  }

  test("the fiber dump names fibers, not threads") {
    // A suspended fiber is parked first: that is what the monitor tracks, and
    // an idle runtime can report almost nothing, so dumping one proves nothing.
    IO.sleep(1.hour)
      .start
      .flatMap(parked => Diagnostics.fiberDump.guarantee(parked.cancel))
      .map { dump =>
        assert(
          !dump.startsWith("fiber dump unavailable"),
          s"the MBean call failed: $dump"
        )
        assert(
          !dump.startsWith("no fiber monitor"),
          "no trigger answered. Either the ObjectName pattern no longer " +
            "matches, or every runtime's monitor threw — under the full " +
            "suite there are several, and only some can answer: " + dump
        )
        assert(dump.trim.nonEmpty, "the dump was empty with a fiber parked")
      }
  }

  test("a malformed cgroup file is reported as unknown rather than raising") {
    val dir = cgroupDir("not-a-number", Some("max"), "anon not-a-number\n")
    Diagnostics.report(dir).map { json =>
      assertEquals(field(json, "container", "current"), Json.Null)
      assertEquals(field(json, "container", "anon"), Json.Null)
    }
  }

  test("the report never raises, whatever the cgroup root is") {
    // A diagnostic must not become a 500 on the route opened when the add-on
    // misbehaves.
    val file = os.temp.dir() / "not-a-dir"
    os.write(file, "")
    Diagnostics.report(file).attempt.map(r => assert(r.isRight, s"raised: $r"))
  }
}
