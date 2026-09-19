addSbtPlugin(
  "com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.7"
)

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.6")
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.3.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

// Scalafix (#115). Runs the compiler's own -Wunused findings back into the
// sources (`scalafixAll RemoveUnused`) instead of hand-deleting imports, which
// is what made the warning gate affordable to turn on.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")

// Fat jar for the HA add-on image (home-addon/Dockerfile). 2.3.x is
// cross-published for sbt 2.x.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")

// Benchmarks (`fh-datastar-view/Jmh/run`). A hand-rolled nanoTime loop was
// tried first and was wrong in the way such loops always are: no fork, so
// seven measurements in one JVM inherited each other's JIT state, and the
// per-bucket numbers moved 2x between runs of the same file.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
