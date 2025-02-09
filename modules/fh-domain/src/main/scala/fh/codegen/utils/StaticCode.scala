package fh.codegen.utils

import shapeless3.deriving.*

case class Field(
    label: String,
    staticInstantiate: String
)
trait StaticCode[T] {
  def fields(in: T): List[Field]
  def label: String

  def toStatic(
      in: T,
      `type`: String = "object",
      overrideLabel: Option[String] = None,
      `extends`: List[String] = List.empty,
      additionalContent: String = ""
  ) = {

    val allFields = fields(in)
      .map { case Field(name, value) =>
        s"val ${Helpers.paramNameSafe(name)} = $value"
      }
      .mkString("\n")

    s"""
       |object ${Helpers.objectNameSafe(
        overrideLabel.getOrElse(label)
      )} ${`extends`.mkString("extends ", ", ", "")} {
       | $allFields
       | 
       | $additionalContent
       |}
       |""".stripMargin
  }
}
object StaticCode {
  def apply[A](using a: StaticCode[A]): StaticCode[A] = a

  given derived[T](using
      inst: K0.ProductInstances[ToCode, T],
      labelling: Labelling[T]
  ): StaticCode[T] with
    val label = labelling.label

    def fields(t: T): List[Field] =
      labelling.elemLabels.zipWithIndex
        .map((label, i) =>
          val stringInstantiatedValue = inst.project(t)(i)(
            [t] => (tc: ToCode[t], v: t) => tc.staticInstantiate(v)
          )

          Field(label, stringInstantiatedValue)
        )
        .toList

}

trait ToCode[T]:
  def staticInstantiate(in: T): String

object ToCode {
  def apply[A](using a: ToCode[A]): ToCode[A] = a
  def instance[T](f: T => String): ToCode[T] = new ToCode[T]:
    def staticInstantiate(in: T): String = f(in)

  given ToCode[String] = in => s"\"$in\""
  given [A <: Singleton & String] => ToCode[A] = in => s"\"$in\""
  given ToCode[Int] = in => in.toString
  given ToCode[Boolean] = in => in.toString
  given ToCode[Double] = in => in.toString
  given ToCode[Float] = in => in.toString
  given [A: ToCode] => ToCode[Option[A]] = {
    case None     => "None"
    case Some(in) => s"Some(${ToCode[A].staticInstantiate(in)})"
  }
  given [A: ToCode] => ToCode[List[A]] = {
    case Nil => "Nil"
    case in  => s"List(${in.map(ToCode[A].staticInstantiate).mkString(", ")})"
  }
}
