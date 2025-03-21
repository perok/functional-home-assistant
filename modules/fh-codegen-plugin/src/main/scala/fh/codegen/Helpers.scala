package fh.codegen

import fh.codegen.utils.Helpers
import cats.syntax.all.*

def consume[D](
    iterable: Iterable[ThingReference[D]],
    depth: Int = 0,
    createInsert: ThingReference[D] => String
): String = {
  val things = iterable.groupBy(_.pckage.get(depth))

  val currentImports = things
    .get(None)
    .filter(_.nonEmpty)
    .map { entities =>
      entities
        .map { entity =>
          createInsert(entity)
        }
        .mkString("\n")
    }
    .orEmpty

  val objectImports = things
    .map {
      case (Some(group), entities) =>
        val newImports = consume(entities, depth + 1, createInsert)

        s"""
             |object ${Helpers.objectNameSafe(group)} {
             | $newImports
             |}
             |
             |""".stripMargin

      case (_, _) => ""
    }
    .mkString("\n")

  s"""
       |$currentImports
       |
       |$objectImports
       |""".stripMargin
}
