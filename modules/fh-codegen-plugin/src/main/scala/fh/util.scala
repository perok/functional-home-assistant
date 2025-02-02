package fh

import smithy4s.Document

import java.nio.file.Path

object util {

  def getBitValues(in: Int): List[Int] = {
    import java.util.BitSet
    val b = BitSet.valueOf(Array(in.toLong))

    List.unfold(0)(index =>
      if b.nextSetBit(index) == -1 then None
      else Some((scala.math.pow(2, index).toInt, index + 1))
    )
  }

  def writeToFile(path: String, content: String): Path = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}

    val f = Paths.get(path)

    Files.createDirectories(f.getParent)

    Files.write(
      f,
      content.getBytes(StandardCharsets.UTF_8)
    )
  }

  object serializer {
    import java.io.*

    def write[A](path: String, in: A): Unit =
      val out = new ObjectOutputStream(new FileOutputStream(path))
      out.writeObject(in)
      out.close()

    def read[A](path: String): A =
      val in = new ObjectInputStream(new FileInputStream(path))
      val fooToRead = in.readObject()
      in.close()
      fooToRead.asInstanceOf[A]
  }

  import _root_.pprint.PPrinter

  /** Helper pprint for creating print of case classes that are easy to copy
    * into tests
    */
  val pprint: PPrinter = _root_.pprint
    .copy(additionalHandlers = {

      case a: (Document.DNumber | Document.DString | Document.DBoolean) =>
        _root_.pprint.Tree.Literal(
          s"${a.getClass().getSimpleName()}(${a.show})"
        )

      case a: Document.DArray =>
        _root_.pprint.Tree.Apply(
          "Document.DArray",
          Iterator(
            pprint.treeify(
              a.value,
              _root_.pprint.defaultEscapeUnicode,
              _root_.pprint.defaultShowFieldNames
            )
          )
        )

      case a: Document.DObject =>
        _root_.pprint.Tree.Apply(
          "Document.DObject",
          Iterator(
            pprint.treeify(
              a.value,
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
}
