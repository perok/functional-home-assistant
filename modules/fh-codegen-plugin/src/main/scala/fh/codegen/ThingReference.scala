package fh.codegen

import fh.codegen.utils.Helpers
import java.util.stream.Collectors

case class AbsolutePosition(directory: String, namespace: List[String])
case class ThingReference[T](
    thing: T,
    name: String,
    pckage: List[String],
    toCode: () => String
) {
  assume(name.nonEmpty, s"Name for a thing $thing missing")


  def toPath(using abspos: AbsolutePosition) = {
    // File names cannot have emojies for the compiler to work.
    val nameLol = name.codePoints().mapToObj(cp => 
        if (Character.isEmojiPresentation(cp)) then Character.getName(cp)
        else new String(Array(cp), 0, 1)
      ).collect(Collectors.joining())

    val nameEscaped = nameLol.replace(" ", "-").replace("/", "_")
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
