package fh.domain.utils

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.all.*

import _root_.pprint.PPrinter

/** Helper pprint for creating print of case classes that are easy to copy into
  * tests
  */
val pprint: PPrinter = _root_.pprint
  .copy(additionalHandlers = {
    case a: NonEmptyList[Any] =>
      _root_.pprint.Tree.Apply(
        "NonEmptyList",
        a.toIterable.iterator.map(v =>
          pprint.treeify(
            v,
            _root_.pprint.defaultEscapeUnicode,
            _root_.pprint.defaultShowFieldNames
          )
        )
      )
    case a: io.circe.Json =>
      _root_.pprint.Tree.Apply(
        "Json",
        Iterator(
          pprint.treeify(
            a.spaces4,
            _root_.pprint.defaultEscapeUnicode,
            _root_.pprint.defaultShowFieldNames
          )
        )
      )

    case a: Some[Any] =>
      _root_.pprint.Tree.Apply(
        "Some",
        Iterator(
          pprint.treeify(
            a.value,
            _root_.pprint.defaultEscapeUnicode,
            _root_.pprint.defaultShowFieldNames
          )
        )
      )
    case a: org.http4s.Uri =>
      _root_.pprint.Tree.Literal(
        s"org.http4s.Uri.unsafeFromString(${a.renderString})"
      )
    case a: java.time.LocalDate =>
      _root_.pprint.Tree.Literal(
        s"java.time.LocalDate.of(${a.getYear}, ${a.getMonthValue}, ${a.getDayOfMonth})"
      )
    case a: java.time.Instant =>
      _root_.pprint.Tree.Literal(
        s"java.time.Instant.parse(\"${a.toString}\")"
      )
  })

given [A]: Show[A] =
  Show.show(something => pprint.apply(something, height = 30).toString)
