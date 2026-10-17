package fh.view.build

import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture, PklWorkspace}
import io.circe.Json
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** What Pkl evaluation costs as a house grows — the build-phase counterpart to
  * [[fh.view.runtime.RenderBench]], which measures a page open once the
  * dashboard is already built.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 2 -i 3 .*EvalBench.*'                  # quick look
  * sbt 'benchmarks/Jmh/run -f 1 -wi 2 -i 3 -p entities=1000 .*EvalBench.*' # one size
  * }}}
  *
  * '''Read the shape of the entity-count column, not the absolute numbers.'''
  * The question this answers is whether cost grows with the house LINEARLY. It
  * exists because it did not: `query.pkl`'s condition fold resolved a property
  * name once per candidate while the resolution itself scanned every candidate,
  * so an unscoped set was quadratic (#108). Nothing said so — the shipped
  * dashboards were fast, `q.stateProp` short-circuits before the scan, and the
  * cost only appeared in a house nobody had. Doubling `entities` and reading
  * the ratio is what would have caught it in one run.
  *
  * Growth in step with `entities` is fine; growth in step with its SQUARE is
  * that bug class returning. This is deliberately NOT a test: the assertion
  * form needs either an absolute threshold calibrated per machine, or four
  * evaluations on every CI run, to catch something that has happened once.
  * Keeping the measurement one command away is the part that had value.
  *
  * ==Baseline==
  *
  * `-f 1 -wi 1 -i 3`, ms/op, one machine, after the fold fix (#327). Read the
  * RATIOS down a column, not the absolutes.
  *
  * {{{
  *   entities   dumpOnly   scoped   unscoped
  *        250       27.6     32.9       54.1
  *       1000       39.0     52.4      132.3
  *       4000      105.6    137.3      765.8
  * }}}
  *
  * `dumpOnly` barely moves against a 16x house: Pkl evaluates the entity
  * classes a dashboard TOUCHES, not all of them, so one generated class per
  * entity is not the cost it looks like. What grows is per-candidate node
  * building, which is the work itself.
  *
  * `unscoped` grows faster than its candidate count at 4000 (5.8x for 4x the
  * house). That is not the old quadratic — 16x the house would be ~100x, not
  * 14x — and it is attributed to allocation, on the evidence that nothing
  * algorithmic remains rather than on a profile. Take these settings' error
  * bars seriously before reading anything finer: one warmup iteration leaves
  * the 4000 rows with intervals wider than the gaps between them.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
class EvalBench {

  @Param(Array("250", "1000", "4000"))
  var entities: Int = 0

  private var workspace: os.Path = scala.compiletime.uninitialized

  /** A house shaped like a real instance rather than a uniform one: mostly
    * sensors, a fifth lights, the rest switches and binary sensors, and a
    * `device_class` only some of them carry — an optional registry fact is what
    * makes a `where` resolve a name against the whole candidate list.
    */
  private def house(n: Int): Json = {
    val extra = (0 until n).toList.map { i =>
      val name = Json.fromString(s"Entity $i")
      i % 5 match {
        case 0 =>
          FixtureEntity(
            s"light.gen_$i",
            if (i % 3 == 0) "on" else "off",
            Map("friendly_name" -> name, "brightness" -> Json.fromInt(i % 255))
          )
        case 1 =>
          FixtureEntity(
            s"switch.gen_$i",
            if (i % 2 == 0) "on" else "off",
            Map("friendly_name" -> name)
          )
        case 2 =>
          FixtureEntity(
            s"binary_sensor.gen_$i",
            "off",
            Map(
              "friendly_name" -> name,
              "device_class" -> Json.fromString("motion")
            )
          )
        case _ =>
          FixtureEntity(
            s"sensor.gen_$i",
            (i % 100).toString,
            Map(
              "friendly_name" -> name,
              "device_class" -> Json.fromString(
                if (i % 7 == 0) "battery" else "temperature"
              ),
              "unit_of_measurement" -> Json.fromString(
                if (i % 7 == 0) "%" else "°C"
              )
            )
          )
      }
    }
    HouseFixture.dumpWith(extra*)
  }

  private val preamble =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-home/dump.pkl" as dump
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |""".stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(house(entities)))

    // The dump is IMPORTED but only one entity is read: the fixed cost of
    // having a typed dump at all, separated from what querying it costs.
    os.write.over(
      tmp / "dump-only.pkl",
      s"""$preamble
         |card = (c.column) {
         |  children {
         |    c.entityCard(dump.entities.sensor_outside_temp)
         |  }
         |}
         |""".stripMargin
    )

    // The two shapes `pkl-demo` runs, which is where #108 was measured: an
    // unscoped set over the whole house, plus the optional-registry-fact filter.
    os.write.over(
      tmp / "unscoped.pkl",
      s"""$preamble
         |card = (c.column) {
         |  children {
         |    q.from(dump.all)
         |      .where(q.eq(q.stateProp, "on"))
         |      .caseOf(q.eq(q.prop("domain"), "light"), c.slider)
         |      .`else`((e) -> c.entityCard(e).tapAction(c.tap.toggle))
         |      .build()
         |    q.from(dump.sensors)
         |      .where(q.eq(q.optional(q.prop("device_class")), "battery"))
         |      .where(q.lt(q.stateProp, 20))
         |      .render((e) -> c.entityCard(e))
         |      .build()
         |  }
         |}
         |""".stripMargin
    )

    // The same work with candidates narrowed by the dump's own domain list —
    // what an author writes when they know which entities they mean.
    os.write.over(
      tmp / "scoped.pkl",
      s"""$preamble
         |card = (c.column) {
         |  children {
         |    q.from(dump.lights)
         |      .where(q.eq(q.stateProp, "on"))
         |      .render((e) -> c.entityCard(e).tapAction(c.tap.toggle))
         |      .build()
         |  }
         |}
         |""".stripMargin
    )

    workspace = tmp
  }

  private def eval(entry: String, bh: Blackhole): Unit =
    bh.consume(
      SourceEval
        .eval(workspace, entry)
        .fold(err => sys.error(s"$entry failed: $err"), _.value.noSpaces.length)
    )

  @Benchmark def dumpOnly(bh: Blackhole): Unit = eval("dump-only.pkl", bh)
  @Benchmark def unscoped(bh: Blackhole): Unit = eval("unscoped.pkl", bh)
  @Benchmark def scoped(bh: Blackhole): Unit = eval("scoped.pkl", bh)
}
