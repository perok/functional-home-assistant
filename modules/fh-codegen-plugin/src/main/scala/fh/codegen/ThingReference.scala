package fh.codegen

import fh.codegen.utils.Helpers

case class AbsolutePosition(directory: String, namespace: List[String])
case class ThingReference[T](
    thing: T,
    name: String,
    pckage: List[String],
    toCode: () => String
) {
  def toPath(using abspos: AbsolutePosition) = {
    val nameEscaped = name.replace(" ", "-").replace("/", "_")
    java.nio.file.Paths
      .get(
        abspos.directory,
        abspos.namespace.appendedAll(pckage).appended(s"$nameEscaped.scala")*
      )
  }

  def toCodeFileContent(using abspos: AbsolutePosition) = {
    val pcgName = (abspos.namespace.appendedAll(pckage)).mkString("", ".", "")
    s"""package $pcgName
       |
       |${toCode()}
       |""".stripMargin
  }

  def toRootReferenceAsObjectType(using abspos: AbsolutePosition): String =
    s"$almostFullyQualifiedName.type"

  def almostFullyQualifiedName(using abspos: AbsolutePosition): String =
    s"_root_${abspos.namespace.appendedAll(pckage).mkString(".", ".", ".")}${Helpers.objectNameSafe(name)}"
}
