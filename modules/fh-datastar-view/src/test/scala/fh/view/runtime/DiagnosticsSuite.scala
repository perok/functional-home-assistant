package fh.view.runtime

import io.circe.Json

/** The memory report `GET /system/diagnostics` answers with.
  *
  * The cgroup half is asserted through a FIXTURE directory rather than the real
  * `/sys/fs/cgroup`, because the numbers there are the machine's and a test
  * that reads them can only assert that parsing did not throw. The JVM half is
  * asserted on the running JVM, since the whole claim being made is that the
  * platform MXBeans answer with no flag and no agent.
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
    // The case that matters: the supervisor gives an add-on no memory limit, so
    // `memory.max` is the literal "max". Reporting that IS the explanation for
    // why a JVM sizing itself as a percentage of available memory sized itself
    // against the whole Pi.
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
    // `current` is what the supervisor's percentage is computed from, and it
    // charges the add-on for page cache it did not allocate — so a report that
    // gave only the total would invite blaming the JVM for the file half.
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
    // A local `dashboardServe` on a laptop, or any non-Linux host: the
    // container half is genuinely unknown, and that must not cost the half
    // that is knowable.
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

      // The claim the endpoint rests on: metaspace and the code cache are
      // ordinary memory pools, so the breakdown people reach for NMT to get is
      // already here without it.
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
    // This is the claim that replaces `docker exec … jcmd`: the platform
    // registers a DiagnosticCommand MBean, and `vmNativeMemory` is invokable
    // in process. Asserted on the RAW answer, because the reported field is
    // `None` when tracking is merely off and a broken operation name or
    // argument signature would look exactly the same there.
    Diagnostics.nmtText.map(text =>
      assert(text.isDefined, "the DiagnosticCommand MBean did not answer")
    )
  }

  test("NMT is the summary or absent, never the 'not enabled' sentence") {
    // The MBean answers with prose rather than failing when tracking is off,
    // so passing its text straight through would put a sentence in a field
    // callers read as the summary. Which arm runs depends on how this JVM was
    // started, so both are accepted — but only these two.
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

  test("a malformed cgroup file is reported as unknown rather than raising") {
    val dir = cgroupDir("not-a-number", Some("max"), "anon not-a-number\n")
    Diagnostics.report(dir).map { json =>
      assertEquals(field(json, "container", "current"), Json.Null)
      assertEquals(field(json, "container", "anon"), Json.Null)
    }
  }

  test("the report never raises, whatever the cgroup root is") {
    // It is a diagnostic: failing to describe the machine must not become a
    // 500 on the one route someone opens when the add-on is misbehaving.
    val file = os.temp.dir() / "not-a-dir"
    os.write(file, "")
    Diagnostics.report(file).attempt.map(r => assert(r.isRight, s"raised: $r"))
  }
}
