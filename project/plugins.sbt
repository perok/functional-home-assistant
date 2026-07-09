addSbtPlugin(
  "com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.7"
)

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.6")
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.2.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

// Fat jar for the HA add-on image (home-addon/Dockerfile). 2.3.x is
// cross-published for sbt 2.x.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")
