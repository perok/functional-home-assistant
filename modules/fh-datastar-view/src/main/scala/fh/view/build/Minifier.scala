package fh.view.build

import cats.effect.std.Mutex
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fh.view.FHError
import fh.view.runtime.JsIsolate
import fh.view.telemetry.Logging
import io.circe.{Json, JsonObject}
import org.graalvm.polyglot.{Engine, Source}
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.immutable.ListMap
import scala.util.Using

/** A dashboard's inline CSS and JS, minified before it is served (#153).
  * Authors write them readable, comments and all; the page carries none of
  * that.
  */
trait Minifier {

  /** Minify every piece `dashboards` hold in one batch, so a site's later
    * per-dashboard [[dashboard]] calls find them all done.
    */
  def prepare(dashboards: List[Json]): IO[Unit]

  /** One dashboard's wire JSON with its CSS and JS minified. A piece that does
    * not parse fails the dashboard, naming where it sits.
    */
  def dashboard(json: Json): IO[Json]
}

object Minifier {

  /** Leaves everything as written. */
  val none: Minifier = new Minifier {
    def prepare(dashboards: List[Json]): IO[Unit] = IO.unit
    def dashboard(json: Json): IO[Json] = IO.pure(json)
  }

  /** csso and terser in a GraalJS isolate. An isolate costs hundreds of MB on a
    * Pi (ADR 0032), so one is started only for pieces `store` has not seen, and
    * closed when that batch is done. `store` is content-addressed and keyed on
    * the two libraries' bytes: an unchanged boot starts nothing, and a library
    * bump starts over. Pruned to [[KeptFiles]].
    */
  def isolated(
      store: os.Path,
      log: Logger[IO] = Logging.console.getLoggerFromName(LoggerName)
  ): IO[Minifier] =
    onEngine(
      store,
      JsIsolate.engineOrInHeap(e =>
        log.warn(s"no GraalJS isolate, minifying in-heap: ${e.getMessage}")
      ),
      log
    )

  private[build] def onEngine(
      store: os.Path,
      engine: Resource[IO, Engine],
      log: Logger[IO]
  ): IO[Minifier] =
    (Mutex[IO], Ref.of[IO, ListMap[Piece, String]](ListMap.empty)).mapN {
      (lock, failures) => Isolated(store, engine, lock, failures, log)
    }

  enum Lang(val ext: String) derives CanEqual:
    case Css extends Lang("css")
    case Js extends Lang("js")

  case class Piece(lang: Lang, source: String) derives CanEqual

  /** Where a dashboard's inline CSS and JS sit in its wire JSON, each with the
    * place an error names.
    */
  def piecesOf(dashboard: Json): List[(String, Piece)] = {
    val obj = dashboard.asObject.getOrElse(JsonObject.empty)
    val cards = obj("cards").flatMap(_.asObject).toList.flatMap(_.toList)
    val theme = obj("theme").flatMap(_.asObject).getOrElse(JsonObject.empty)
    text(obj, "css").map("the dashboard's css" -> Piece(Lang.Css, _)).toList ++
      cards.sortBy(_._1).flatMap { case (name, c) =>
        c.asObject.toList.flatMap(o =>
          text(o, "css")
            .map(s"card '$name' css" -> Piece(Lang.Css, _))
            .toList ++
            text(o, "script").map(s"card '$name' script" -> Piece(Lang.Js, _))
        )
      } ++
      text(theme, "styles").map("theme styles" -> Piece(Lang.Css, _)).toList ++
      theme("inlineScripts")
        .flatMap(_.asArray)
        .toList
        .flatten
        .zipWithIndex
        .flatMap { case (s, i) =>
          s.asString
            .filter(_.nonEmpty)
            .map(s"theme inlineScripts[$i]" -> Piece(Lang.Js, _))
        }
  }

  /** Every place [[piecesOf]] names, replaced by `f` of its piece. */
  def rewrite(dashboard: Json, f: Piece => String): Json = {
    def at(o: JsonObject, k: String, lang: Lang): JsonObject =
      text(o, k).fold(o)(s => o.add(k, Json.fromString(f(Piece(lang, s)))))
    def script(s: Json): Json =
      s.asString
        .filter(_.nonEmpty)
        .fold(s)(js => Json.fromString(f(Piece(Lang.Js, js))))
    dashboard.mapObject { obj =>
      val cards = obj("cards").map(
        _.mapObject(
          _.mapValues(
            _.mapObject(c => at(at(c, "css", Lang.Css), "script", Lang.Js))
          )
        )
      )
      val theme = obj("theme").map(_.mapObject { t =>
        val styled = at(t, "styles", Lang.Css)
        t("inlineScripts")
          .fold(styled)(ss =>
            styled.add("inlineScripts", ss.mapArray(_.map(script)))
          )
      })
      val base = at(obj, "css", Lang.Css)
      val withCards = cards.fold(base)(base.add("cards", _))
      theme.fold(withCards)(withCards.add("theme", _))
    }
  }

  private def text(o: JsonObject, k: String): Option[String] =
    o(k).flatMap(_.asString).filter(_.nonEmpty)

  private final class Isolated(
      store: os.Path,
      engine: Resource[IO, Engine],
      lock: Mutex[IO],
      // Kept in memory only: a failure reruns once the author has fixed it.
      failures: Ref[IO, ListMap[Piece, String]],
      log: Logger[IO]
  ) extends Minifier {

    def prepare(dashboards: List[Json]): IO[Unit] =
      results(dashboards.flatMap(piecesOf(_).map(_._2)).toSet).void

    def dashboard(json: Json): IO[Json] = {
      val pieces = piecesOf(json)
      results(pieces.map(_._2).toSet).flatMap { done =>
        val bad = pieces.flatMap { case (where, p) =>
          done(p).left.toOption.map(e => s"$where: $e")
        }
        if (bad.nonEmpty)
          FHError
            .badCondition(
              s"${bad.size} inline CSS/JS piece(s) could not be minified:\n" +
                bad.mkString("\n")
            )
            .raiseError[IO, Json]
        else IO.pure(rewrite(json, p => done(p).getOrElse(p.source)))
      }
    }

    private def results(
        pieces: Set[Piece]
    ): IO[Map[Piece, Either[String, String]]] =
      lock.lock.surround {
        for {
          failed <- failures.get
          known <- IO.blocking(pieces.toList.flatMap { p =>
            failed
              .get(p)
              .map(e => p -> Left(e))
              .orElse(stored(p).map(s => p -> Right(s)))
          }.toMap)
          missing = pieces.filterNot(known.contains).toList
          ran <-
            if (missing.isEmpty) IO.pure(Map.empty)
            else run(missing)
        } yield known ++ ran
      }

    private def run(
        missing: List[Piece]
    ): IO[Map[Piece, Either[String, String]]] =
      engine
        .flatMap(JsIsolate.context)
        .use { ctx =>
          IO.blocking {
            ctx.eval(terser)
            ctx.eval(csso)
            ctx.eval(entry)
            val fn = ctx.getBindings("js").getMember("fhMinify")
            missing.map { p =>
              val out = io.circe.parser
                .parse(fn.execute(p.lang.ext, p.source).asString())
                .toOption
                .flatMap(_.asObject)
              p -> out
                .flatMap(_("code").flatMap(_.asString))
                .toRight(
                  out
                    .flatMap(_("error").flatMap(_.asString))
                    .getOrElse("the minifier returned nothing")
                )
            }.toMap
          }
        }
        .timed
        .flatMap { (took, done) =>
          val bad = done.collect { case (p, Left(e)) => p -> e }
          IO.blocking(done.foreach {
            case (p, Right(s)) => keep(p, s)
            case _             => ()
          }) *>
            failures.update(m => (m ++ bad).takeRight(KeptFailures)) *>
            log
              .info(
                s"minified ${done.size} inline CSS/JS piece(s) in " +
                  s"${took.toMillis} ms, engine start included (${bad.size} failed)"
              )
              .as(done)
        }
        // No engine is no reason to refuse a dashboard: served as written, and
        // not stored, so the next start tries again.
        .handleErrorWith(e =>
          log
            .warn(e)(
              "no JavaScript engine to minify with; serving CSS/JS as written"
            )
            .as(missing.map(p => p -> Right(p.source)).toMap)
        )

    private def fileOf(p: Piece): os.Path =
      store / s"${LibPackage.sha256((libsHash + p.lang.ext + "\n" + p.source).getBytes(UTF_8))}.${p.lang.ext}"

    // A hit is touched, so pruning drops what no dashboard has asked for longest.
    private def stored(p: Piece): Option[String] = {
      val f = fileOf(p)
      Option.when(os.isFile(f)) {
        val _ = os.mtime.set(f, System.currentTimeMillis())
        os.read(f)
      }
    }

    private def keep(p: Piece, minified: String): Unit = {
      os.write.over(fileOf(p), minified, createFolders = true)
      val all = os.list(store)
      if (all.size > KeptFiles)
        all.sortBy(os.mtime(_)).take(all.size - KeptFiles).foreach(os.remove)
    }
  }

  private val KeptFiles = 512
  private val KeptFailures = 64
  private val LoggerName = "fh.view.build.Minifier"

  // csso's `restructure` merges rules across the sheet, which reorders the
  // cascade the three CSS layers rely on (ADR 0020). terser keeps top-level
  // names: a card's script is a classic script.
  private val entry: Source = js(
    "fh-minify.js",
    """globalThis.fhMinify = function (lang, source) {
      |  try {
      |    var code = lang === 'css'
      |      ? csso.minify(source, { restructure: false }).css
      |      : Terser.minify_sync(source, { toplevel: false, format: { comments: false } }).code;
      |    return JSON.stringify({ code: code });
      |  } catch (e) {
      |    var at = e.line ? ' (line ' + e.line + ', col ' + e.col + ')' : '';
      |    return JSON.stringify({ error: String(e.message || e) + at });
      |  }
      |};
      |""".stripMargin
  )

  private lazy val terserBytes = classpath("/minify/terser.min.js")
  private lazy val cssoBytes = classpath("/minify/csso.js")
  private lazy val terser: Source =
    js("terser.min.js", String(terserBytes, UTF_8))
  private lazy val csso: Source = js("csso.js", String(cssoBytes, UTF_8))
  private lazy val libsHash: String =
    LibPackage.sha256(terserBytes ++ cssoBytes) + "\n"

  private def classpath(path: String): Array[Byte] =
    Using.resource(
      Option(getClass.getResourceAsStream(path)).getOrElse(
        throw new IllegalStateException(
          s"$path is not on the classpath — the build stages it from node_modules"
        )
      )
    )(_.readAllBytes())

  // Named so a guest stack trace shows a file rather than `<eval>`.
  private def js(name: String, code: String): Source =
    Source.newBuilder("js", code, name).buildLiteral()
}
