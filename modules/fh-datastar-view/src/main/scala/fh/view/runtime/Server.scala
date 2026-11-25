package fh.view.runtime

import api.homeassistant.{HomeAssistantApi, ServiceTarget}
import scala.util.chaining.*
import cats.data.{EitherT, NonEmptyList, OptionT}
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.telemetry.{Diagnostics, Logging, Meters}
import fh.view.build.{
  AddonBootstrap,
  DashboardBuild,
  DumpRefresh,
  LibPackage,
  Minifier,
  Site,
  SystemPkl
}
import fh.view.FHError
import fh.view.auth.{AuthGate, Requirement}
import fh.view.history.{ChartRenderer, History, SeriesSource}
import fh.view.fetch.{FetchIdentity, FetchResolver, FetchSnapshot}
import fh.view.model.{
  Ready,
  CallDecl,
  CallField,
  CallId,
  ChromeColors,
  Dashboard,
  Ix,
  NodeId,
  Permission,
  SignalId,
  SlotRead,
  SlotValue,
  ThemeClasses,
  Step,
  Value,
  ValueType
}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.{Decoder, Json, JsonObject}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.{
  `Cache-Control`,
  `Content-Type`,
  `If-None-Match`,
  ETag
}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.immutable.BitSet

import scala.concurrent.duration.*

/** HTTP surface for the dashboards. Nothing is pushed: a state change is
  * recorded once per slug ([[sharedPatchPublishers]]) and each connection pulls
  * what it is owed ([[pull]]), rendered against its own holds and selections
  * (ADR 0012). Going to another dashboard is a document load of `/d/:slug`, not
  * a route here (ADR 0002).
  */
class Server(
    // Writing is all the server does to HA directly; it reads through the
    // store.
    actions: ServiceCalls,
    stateStore: StateStore,
    site: Server.LiveSite,
    sessions: Sessions,
    gate: AuthGate,
    supervisor: Supervisor[IO],
    assets: AssetCache = AssetCache.empty,
    // The heartbeat beats only while this is true, so the disconnect banner
    // also shows an upstream freeze, not only a browser-side drop.
    healthy: Signal[IO, Boolean] = Signal.constant(true),
    systemPkl: SystemPkl = SystemPkl.empty,
    dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
    windows: Server.SessionWindows = Server.SessionWindows.default,
    tracer: Tracer[IO] = Tracer.noop,
    loggerFactory: LoggerFactory[IO] = Logging.console,
    meters: Meters = Meters.noop,
    fetches: Option[FetchResolver] = None,
    minifier: Minifier = Minifier.none
) {

  // `logger`, not `log`: `renderPage` takes a `log: FragmentLog`.
  private val logger = loggerFactory.getLoggerFromClass(classOf[Server])

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Resolved per request: the entrypoint can rename or drop the default.
    case req @ GET -> Root =>
      gate.handleRequirement(
        req,
        Requirement.FromDashboard(None),
        AuthGate.orLogIn(req)
      )(
        site.defaultSlug.flatMap(pageResponse(_, req))
      )
    case req @ GET -> Root / "d" / slug =>
      gate.handleRequirement(
        req,
        Requirement.FromDashboard(Some(slug)),
        AuthGate.orLogIn(req)
      )(
        pageResponse(slug, req)
      )

    // Ungated, like everything the shell needs: it paints before anyone can
    // be logged in.
    case GET -> Root / "assets" / name =>
      assets.serve(name)

    case GET -> Root / "manifest.webmanifest" =>
      chromeColors.flatMap(PwaAssets.manifest)
    case GET -> Root / "sw.js" =>
      PwaAssets.serve("sw.js")
    case GET -> Root / "icon-192.png" =>
      PwaAssets.serve("icon-192.png")
    case GET -> Root / "icon-512.png" =>
      PwaAssets.serve("icon-512.png")

    // Only content-hashed names the manifest lists, so no path sanitising is
    // needed and `immutable` is safe.
    case req @ GET -> Root / "web" / file if FrontendAssets.serves(file) =>
      StaticFile
        .fromResource(s"/web/$file", Some(req))
        .map(
          _.putHeaders(
            Header.Raw(
              CIString("Cache-Control"),
              "public, max-age=31536000, immutable"
            )
          )
        )
        .getOrElseF(NotFound())

    // `/system/pkl/*` serves pkl-lsp, the editor and `fh` on a laptop; the
    // server's own eval resolves offline from the seeded cache. UNGATED — a
    // known hole (issue #166): it is not confirmed pkl-lsp can send a header.

    // Before `:name`, which would match it as `name = "packages"`.
    case GET -> Root / "system" / "pkl" / "packages" =>
      answerErrors(
        systemPkl.packagesIndex.flatMap(json =>
          Ok(json).map(
            _.putHeaders(`Content-Type`(MediaType.application.json))
          )
        )
      )

    // The machine-agnostic scaffold `fh init` writes verbatim (ADR 0010);
    // `.fh/machine.json` is per machine and not served. Before `:name`.
    case GET -> Root / "system" / "pkl" / "base.pkl" =>
      Ok(AddonBootstrap.BaseManifest)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
    case GET -> Root / "system" / "pkl" / "PklProject" =>
      Ok(AddonBootstrap.ConsumerManifest)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
    case GET -> Root / "system" / "pkl" / "gitignore" =>
      Ok(AddonBootstrap.GitignoreTemplate)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))

    case req @ GET -> Root / "system" / "pkl" / name =>
      answerErrors(
        systemPkl.module(name).flatMap(systemPklResponse(_, req))
      )

    // pkl's remote-package protocol, so a laptop resolves the instance's own
    // sha256-pinned packages with one `http.rewrites` line (ADR 0010). No cache
    // headers: a proxy-cached zip turns a dev image's changed lib bytes under
    // an unchanged version into a stale-checksum failure.
    case GET -> Root / "system" / "pkl" / "packages" / file =>
      answerErrors(systemPkl.packageArtifact(file).flatMap { bytes =>
        val mediaType =
          if (file.endsWith(".zip")) MediaType.application.zip
          else MediaType.application.json
        Ok(bytes).map(_.putHeaders(`Content-Type`(mediaType)))
      })

    case req @ GET -> Root / "edit" / "node" / slug / id / "debug" =>
      gate.handleRequirement(req, Requirement.Admin)(nodeDebug(slug, id))

    // The body is the wire JSON the Pkl layer emits (ADR 0010, persona 4).
    case req @ POST -> Root / "system" / "push" / slug =>
      gate.handleRequirement(req, Requirement.Admin)(pushResponse(slug, req))

    case req @ GET -> Root / "system" / "diagnostics" =>
      gate.handleRequirement(req, Requirement.Admin)(
        Diagnostics.report().flatMap(Ok(_))
      )

    // Separate from the report: a thread dump pauses every thread.
    case req @ GET -> Root / "system" / "diagnostics" / "threads" =>
      gate.handleRequirement(req, Requirement.Admin)(
        Diagnostics.threadDump.flatMap(plainText)
      )

    case req @ GET -> Root / "system" / "diagnostics" / "fibers" =>
      gate.handleRequirement(req, Requirement.Admin)(
        Diagnostics.fiberDump.flatMap(plainText)
      )

    case req @ POST -> Root / "system" / "dump" / "refresh" =>
      gate.handleRequirement(req, Requirement.Admin)(dumpRefresh match {
        case None         => NotFound()
        case Some(action) =>
          action.flatMap(result => Ok(Server.dumpRefreshJson(result)))
      })

    // The unknown-slug 404 is decided inside the stream ([[sseStream]]).
    case req @ GET -> Root / "sse" / "dashboard" / slug / "patch" =>
      gate.handleStream(req, Some(slug))(sseStream(slug, req, _))

    case req @ GET -> Root / "sse" / "dashboard" / slug / "recover" =>
      gate.handleStream(req, Some(slug))(recoverStream(slug, _))

    // `domain` is the service's, not always the target's
    // (`homeassistant.toggle` on a `light`). `kind` is `entity`, `area` or
    // `floor`; HA expands the last two (issue #389).
    case req @ POST -> Root / "sse" / "call" / slug / id =>
      req.bodyText.compile.string.flatMap(body =>
        Server.callBody(body) match {
          case Right(b)  => tapCall(req, slug, CallId.parse(id), b)
          case Left(why) => actionRefused(req, why)
        }
      )

    // The query string is the page's, which Back or Forward just moved.
    case req @ POST -> Root / "sse" / "history" / slug =>
      withSession(req, slug)((session, renderer) =>
        fromHistory(session, renderer, Server.varChoicesOf(req))
      )
  }

  /** Refused unless this dashboard declares the call and the pressed node
    * places it; then the server runs its own evaluation of it now, the posted
    * values being the one client input (ADR 0023). A missing or failed
    * dashboard permits nothing.
    */
  private def tapCall(
      req: Request[IO],
      slug: String,
      id: CallId,
      body: Server.CallBody
  ): IO[Response[IO]] =
    gate.handleRequirement(req, Requirement.FromDashboard(Some(slug)))(
      (site.permissionFor(Some(slug)), gate.of(req)).flatMapN {
        (permission, user) =>
          permission.callOf(user, id) match
            case None =>
              actionRefused(req, s"no tap on this dashboard makes the call $id")
            case Some(call: Ready.ServiceCall) =>
              (for
                posted <- EitherT.fromEither[IO](
                  Server.postedValues(call.decl, body.posted)
                )
                p <- EitherT(pressed(req, slug, id, call, posted))
                _ <- EitherT.fromEither[IO](
                  Server.seenRefusal(p.seen, body.seen).toLeft(())
                )
              yield p).foldF(actionRefused(req, _), send(req, _))
            case Some(Ready.SetVarCall(d)) => choose(req, slug, id, d, body)
      }
    )

  /** This viewer's choice of a node variable, declared at the nearest node
    * above the pressed one that declares it (issue #209). A refusal clears the
    * pending ask the press wrote (ADR 0025).
    */
  private def choose(
      req: Request[IO],
      slug: String,
      id: CallId,
      d: CallDecl.SetVar,
      body: Server.CallBody
  ): IO[Response[IO]] =
    (for
      (renderer, node) <- EitherT(pressAt(req, slug))
        .leftMap(_ -> Option.empty[String])
      key <- EitherT.fromOption[IO](
        renderer.vars.keyAt(node, d.variable),
        s"no node at or above $node declares the variable '${d.variable}'" ->
          Option.empty[String]
      )
      pending = Option(key.pendingSignal)
      _ <- EitherT(placedAt(renderer, node, id)((_, _, _) => Right(())))
        .leftMap(_ -> pending)
      conn <- EitherT.fromOption[IO](body.conn, "missing conn" -> pending)
    yield (renderer, key, conn)).foldF(
      (why, pending) => actionRefused(req, why, pending),
      (renderer, key, conn) =>
        inSession(
          req,
          slug,
          IO.pure(renderer),
          conn,
          Some(key.pendingSignal)
        )((session, renderer) => setVar(session, renderer, key, d.value))
    )

  private def pressed(
      req: Request[IO],
      slug: String,
      id: CallId,
      call: Ready.ServiceCall,
      posted: Map[String, Any]
  ): IO[Either[String, Renderer.Pressed]] =
    EitherT(pressAt(req, slug)).flatMapF { (renderer, node) =>
      placedAt(renderer, node, id)((c, placed, at) =>
        renderer.press(call, placed.seen, c, at, posted)
      )
    }.value

  /** The pressed node, the client's claim, and the dashboard's renderer. */
  private def pressAt(
      req: Request[IO],
      slug: String
  ): IO[Either[String, (Renderer, NodeId)]] =
    req.uri.query.params.get(Server.NodeParam).map(NodeId.derived) match
      case None       => IO.pure(Left("the press names no node"))
      case Some(node) =>
        rendererFor(slug).flatMap {
          case None =>
            IO.raiseError(FHError.unavailable(s"dashboard '$slug' is not live"))
          case Some(renderer) => IO.pure(Right(renderer -> node))
        }

  /** `f` over the call `id` as `node` places it now: a set member is its
    * current clause, so a page drawn from another one is refused here when that
    * clause's call differs, and by `seen` when both declare the same call.
    */
  private def placedAt[A](renderer: Renderer, node: NodeId, id: CallId)(
      f: (Ready.Component, Ready.Placed, Shared) => Either[String, A]
  ): IO[Either[String, A]] =
    stateStore.current
      .flatMap(decided(renderer, _) { at =>
        renderer
          .componentAt(node, at)
          .flatMap(c => c.calls.values.find(_.id == id).map(c -> _))
          .toRight(s"the node $node does not place the call $id")
          .flatMap((c, placed) => f(c, placed, at))
      })
      .map(_._1)

  private def send(req: Request[IO], p: Renderer.Pressed): IO[Response[IO]] =
    p.service.split('/') match {
      case Array(domain, service) =>
        ServiceTarget.of(p.target, p.exclude) match {
          case Some(target) =>
            callService(
              domain,
              service,
              target,
              Json.fromFields(p.data.toList.map((k, v) => k -> Server.json(v))),
              req
            )
          // Nothing to act on is already the state asked for.
          case None => NoContent()
        }
      case _ =>
        actionRefused(req, s"the service '${p.service}' is not domain/service")
    }

  /** `store`'s version with what `walk` reaches decided: its result, and the
    * version the rest of the send reads, which raises on a fetch-reading
    * decision the walk did not reach.
    */
  private def decided[A](renderer: Renderer, store: StoreState)(
      walk: Shared => A
  ): IO[(A, Shared)] = decidedAt(renderer, store, identity)(walk)

  /** [[decided]] for one viewer: what reads a variable is decided at `env`. */
  private def decidedFor[A](renderer: Renderer, store: StoreState, env: VarEnv)(
      walk: Shared => A
  ): IO[(A, Shared)] = decidedAt(renderer, store, _.viewing(env))(walk)

  private def decidedAt[A](
      renderer: Renderer,
      store: StoreState,
      as: Shared => Shared
  )(walk: Shared => A): IO[(A, Shared)] = {
    val shared = renderer.shared(store)
    renderer.decide(decisionAnswers) { run =>
      val at = as(shared.asking(run))
      walk(at) -> at
    }
  }

  // A decision reads no viewer's variable (refused at build).
  private def decisionAnswers(reads: Set[SlotRead]): IO[FetchSnapshot] =
    answer(reads.toList, Map.empty)

  /** Handled here rather than by [[FHError.handle]], so a test driving the
    * routes without it sees the same response.
    */
  private def answerErrors(io: IO[Response[IO]]): IO[Response[IO]] =
    io.handleErrorWith {
      case e: FHError => FHError.logged(e)
      case err        => InternalServerError(err.getMessage)
    }

  // Declared: as octet-stream a browser downloads the dump instead of showing
  // it.
  private def plainText(body: String): IO[Response[IO]] =
    Ok(body).map(_.withContentType(`Content-Type`(MediaType.text.plain)))

  private def rendererFor(slug: String): IO[Option[Renderer]] =
    liveFor(slug).flatMap(
      _.traverse(_.renderer.get).map(_.flatMap(_.rendererOf))
    )

  /** Test seam: assert on what a path wrote to the log, not only the wire. */
  private[runtime] def liveSlug(slug: String): IO[Server.LiveSlug] =
    liveFor(slug).map(_.getOrElse(sys.error(s"no live slug '$slug'")))

  /** Test seam: reap every connection, so a smoke test reaches a real reconnect
    * without waiting out [[Server.LingerWindow]]. Answers how many.
    */
  private[runtime] def forgetSessions: IO[Int] =
    sessions.all.flatMap { live =>
      live.toList
        .traverse { case (conn, session) =>
          session.tenure.set(Tenure.Reaped) *>
            sessions.deregisterIf(conn, session)
        }
        .as(live.size)
    }

  private def liveFor(slug: String): IO[Option[Server.LiveSlug]] =
    site.liveFor(slug)

  /** The per-slug recorder: writes each frame to the slug's log and rings its
    * doorbell. It renders nothing; each session renders its own bytes from
    * [[Patches.resume]] (ADR 0012).
    *
    * An arm writes and rings only while the log is its renderer's: a swap
    * installs the next log before `switchMap` stops the old arm, whose frames
    * then go nowhere. The new arm's seed covers them: a session behind it
    * repaints ([[owed]]).
    */
  private def publisherFor(
      slug: String,
      live: Server.LiveSlug
  ): Stream[IO, Nothing] =
    live.renderer.discrete.switchMap {
      // No renderer to diff against, and a log none numbers.
      case Server.RendererState.Failed(_)       => Stream.empty
      case Server.RendererState.Ready(renderer) =>
        // The fold restarts per arm, as the log does: a `Shared` is this
        // renderer's decisions.
        stateStore.frames
          .evalMapAccumulate(Option.empty[Shared]) {
            case (None, seed) =>
              val at = seed.state.version
              (IO(renderer.shared(seed.state)), live.seed(renderer, at))
                .mapN((shared, seeded) =>
                  Some(shared) -> Option.when(seeded)(at)
                )
            case (Some(prev), frame) =>
              recordFrame(slug, renderer, live.log, prev, frame)
                .map((next, written) =>
                  Some(next) -> Option.when(written)(frame.state.version)
                )
          }
          // Ring AFTER the write, or a woken session reads a log that does
          // not yet hold the version it was told about, and skips past it.
          .evalMap { case (_, version) =>
            version.traverse_(v => live.doorbell.update(_ max v))
          }
    }.drain

  /** The one place a recorder starts or stops: one per registered slug,
    * reconciled against the live registry.
    */
  def sharedPatchPublishers: Stream[IO, Nothing] =
    Stream
      .eval(Ref[IO].of(Map.empty[String, IO[Unit]]))
      .flatMap { active =>
        site.changes.evalMap { registered =>
          active.get.flatMap { current =>
            val toAdd =
              registered.toList.filterNot { case (slug, _) =>
                current.contains(slug)
              }
            val toCancel = current.keySet -- registered.keySet
            for {
              added <- toAdd.traverse { case (slug, live) =>
                supervisor
                  .supervise(publisherFor(slug, live).compile.drain)
                  .map(slug -> _.cancel)
              }
              _ <- toCancel.toList.traverse_(current.getOrElse(_, IO.unit))
              _ <- active.set((current ++ added) -- toCancel)
            } yield ()
          }
        }
      }
      .drain

  /** Test seam: await a session before moving an entity, so the frame is
    * recorded with that client's screen in view.
    */
  private[runtime] def connectedSessions: Stream[IO, Int] = sessions.liveStreams

  /** Install a pre-evaluated dashboard live (ADR 0010, persona 4). An existing
    * slug's ref is reused, so open connections repaint as on reload. Writes no
    * file: a restart returns to the on-disk dashboards.
    *
    * A pushed slug is never reclaimed while the process lives — nothing can
    * tell when a developer is done with one — and each costs a renderer, a log
    * and a recorder pass per frame (TODO2.md).
    */
  def push(validated: Dashboard.Validated): IO[Unit] =
    site.installPushed(
      validated.dashboard.slug,
      Server.RendererState.Ready(Renderer(validated))
    )

  /** The shell around [[Patches.plan]] + [[Patches.record]]: one plan per
    * frame, its visibility the union of each watched viewer's screen
    * ([[Sessions.watching]]), one viewer at a time, and the screens kept apart
    * per distinct values for the picks a viewer's values decide. Where a set's
    * decision reads a variable, one plan per distinct values, each as that
    * viewer, logged as the union.
    *
    * A slug nobody watches records nothing ([[FragmentLog.skipped]]). Safe only
    * because [[pageResponse]] registers the session BEFORE reading the snapshot
    * its page renders from: a skipped frame precedes that read, so the document
    * already holds it. Register later and the gap is silent staleness.
    */
  private[runtime] def recordFrame(
      slug: String,
      renderer: Renderer,
      log: Ref[IO, FragmentLog],
      prev: Shared,
      frame: Frame
  ): IO[(Shared, Boolean)] =
    sessions.watching(slug).flatMap { watched =>
      val viewers = watched.distinct
      val version = frame.state.version
      def written(f: FragmentLog => FragmentLog): IO[Boolean] =
        log.modify(l =>
          if (l.numberedBy(renderer.generation)) (f(l), true) else (l, false)
        )
      // Before the gate: the carry tracks the stream, not who is watching,
      // and is the next frame's "before".
      IO(renderer.advance(prev, frame, viewers.map(_.values).toSet)).flatMap {
        next =>
          val recorded =
            if (viewers.isEmpty) written(_.skipped(version))
            else
              // One run for "before" and "after": a decision shown in either is
              // made there, dirty or never made; one nobody shows is not reached.
              renderer
                .decide(decisionAnswers) { run =>
                  val (was, now) = (prev.asking(run), next.asking(run))
                  // Only cases some client can see: a tab inside a hidden
                  // `If` is selected but on no screen. Filtered per session
                  // before the union, because a chain is one client's.
                  val screens = viewers.map { w =>
                    val env = renderer.vars.env(w.values)
                    (
                      w.values,
                      env,
                      renderer.picks.onScreen(w.open, now.viewing(env))
                    )
                  }
                  val visible = screens.foldLeft(BitSet.empty)(_ | _._3)
                  // A viewer pick is decided per distinct values, each by its
                  // own sessions' screens; nothing to split without one.
                  def seen =
                    screens.groupBy(_._1).values.toList.map { same =>
                      PickGraph.Seen(
                        same.head._2,
                        same.foldLeft(BitSet.empty)(_ | _._3)
                      )
                    }
                  // A set read per viewer decides what is on the page below
                  // it, so each values is planned as its own viewer.
                  if (renderer.members.readsViewer)
                    seen.map(s =>
                      Patches.plan(
                        renderer,
                        was.viewing(s.env),
                        now.viewing(s.env),
                        frame.changes,
                        s.visible,
                        List(s)
                      )
                    )
                  else
                    List(
                      Patches.plan(
                        renderer,
                        was,
                        now,
                        frame.changes,
                        visible,
                        if (renderer.picks.viewerPicks.isEmpty) Nil else seen
                      )
                    )
                }
                .flatMap(reqs =>
                  written(l =>
                    reqs.foldLeft(l)((acc, req) =>
                      Patches.record(renderer, acc, req)
                    )
                  )
                )
          recorded.map(next -> _)
      }
    }

  /** Drop a session a later document in the same tab superseded, unless a
    * stream still holds it. Deregistered by reference, so a `conn` a later
    * document reuses is never unrouted.
    */
  private def retire(conn: String): IO[Unit] =
    sessions.get(conn).flatMap {
      case None    => IO.unit
      case Some(s) =>
        s.supersede.flatMap(IO.whenA(_)(sessions.deregisterIf(conn, s)))
    }

  /** The per-connection stream: [[opening]], then what this session pulls off
    * the doorbell ([[pull]]), reload repaints, HA health and a heartbeat. All
    * but health are session steps, delivered through one [[Outlet]] in the
    * order they ran: frames returned to separate branches of a merge reach the
    * client in whichever order the merge takes them.
    */
  private def sseStream(
      slug: String,
      req: Request[IO],
      allowed: Stream[IO, Boolean]
  ): IO[Response[IO]] =
    for {
      // `None`: a bookmarked SSE URL; every document names its own.
      named = Server.connOf(req)
      conn <- named.fold(IO.randomUUID.map(_.toString))(IO.pure)
      // A reload mints a fresh `conn`, so without this the replaced session
      // lingers a whole window, its values widening what each frame
      // records.
      _ <- Server.prevConnOf(req).filterNot(_ == conn).traverse_(retire)
      adopted <- adoptOrMint(slug, conn)
      (session, epoch) = adopted
      liveOpt <- liveFor(slug)
      rendererOpt <- liveOpt
        .traverse(_.renderer.get)
        .map(_.flatMap(_.rendererOf))
      _ <- Server
        .cursorAnomaly(req)
        .traverse_(w => logger.warn(w))
      outlet <- Outlet.create
      healthPatch = (h: Boolean) =>
        Datastar.patchSignals(s"""{"${Server.HaDownSignal}":${!h}}""")

      // Only when it differs from what the document rendered: health can move
      // between that render and this connect, and the next transition may be
      // hours away.
      haDown = healthy.discrete.changes.evalMapFilter { h =>
        val down = !h
        session.haDown.modify {
          case Some(`down`) => (Some(down), None)
          case _            => (Some(down), Some(healthPatch(h)))
        }
      }
      // Also where the cursor catches up, since a pull that owed nothing sends
      // nothing. A quiet house costs only the comment.
      keepAlive = Stream
        .awakeEvery[IO](Server.KeepAliveInterval)
        .evalMap(_ =>
          session.run { s =>
            IO.pure(
              if (s.position == s.told)
                Session.Step(s, List(Server.keepAliveComment), ())
              else
                Session.Step(
                  s.copy(told = s.position),
                  List(Server.versionSignal(s.position)),
                  ()
                )
            )
          }
        )
      // `drained` keeps the coalescing: versions landing while a slow client
      // reads collapse into one pull.
      live = Stream
        .emits(liveOpt.toList)
        .flatMap { l =>
          Stream.exec(
            session
              .attach(outlet) *>
              session.run(opening(slug, l, session, req, rendererOpt))
          ) ++
            outlet.frames
              .concurrently(
                l.doorbell.discrete
                  .evalMap(pull(l, session, _) *> outlet.drained)
                  .merge(keepAlive)
              )
              .merge(haDown)
        }
        // Reaped under it (a shutdown, a test's reap): the tenure ends the
        // stream as well, whichever is seen first.
        .handleErrorWith(e =>
          if (Session.isEnded(e)) Stream.empty else Stream.raiseError[IO](e)
        )

      // Bracketed to the stream, not done in the handler: a handler that
      // registers and never reaches a body (it raised, or ember dropped the
      // response) would leak the session into every frame's `watching`.
      //
      // Release hands the session to the linger ([[Session.release]]), and only
      // while this stream still owns it: a displaced stream releases after its
      // successor took over.
      stream = Stream.bracket(sessions.register(conn, session))(_ =>
        session.detach(outlet) *>
          session
            .release(epoch)
            .flatMap(_.traverse_(reapAfter(conn, session, _, windows.linger)))
        // Only when this stream minted it; a document already knows its own.
      ) >> (Stream.emits(
        Option
          .when(named.isEmpty)(
            Datastar.patchSignals(s"""{"${Server.ConnSignal}":"$conn"}""")
          )
          .toList
      ) ++ live)
      // The 404 is decided on this stream's own lookup: a second, route-side
      // read could disagree (slug removed in between) and answer a 200 empty
      // SSE. Nothing is registered yet — that is bracketed to `stream`.
      resp <- liveOpt match
        case None    => NotFound()
        case Some(_) =>
          Ok(
            Server
              .untilRevoked(allowed)(stream)
              // A second stream for one session displaces the first: two
              // sharing one `holds` would each record bytes the other sent,
              // and suppress changes the client never received.
              //
              // OUTSIDE `untilRevoked`: fs2 interruption is scoped, so
              // interrupting a branch of that merge ends the branch without the
              // merge completing (the revocation branch is `Stream.never`), and
              // the body never ends.
              .interruptWhen(
                session.tenure.discrete.map(_ != Tenure.Held(epoch))
              )
          )
    } yield resp

  /** The error page's stream ([[errorPage]]): renderer transitions as reloads,
    * and nothing else — no session, cursor or holds. The one slug lookup is
    * here for the same reason as in [[sseStream]].
    */
  private def recoverStream(
      slug: String,
      allowed: Stream[IO, Boolean]
  ): IO[Response[IO]] =
    liveFor(slug).flatMap {
      case None       => NotFound()
      case Some(live) =>
        Ok(
          Server.untilRevoked(allowed)(
            recoverTransitions(live).merge(keepAliveComments)
          )
        )
    }

  /** Reload on every change except an unchanged `Failed`, which the page
    * already shows (a reload would loop). The first element is the connection
    * marker: a comment under `Failed`, or a reload if the fix landed between
    * the page's render and this connect.
    */
  private def recoverTransitions(
      live: Server.LiveSlug
  ): Stream[IO, SseFrame] =
    live.renderer.discrete.zipWithPrevious.map {
      case (None, st) =>
        Some(
          if (st.rendererOf.isDefined) Server.reloadPatch
          else Server.recoverOpenMarker
        )
      case (prev, st) =>
        Option.unless(unchangedFailed(prev, st))(Server.reloadPatch)
    }.unNone

  private def unchangedFailed(
      prev: Option[Server.RendererState],
      current: Server.RendererState
  ): Boolean =
    (prev, current) match
      case (
            Some(Server.RendererState.Failed(m1)),
            Server.RendererState.Failed(m2)
          ) =>
        m1 == m2
      case _ => false

  private val keepAliveComments: Stream[IO, SseFrame] =
    Stream.awakeEvery[IO](Server.KeepAliveInterval).as(Server.keepAliveComment)

  /** What this client is owed from `position + 1`.
    *
    * `+ 1` exactly, where a client's cursor gets `>=` ([[resumeFrom]]): a
    * client can hold part of version V, but a position is what this server last
    * sent, so V is complete.
    *
    * The position advances to the doorbell's version, not the store's: the
    * snapshot can be ahead of the log, and claiming it would skip what the next
    * frame records.
    *
    * Answers the frames it queued, which only a hand-pulled test reads.
    */
  private[runtime] def pull(
      live: Server.LiveSlug,
      session: Session,
      version: Long
  ): IO[List[SseFrame]] =
    session.run { was =>
      live.renderer.get.flatMap(installed =>
        // First, and before the version: `install` rings at the same one.
        swapAnswer(live, session, installed, was) match
          case Some(swap) =>
            swap.map(st => Session.Step(st.next, st.frames, st.frames))
          case None => owed(live, session, version, installed, was)
      )
    }

  /** A log another renderer numbers, or one that does not reach the position (a
    * recorder's seed above it, a stretch nobody watched), cannot say what this
    * session missed: it repaints. The log can be newer than `installed` (a
    * second swap in flight); the ring that swap sends repaints again.
    */
  private def owed(
      live: Server.LiveSlug,
      session: Session,
      version: Long,
      installed: Server.RendererState,
      was: SessionState
  ): IO[Session.Step[List[SseFrame]]] =
    if (version <= was.position) IO.pure(Session.Step(was, Nil, Nil))
    else
      (for
        renderer <- OptionT.fromOption[IO](installed.rendererOf)
        s <- OptionT.liftF(checked(renderer, was))
        log <- OptionT.liftF(live.log.get)
        store <- OptionT.liftF(stateStore.current)
      yield (renderer, s, log, store))
        .semiflatMap { (renderer, s, log, store) =>
          if (log.numberedBy(renderer.generation) && log.reaches(s.position))
            resumed(live, renderer, s, log, store, version)
          else
            repaint(live, session, renderer.generation, renderer)(was)
              .map(st => Session.Step(st.next, st.frames, st.frames))
        }
        .getOrElse(Session.Step(was, Nil, Nil))

  private def resumed(
      live: Server.LiveSlug,
      renderer: Renderer,
      s: SessionState,
      log: FragmentLog,
      store: StoreState,
      version: Long
  ): IO[Session.Step[List[SseFrame]]] = {
    val env = renderer.vars.env(s.values)
    Patches
      .resume(
        renderer,
        live.cache,
        log,
        s.holds,
        renderer.shared(store),
        decisionAnswers,
        answer(_, env),
        env,
        s.position + 1,
        s.open,
        // Only reached when the log reaches `position`, and this session
        // has been watched since its page.
        Patches.Coverage.Logged
      )
      .map { patches =>
        // The cursor rides LAST, which makes it an ack: a client echoing it
        // applied what came before. A silent frame announces nothing, so
        // `told` does not move for it; the keepalive carries the cursor
        // instead ([[SessionState]]).
        val frames =
          if (patches.isEmpty) Nil
          else
            // Through `encode`, so it merges with a trailing signal frame and
            // stays a separate event after an element one.
            Patches.encode(patches :+ Addressed(Server.versionPatch(version)))
        Session.Step(
          s.copy(
            holds =
              patches.foldLeft(s.holds)(Patches.applied(renderer.index, _, _)),
            position = version,
            told = if (patches.isEmpty) s.told else version
          ),
          frames,
          frames
        )
      }
  }

  /** What a (re)connecting client is sent first (ADR 0011): '''reload''' when
    * the head's unpatchable part moved ([[Renderer.headHash]]), '''resume'''
    * when the cursor provably names what this DOM holds, else '''repaint''' —
    * always correct, where a wrong resume is silently stale forever. A stale
    * theme or title is patched in front of either ([[headPatches]]).
    *
    * '''The doorbell is read BEFORE the log.''' The store runs ahead of the
    * log, so a resume claiming `store.version` would claim a change nobody
    * sent, and the pull that would carry it is skipped (`version <= position`).
    * A repaint claims the store: it rendered all of it.
    */
  private def openingPatches(
      slug: String,
      live: Server.LiveSlug,
      req: Request[IO],
      s: SessionState
  ): IO[Session.Step[Unit]] =
    (
      live.doorbell.get,
      live.renderer.get,
      live.log.get,
      stateStore.current
    ).flatMapN { (covered, installed, log, store) =>
      val cursor = Server.cursorOf(req)
      installed.rendererOf match
        // A failed slug: the error page is a reload away.
        case None => IO.pure(Server.reloaded(s, installed))
        case Some(renderer) if cursor.exists(_.headHash != renderer.headHash) =>
          IO.pure(Server.reloaded(s, renderer.generation))
        case Some(renderer) =>
          val env = renderer.vars.env(s.values)
          val shared = renderer.shared(store)
          val head =
            if (cursor.exists(_.styleHash != renderer.styleHash))
              Server.headPatches(renderer, slug)
            else Nil
          // `Patches.resume` is total, so only global reasons repaint: no
          // cursor, another log's (restart or swap), one ahead of the store
          // (rewound counter), one from before a gap nobody watched
          // ([[FragmentLog.reaches]]) — or one behind `told`. `holds` records
          // what was SENT; a cursor rides last in its batch, so an echo behind
          // `told` means claimed bytes were lost.
          // And never across a swap: `holds` is one renderer's, and the log
          // read here may be the next renderer's already.
          val heldHere = s.painter match
            case Painter.By(p) => p eq renderer.generation
            case _             => true
          val resumedIO = cursor
            .filter(c =>
              heldHere && log.numberedBy(renderer.generation) &&
                c.logId == log.id && c.version <= store.version &&
                log.reaches(c.version) && c.version >= s.told
            )
            .traverse(c =>
              Patches.resume(
                renderer,
                live.cache,
                log,
                s.holds,
                shared,
                decisionAnswers,
                answer(_, env),
                env,
                Server.resumeFrom(req, c),
                s.open
              )
            )
          // Traced, so the repaint claims what it painted; clearing `holds`
          // instead would re-send the whole page on the next pull.
          val painted =
            pageAnswers(renderer, s.open, store, env).map((fragments, at) =>
              Patches.repaint(renderer, at, Viewing(fragments))
            )
          resumedIO.flatMap { resumed =>
            val claim = resumed.fold(store.version)(_ => covered)
            resumed
              .fold(painted)(patches =>
                IO.pure(
                  patches.map(_.patch.toSse) ->
                    patches
                      .foldLeft(s.holds)(
                        Patches.applied(renderer.index, _, _)
                      )
                )
              )
              .map { (sent, held) =>
                Session.Step(
                  s.copy(
                    holds = held,
                    position = claim,
                    told = claim,
                    painter = Painter.By(renderer.generation)
                  ),
                  head ++ sent :+
                    // With the variables: a stream that died between a press's
                    // patch and its commit left them disagreeing.
                    Server
                      .openingSignals(
                        renderer,
                        s.values,
                        log.idUnder(renderer.generation),
                        claim
                      ),
                  ()
                )
              }
          }
    }

  /** The first step of a stream. A forgotten session's carried variables are
    * adopted first, so it keeps its tabs and its open popup, and a reconnect
    * does not orphan the dialog on screen.
    */
  private def opening(
      slug: String,
      live: Server.LiveSlug,
      session: Session,
      req: Request[IO],
      served: Option[Renderer]
  )(s: SessionState): IO[Session.Step[Unit]] =
    served
      .fold(IO.pure(s)) { r =>
        checked(r, s).flatMap(adoptCarriedVars(_, r, req)).flatMap { adopted =>
          val env = r.vars.env(adopted.values)
          warnAnomalies(r, env) *> session
            .reveal(Set.empty, adopted.values)
            .as(adopted.copy(open = Set.empty))
        }
      }
      .flatMap(openingPatches(slug, live, req, _))

  /** A step under a renderer other than the one `s.holds` was painted under
    * answers as a swap: a reload when the head's unpatchable part moved
    * ([[Renderer.headHash]], a morph leaves old stylesheets and scripts in
    * place) or to or from the error page, which has no root; else the repaint.
    * `None` when `holds` is this renderer's, or empty.
    */
  private def swapAnswer(
      live: Server.LiveSlug,
      session: Session,
      installed: Server.RendererState,
      s: SessionState
  ): Option[IO[Session.Step[Unit]]] =
    val stamp: AnyRef = installed.rendererOf.fold(installed)(_.generation)
    (s.painter, installed.rendererOf) match
      case (Painter.Unpainted, _)                  => None
      case (Painter.By(p), _) if p eq stamp        => None
      case (Painter.Reloading(i), _) if i eq stamp =>
        Some(IO.pure(Session.Step(s, Nil, ())))
      case (Painter.By(p), Some(r)) if p.headHash == r.headHash =>
        Some(repaint(live, session, p, r)(s))
      case _ => Some(IO.pure(Server.reloaded(s, stamp)))

  private def repaint(
      live: Server.LiveSlug,
      session: Session,
      prev: Renderer.Stamp,
      r: Renderer
  )(was: SessionState): IO[Session.Step[Unit]] = checked(r, was).flatMap { s =>
    // What is chosen NOW, not what this stream connected with: presses since
    // have moved the session's variables.
    val env = r.vars.env(s.values)
    val open = Set.empty[String]
    session.reveal(open, s.values) *> (stateStore.current, live.log.get)
      .flatMapN { (store, log) =>
        pageAnswers(r, open, store, env).map { (fragments, shared) =>
          val head =
            if (prev.styleHash != r.styleHash)
              Server.headPatches(r, session.slug)
            else Nil
          // Claimed as in [[openingPatches]]. `told` too, or the keepalive
          // announces a lower version than the swap did.
          val (painted, held) =
            Patches.repaint(r, shared, Viewing(fragments))
          Session.Step(
            s.copy(
              open = open,
              holds = held,
              position = store.version,
              told = store.version,
              painter = Painter.By(r.generation)
            ),
            head ++ painted ++
              // A value the edit refused is the declared one again, which the
              // page's committed signal and its URL mirror must follow.
              Option
                .when(s.values != was.values)(
                  Datastar.patchSignals(
                    VarKey.signalsJson(r.vars.committed(s.values)).noSpaces
                  )
                )
                .toList :+
              // A swap rotates the log id; without this a reconnect quotes a
              // dead log and repaints.
              Server.cursorSignals(r, log.idUnder(r.generation), store.version),
            ()
          )
        }
      }
  }

  /** The page's answers, and the version its render reads. */
  private def pageAnswers(
      renderer: Renderer,
      open: Set[String],
      store: StoreState,
      env: VarEnv
  ): IO[(FetchSnapshot, Shared)] =
    decidedFor(renderer, store, env)(renderer.fetchesForPage(open, _, env))
      .flatMap((reads, shared) => answer(reads, env).map(_ -> shared))

  /** Set a node variable for this viewer and re-render the readers it is shown
    * (issue #209): the nodes whose fetches or slots read it, and each pick or
    * set it moves, planned against what this session's host holds. Per session:
    * it reads and logs nothing.
    *
    * The untrusted value is checked ([[Renderer.refusals]]) before it is kept.
    * `None` drops the choice, so the variable holds what it opened at again.
    * Committed last even when no bytes moved: the commit ends the pending ask
    * (ADR 0025).
    */
  private def setVar(
      session: Session,
      renderer: Renderer,
      key: VarKey,
      chosen: Option[String]
  )(s: SessionState): IO[Session.Step[Unit]] = {
    val proposed = chosen.fold(s.vars - key)(v => s.vars + (key -> v))
    for {
      store <- stateStore.current
      reach <- reached(renderer, store, s, List(key))
      _ <- IO.raiseWhen(
        (renderer.vars.readersOf(key).isEmpty &&
          renderer.vars.picksDecidedBy(key).isEmpty) ||
          !reach.declarersShown(key)
      )(
        FHError.notFound(
          s"no node this viewer is shown reads the variable '${key.name}' declared on '${key.declarer}'"
        )
      )
      _ <- renderer.refusals(proposed) match {
        case Nil     => IO.unit
        case refused =>
          IO.raiseError(FHError.badCondition(refused.mkString("; ")))
      }
      step <- commitVars(session, renderer, store, s, proposed, List(key))(
        reach
      )
    } yield step
  }

  /** Back or Forward moved the URL to `url`'s variables (`.inHistory()`, ADR
    * 0005): each one in the URL is read again as a page load reads it, a
    * refused value dropped, and whatever moved is re-rendered as a press would.
    * One kept out of the URL is left alone, since its absent parameter would
    * read as its default.
    */
  private def fromHistory(
      session: Session,
      renderer: Renderer,
      url: Map[VarKey, String]
  )(s: SessionState): IO[Session.Step[Unit]] = {
    val inUrl = renderer.vars.inUrl
    val (proposed, refused) = renderer.adopt(
      s.vars.filterNot((k, _) => inUrl(k)),
      url.view.filterKeys(inUrl).toMap
    )
    val before = renderer.vars.committed(s.values)
    val after = renderer.vars.committed(s.initialValues ++ proposed)
    val moved = after.keys.filter(k => before.get(k) != after.get(k)).toList
    for {
      _ <- IO.whenA(refused.nonEmpty)(
        logger.warn(
          s"a history entry carried choices no reader takes, ignored: ${refused.mkString("; ")}"
        )
      )
      store <- stateStore.current
      reach <- reached(renderer, store, s, moved)
      step <- commitVars(session, renderer, store, s, proposed, moved)(
        reach
      )
    } yield step
  }

  /** What a write to `keys` reaches on this viewer's screen before it: the
    * declarers shown, and the picks, sets and readers it moves.
    */
  private def reached(
      renderer: Renderer,
      store: StoreState,
      s: SessionState,
      keys: List[VarKey]
  ): IO[Server.Reach] = {
    val readers = keys.flatMap(renderer.vars.readersOf).distinct
    val groups = keys.flatMap(renderer.vars.picksDecidedBy).distinct
    decidedFor(renderer, store, renderer.vars.env(s.values)) { at =>
      val open = renderer.picks.screen(s.open, at)
      val shown = renderer.picks.visibleNode(_, open, at)
      Server.Reach(
        keys.filter(k => shown(k.declarer)).toSet,
        groups.filter(gid =>
          shown(gid) && renderer.members
            .setContainer(gid)
            .fold(renderer.picks.groupShown(gid, at))(
              renderer.members.setShown(_, at)
            )
        ),
        readers.filter(shown)
      )
    }.map(_._1)
  }

  /** Keep `proposed`, already checked, and send what `reach` moves, planned
    * against what this session's host holds. Committed last even when no bytes
    * moved: the commit ends the pending ask (ADR 0025).
    */
  private def commitVars(
      session: Session,
      renderer: Renderer,
      store: StoreState,
      s: SessionState,
      proposed: Map[VarKey, String],
      keys: List[VarKey]
  )(reach: Server.Reach): IO[Session.Step[Unit]] = {
    val values = s.initialValues ++ proposed
    val env = renderer.vars.env(values)
    for {
      _ <- session.reveal(Set.empty, values)
      pulled <- decidedFor(renderer, store, env) { at =>
        val o =
          Patches.written(renderer, s.holds, at, reach.targets, reach.groups)
        o -> renderer.readsForPull(o.touched, o.filled, at, env)
      }
      ((owing, reads), shared) = pulled
      snapshot <- answer(reads, env)
      live <- liveFor(session.slug)
      patches <- live.toList.flatTraverse(l =>
        Patches.patches(
          renderer,
          l.cache,
          s.holds,
          shared,
          Viewing(snapshot),
          owing
        )
      )
      signals = renderer.vars.committed(values).view.filterKeys(keys.toSet)
    } yield Session.Step(
      s.copy(
        vars = proposed,
        holds = patches.foldLeft(s.holds)(Patches.applied(renderer.index, _, _))
      ),
      Patches.encode(patches) ++ Option
        .when(signals.nonEmpty)(
          Datastar.patchSignals(VarKey.signalsJson(signals.toMap).noSpaces)
        )
        .toList,
      ()
    )
  }

  /** `s` as `renderer` takes it ([[Renderer.recheck]]), where a session's
    * values meet a renderer: a dashboard edit while its stream was down misses
    * [[repaint]], so every such entry checks, and only once per renderer.
    */
  private def checked(
      renderer: Renderer,
      s: SessionState
  ): IO[SessionState] = {
    val (ok, refused) = renderer.recheck(s)
    IO.whenA(refused.nonEmpty)(
      logger.warn(
        s"the dashboard changed under choices it no longer takes, reset: ${refused.mkString("; ")}"
      )
    ).as(ok)
  }

  /** A session this process forgot (a restart, a reap) would start at the
    * declared values and reset every bar on the opening frame. The reconnect
    * still carries what the server last committed, so it is adopted, each value
    * under the check every other entry passes: one stale value costs only its
    * own variable. A live session's own choices win, since a commit can be lost
    * with the stream it rode.
    */
  private def adoptCarriedVars(
      s: SessionState,
      renderer: Renderer,
      req: Request[IO]
  ): IO[SessionState] = {
    val (kept, refused) = renderer.adopt(
      s.vars,
      Server.carriedVars(req, renderer).removedAll(s.vars.keys)
    )
    IO.whenA(refused.nonEmpty)(
      logger.warn(
        s"a reconnect carried choices no reader takes, ignored: ${refused.mkString("; ")}"
      )
    ).as(s.copy(vars = kept))
  }

  /** With no resolver wired only a render that reads a fetch raises, so a
    * dashboard without charts still works.
    */
  private def answer(
      wanted: List[SlotRead],
      env: VarEnv
  ): IO[FetchSnapshot] =
    (fetches, wanted) match {
      case (Some(resolver), qs) if qs.nonEmpty =>
        FetchSnapshot
          .resolve(
            resolver,
            qs,
            env,
            FetchIdentity.Instance
          )
      // Keep the env: `FetchSnapshot.empty` would resolve declared values.
      case _ => IO.pure(FetchSnapshot.of(Map.empty, env))
    }

  /** Resolve `conn` (from the signals body) to its session and renderer, and
    * run `f` as one of its steps. Only success is NoContent; every refusal is
    * [[actionRefused]] (ADR 0024).
    */
  private def withSession(
      req: Request[IO],
      slug: String
  )(
      f: (Session, Renderer) => SessionState => IO[Session.Step[Unit]]
  ): IO[Response[IO]] =
    req.bodyText.compile.string
      .map(io.circe.parser.parse(_).toOption.flatMap(connOf))
      .flatMap {
        case None =>
          actionRefused(req, "missing conn")
        case Some(conn) =>
          gate.handleRequirement(req, Requirement.FromDashboard(Some(slug)))(
            inSession(
              req,
              slug,
              rendererFor(slug).flatMap(
                _.liftTo[IO](
                  FHError.notFound(s"no dashboard '$slug' is being served")
                )
              ),
              conn,
              None
            )(f)
          )
      }

  /** [[withSession]] once `conn` is read and the request is admitted. A refusal
    * also clears `pending`.
    */
  private def inSession(
      req: Request[IO],
      slug: String,
      served: IO[Renderer],
      conn: String,
      pending: Option[String]
  )(
      f: (Session, Renderer) => SessionState => IO[Session.Step[Unit]]
  ): IO[Response[IO]] =
    served
      .flatMap { renderer =>
        // Only the stream makes a session: a press carries too little of the
        // page to rebuild one (ADR 0024).
        sessions.get(conn).flatMap {
          case None =>
            actionRefused(req, Server.NotConnectedMessage, pending)
          case Some(session) if session.slug != slug =>
            actionRefused(req, Server.WrongSlugMessage, pending)
          case Some(session) =>
            session
              .run(was =>
                (checked(renderer, was), liveFor(slug)).flatMapN { (s, live) =>
                  // Pressed on the page the old renderer painted, which may
                  // have meant something else by it: repainted, not run.
                  live.flatMap(
                    swapAnswer(
                      _,
                      session,
                      Server.RendererState.Ready(renderer),
                      s
                    )
                  ) match
                    case Some(swap) =>
                      swap.map(st => Session.Step(st.next, st.frames, false))
                    case None =>
                      f(session, renderer)(s)
                        .map(st => Session.Step(st.next, st.frames, true))
                }
              )
              .flatMap(ran =>
                if ran then NoContent()
                else actionRefused(req, Server.DashboardChangedMessage, pending)
              )
        }
      }
      // A 4xx becomes a refusal; a 5xx is our bug and stays with
      // [[FHError.handle]] rather than showing the user an internal message.
      .recoverWith {
        case e: FHError if e.status < 500 =>
          actionRefused(req, e.message, pending)
      }

  private def connOf(body: Json): Option[String] =
    body.hcursor.get[String](Server.ConnSignal).toOption

  /** Adopt `conn`'s session, or mint one under the same id: the client loses
    * only its `holds` suppression (bytes, never staleness). The reaper may win
    * the race with the adopt for the same reason.
    */
  private def adoptOrMint(slug: String, conn: String): IO[(Session, Int)] =
    sessions
      .get(conn)
      .map(_.filter(_.slug == slug))
      .flatMap(_.flatTraverse(s => s.adopt.map(_.map(s -> _))))
      .flatMap {
        case Some(adopted) => IO.pure(adopted)
        // A session minted by its own stream is Held(1) from birth.
        case None =>
          Session
            .create(slug, supervisor, logger)
            .flatTap(_.adopt)
            .flatTap(reapUnregistered(conn, _))
            .map(_ -> 1)
      }

  /** A stream registers the session it minted only once its body runs. One that
    * never does (the slug is not served, or the response was dropped first)
    * would stay `Held(1)` with nothing to reap it, its owner kept on the
    * supervisor until shutdown.
    */
  private def reapUnregistered(conn: String, session: Session): IO[Unit] =
    supervisor
      .supervise(
        IO.sleep(windows.adoption) *>
          sessions
            .get(conn)
            .flatMap(current =>
              IO.unlessA(current.exists(_ eq session))(
                session.tenure.update {
                  case Tenure.Held(1) => Tenure.Reaped
                  case t              => t
                }
              )
            )
      )
      .void

  /** Drop `conn`'s session after `after`, unless its tenure moved off
    * `expected` — for a document that never opened a stream ([[Tenure.Fresh]])
    * and a stream that ended ([[Tenure.Lingering]]). [[Session.relinquish]] and
    * [[Session.adopt]] decide on one ref, so a reconnect during the sleep makes
    * this fail rather than race.
    */
  private def reapAfter(
      conn: String,
      session: Session,
      expected: Tenure,
      after: FiniteDuration
  ): IO[Unit] =
    supervisor
      .supervise(
        IO.sleep(after) *>
          session
            .relinquish(expected)
            .flatMap(IO.whenA(_)(sessions.deregisterIf(conn, session)))
      )
      .void

  private def warnAnomalies(renderer: Renderer, env: VarEnv): IO[Unit] =
    renderer.picks.selectionAnomalies(env).traverse_(w => logger.warn(w))

  private def callService(
      domain: String,
      service: String,
      target: ServiceTarget,
      serviceData: Json,
      req: Request[IO]
  ): IO[Response[IO]] =
    actions
      .call(req, domain, service, target, serviceData)
      .attempt
      .flatMap {
        case Right(_)  => NoContent()
        case Left(err) =>
          // Not retried: a toggle run twice is back where it started, and a
          // failure here cannot say whether HA ran it.
          actionRefused(req, Option(err.getMessage).getOrElse(err.toString))
      }

  /** Every refused action answers 200 with signals, never 4xx: the pinned
    * bundle drops a non-200 body unread (ADR 0024). The signals set the pressed
    * control's `_<node>__error`, clear the `pending` ask a choice wrote (ADR
    * 0025) and raise `_toast`. The node is the client's claim from the query
    * string; nothing is authorized off it.
    */
  private def actionRefused(
      req: Request[IO],
      message: String,
      pending: Option[String] = None
  ): IO[Response[IO]] =
    Ok(Server.actionSignals(req, message, pending))

  /** The live state of every entity a node binds, for the editor preview's
    * overlay. An unknown node is `[]`.
    */
  private def nodeDebug(slug: String, id: String): IO[Response[IO]] =
    rendererFor(slug).flatMap {
      case None           => NotFound()
      case Some(renderer) => nodeDebugJson(renderer, id)
    }

  private def nodeDebugJson(
      renderer: Renderer,
      id: String
  ): IO[Response[IO]] =
    stateStore.current
      .flatMap(store =>
        decided(renderer, store)(
          renderer.entitiesForNode(NodeId.derived(id), _)
        ).map((entities, _) => store.entities -> entities)
      )
      .flatMap { (states, entities) =>
        val arr = Json.arr(entities.map { e =>
          states.get(e) match {
            case Some(st) =>
              Json.obj(
                "entity_id" -> Json.fromString(e),
                "state" -> Json.fromString(st.state),
                "attributes" -> Json.fromFields(st.attributes.toList)
              )
            case None =>
              Json.obj(
                "entity_id" -> Json.fromString(e),
                "state" -> Json.Null,
                "attributes" -> Json.obj()
              )
          }
        }*)
        Ok(arr)
      }

  /** Validated by the same [[DashboardBuild.decode]] as the eval path, and a
    * failure goes back as a 400 — the pushing developer has no server logs. The
    * URL's slug wins over the body's. A pushed site names its own slugs and is
    * all-or-nothing.
    */
  private def pushResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    answerErrors(
      req.bodyText.compile.string
        .map(io.circe.parser.parse)
        .flatMap {
          case Left(err) =>
            BadRequest(s"push body is not JSON: ${err.getMessage}")
          case Right(json)
              if json.asObject.exists(_.contains(Site.DashboardsKey)) =>
            pushSite(json)
          case Right(json) =>
            DashboardBuild
              .decode(json, slug = Some(slug), minifier = minifier)
              .flatMap(v => push(v).as(v))
              .flatMap(v =>
                Ok(
                  s"pushed ${v.dashboard.slug} (${v.dashboard.cards.size} cards)"
                )
              )
        }
    )

  private def pushSite(json: Json): IO[Response[IO]] =
    Site
      .decode(json, minifier = minifier)
      .flatMap { site =>
        site.dashboards.collect { case (slug, Left(err)) =>
          s"'$slug': $err"
        } match {
          case Nil =>
            val ready = site.dashboards.collect { case (slug, Right(v)) =>
              slug -> v
            }
            ready.traverse_ { case (_, v) => push(v) } *>
              this.site.setPreferred(site.default) *>
              Ok(
                s"pushed ${ready.size} dashboard(s): ${site.slugs.mkString(", ")}"
              )
          case errors =>
            BadRequest(
              s"site push rejected (${errors.size} dashboard(s) failed):\n" +
                errors.mkString("\n")
            )
        }
      }

  /** `no-cache` is the load-bearing header: `dump.pkl` changes under a fixed
    * URL, and a stored copy would offer completions for devices that are gone.
    * The ETag serves browser/editor JS; pkl-core 0.32.1 does no conditional
    * requests at all.
    */
  private def systemPklResponse(
      text: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val etag = EntityTag(LibPackage.sha256(text.getBytes(UTF_8)))
    val fresh = req.headers
      .get[`If-None-Match`]
      .exists {
        case `If-None-Match`(None)       => true
        case `If-None-Match`(Some(tags)) =>
          tags.exists(t => t.tag == etag.tag)
      }
    val cacheControl = `Cache-Control`(CacheDirective.`no-cache`())
    if (fresh) NotModified().map(_.putHeaders(ETag(etag), cacheControl))
    else
      Ok(text).map(
        _.withContentType(`Content-Type`(MediaType.text.plain))
          .putHeaders(ETag(etag), cacheControl)
      )
  }

  /** The theme of whatever `/` serves now; `None` falls back to the committed
    * colours ([[PwaAssets.manifest]]).
    */
  private def chromeColors: IO[Option[ChromeColors]] =
    site.defaultSlug
      .flatMap(site.liveFor)
      .flatMap(_.flatTraverse(_.renderer.get.map {
        case Server.RendererState.Ready(r)  => r.chromeColors
        case Server.RendererState.Failed(_) => None
      }))

  private def pageResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    liveFor(slug).flatMap {
      case None       => NotFound()
      case Some(live) => pageFor(slug, live, req)
    }

  private def pageFor(
      slug: String,
      live: Server.LiveSlug,
      req: Request[IO]
  ): IO[Response[IO]] =
    (
      live.renderer.get,
      live.log.get,
      IO.randomUUID.map(_.toString)
    ).flatMapN { (state, log, conn) =>
      state match
        case Server.RendererState.Failed(message) =>
          errorPage(slug, message, req)
        case Server.RendererState.Ready(renderer) =>
          stateStore.current.flatMap(pageOn(slug, renderer, log, conn, req))
    }

  private def pageOn(
      slug: String,
      renderer: Renderer,
      log: FragmentLog,
      conn: String,
      req: Request[IO]
  )(store: StoreState): IO[Response[IO]] = {
    // Narrowed to declarations: an undeclared URL key would become a
    // signal name in the opening frame. A refused one is dropped, not a
    // 400: a link outlives the layout it was copied from.
    val (choices, refused) = renderer.adopt(
      Map.empty,
      Server
        .varChoicesOf(req)
        .view
        .filterKeys(renderer.vars.defaults.contains)
        .toMap
    )
    // From this read, not the walk's: an initial value is decided once,
    // and a state a few milliseconds older decides it just as well.
    val initialValues = renderer.initialValuesFor(store.entities)
    IO.whenA(refused.nonEmpty)(
      logger.warn(
        s"a link carried choices no reader takes, ignored: ${refused.mkString("; ")}"
      )
    ) *> renderPage(
      slug,
      renderer,
      log,
      conn,
      req,
      choices,
      initialValues
    )
  }

  /** The dashboard document for a `Ready` slug. The document mints this
    * client's session: it is the first and largest thing that puts fragments in
    * the DOM, so `holds` means "bytes this client was sent" for its whole life.
    */
  private def renderPage(
      slug: String,
      renderer: Renderer,
      log: FragmentLog,
      conn: String,
      req: Request[IO],
      choices: Map[VarKey, String],
      initialValues: Map[VarKey, String]
  ): IO[Response[IO]] = {
    val env = renderer.vars.env(initialValues ++ choices)
    val editMode = req.uri.query.params.get("edit").contains("1")
    val open = Set.empty[String]
    for {
      // Read once: the banner rendered and the value recorded as told must
      // agree, or the stream repeats it or skips a real change.
      live <- healthy.get
      session <- Session
        .create(
          slug,
          supervisor,
          logger,
          SessionState.initial
            .copy(
              open = open,
              vars = choices,
              initialValues = initialValues,
              checkedBy = Some(renderer.generation),
              painter = Painter.By(renderer.generation)
            )
        )
        .flatTap(_.haDown.set(Some(!live)))
      // REGISTERED BEFORE THE SNAPSHOT IS READ — see [[recordFrame]].
      _ <- sessions.register(conn, session)
      _ <- reapAfter(conn, session, Tenure.Fresh, windows.adoption)
      store <- tracer
        .span("dashboard.page.store", Attribute("fh.slug", slug))
        .surround(stateStore.current)
      // Decided before the head: the walk reaches decisions at once, where a
      // slot's answers it waits for below.
      decidedPage <- decidedFor(renderer, store, env)(
        renderer.fetchesForPage(open, _, env)
      )
      (reads, shared) = decidedPage
      // Captured now: the walk runs when the body is pulled, after this `for`
      // returns, and would otherwise open its own trace (#75).
      parentSpan <- tracer.currentSpanContext
      // Filled by the walk; `holds` is committed in the finalizer, once the
      // bytes are out.
      ownRef <- IO.ref(Map.empty[Ix, Held])
      // The document carries the cursor, so it is the first announcement.
      _ <- session.update(
        _.copy(position = store.version, told = store.version)
      )
      _ <- warnAnomalies(renderer, env)
      restore = Server.Restore(
        conn,
        Some(
          Server.Cursor(
            renderer.headHash,
            renderer.styleHash,
            log.idUnder(renderer.generation),
            store.version
          )
        )
      )
      // Answered while the head goes out, so a cold chart's fetch overlaps
      // the browser fetching stylesheets (architecture §0). A
      // `CompletableFuture`, not a `Deferred`: the waiter is the synchronous
      // walk on a blocking thread. Cancelled with the body, or a walk parked
      // on it never wakes.
      pending = new java.util.concurrent.CompletableFuture[FetchSnapshot]()
      fetch <- answer(reads, env).attempt
        .flatMap { r =>
          IO {
            val _ = r.fold(pending.completeExceptionally, pending.complete)
          }
        }
        .onCancel(IO(pending.cancel(false)).void)
        .start
      // The walk writes straight into the response body, so a render holds
      // one node rather than the page and the head arrives first. This
      // `IO.blocking` is why a suite that fetches documents runs on the real
      // clock: `TestControl` cannot run `readOutputStream`'s two blocking sides.
      body = fs2.io
        .readOutputStream[IO](Server.PageChunkBytes) { os =>
          IO.blocking {
            // Buffered: each small write is a `synchronized` call into
            // `StreamEncoder`, 729 kB of churn per page open
            // (`RenderBench.pageWalkStreamUnbuffered` vs `pageWalkStream`).
            val w = new java.io.BufferedWriter(
              new java.io.OutputStreamWriter(os, UTF_8),
              Server.PageChunkBytes
            )
            var own = Map.empty[Ix, Held]
            pageInto(
              Sink.streaming(w),
              slug,
              sink => {
                // Out of the buffer, or the head waits for the answers too.
                if (!pending.isDone) w.flush()
                own = renderer.renderPageHolds(
                  sink,
                  shared,
                  Viewing(Server.awaitAnswers(pending))
                )
              },
              renderer.themeColorTags,
              renderer.themeClasses,
              renderer.offlineBanners,
              renderer.stylesheets.map(assets.rewrite),
              renderer.deferredStylesheets.map(assets.rewrite),
              renderer.scripts.map(assets.rewrite),
              renderer.inlineScripts,
              renderer.title,
              Server.ingressPrefixOf(req),
              restore,
              editMode,
              haDown = !live,
              committed = renderer.vars.committed(initialValues ++ choices),
              initialValues = renderer.vars.mirroredInitials.toList.map { key =>
                key -> initialValues
                  .get(key)
                  .orElse(renderer.vars.defaults(key))
                  .getOrElse("")
              }.toMap
            )
            // Flush, not close: `readOutputStream` owns the stream.
            w.flush()
            own
          }.flatMap { own =>
            // Nodes with a rendering of their own, as before structure's seeds
            // joined the holds.
            val painted = own.count(_._2.values.isDefined).toLong
            ownRef.set(own) *>
              tracer.currentSpanOrNoop.flatMap(
                _.addAttribute(Attribute("fh.nodes", painted))
              ) *> meters.pageNodes.record(painted)
          }.pipe(walk =>
            tracer.childOrContinue(parentSpan)(
              tracer.span("dashboard.page.walk").surround(walk)
            )
          )
        }
        // On success only: a truncated page leaves `holds` empty, which reads
        // as "send it". A throw mid-walk is a bug (the walk is pure over a
        // `Validated`), so it is only logged.
        .onFinalizeCase {
          case Resource.ExitCase.Succeeded =>
            // A load abandoned past its adoption window has no session left to
            // record for.
            ownRef.get
              .flatMap(held => session.update(_.copy(holds = held)))
              .recover { case e if Session.isEnded(e) => () }
          case Resource.ExitCase.Errored(e) =>
            logger.warn(e)(s"page render for '$slug' failed mid-walk")
          case Resource.ExitCase.Canceled => IO.unit
        }
        .onFinalize(fetch.cancel)
      resp <- Ok(body)
      // A buffering nginx in front (Nginx Proxy Manager here) otherwise gzips
      // the whole page before its first byte, so the head no longer arrives
      // first.
    } yield resp
      .withContentType(`Content-Type`(MediaType.text.html))
      .putHeaders(Header.Raw(CIString("X-Accel-Buffering"), "no"))
  }

  /** A self-contained page for a slug whose build failed: no theme, session or
    * cursor. It reloads when [[recoverStream]] says the fix landed.
    */
  private def errorPage(
      slug: String,
      message: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val baseHref = Server.ingressPrefixOf(req).fold("/")(p => s"$p/")
    val title = Server.escapeHtml(slug)
    val body =
      s"""<!doctype html>
         |<html>
         |<head>
         |  <meta charset="utf-8">
         |  <meta name="viewport" content="width=device-width, initial-scale=1">
         |  <base href="$baseHref">
         |  <title>Dashboard $title</title>
         |  <script type="module" src="${Server.DatastarScript}"></script>
         |</head>
         |<body data-init="@get('sse/dashboard/$slug/recover', ${Server.SseRetry})">
         |  <div data-signals="{${Server.ReloadSignal}: false}"
         |       data-effect="$$${Server.ReloadSignal} && window.location.reload()">
         |    <h1>Dashboard $title failed to build</h1>
         |    <pre>${Server.escapeHtml(message)}</pre>
         |    <p>Fix the source in the editor — the dashboard reloads automatically.</p>
         |    <p><a href="edit/file/${Site.EntryFile}">Edit ${Site.EntryFile}</a></p>
         |  </div>
         |</body>
         |</html>""".stripMargin
    Ok(body).map(_.withContentType(`Content-Type`(MediaType.text.html)))
  }

  /** The document shell around the body. Every app URL is relative to the
    * emitted `<base href>` (`/`, or the ingress prefix), so fragments arriving
    * later need no per-connection rewriting.
    */
  private def pageInto(
      out: Sink,
      slug: String,
      bodyInto: Sink => Unit,
      themeColorTags: String,
      themeClasses: ThemeClasses,
      offlineBanners: String,
      stylesheets: List[String],
      deferredStylesheets: List[String],
      scripts: List[String],
      inlineScripts: List[String],
      title: Option[String],
      ingressPrefix: Option[String],
      restore: Server.Restore,
      editMode: Boolean,
      haDown: Boolean,
      // Seeded ahead of the body: a control seeding its own highlight could
      // only name the declared value, and would mirror it into the URL over
      // the viewer's choice until connect.
      committed: Map[VarKey, String],
      // Each initial transform's value, which its URL mirror leaves off.
      initialValues: Map[VarKey, String]
  ): Unit = {
    // Inline card and theme scripts are emitted verbatim (authored source, not user
    // input); as classic scripts they run before the deferred modules.
    // `onload=null` first: some browsers fire `onload` again after the swap.
    val links = (
      stylesheets
        .map(href => s"""  <link rel="stylesheet" href="$href">""") ++
        deferredStylesheets.map(href =>
          s"""  <link rel="preload" as="style" href="$href" onload="this.onload=null;this.rel='stylesheet'">
             |  <noscript><link rel="stylesheet" href="$href"></noscript>""".stripMargin
        ) ++
        scripts
          .map(src => s"""  <script type="module" src="$src"></script>""") ++
        inlineScripts.map(js => s"""  <script>$js</script>""")
    ).mkString("\n")
    val baseHref = ingressPrefix.fold("/")(p => s"$p/")
    val pageTitle = Server.titleTag(title, slug)
    val editAssets =
      if (!editMode) ""
      else
        s"""<link rel="stylesheet" href="edit/overlay.css">
           |<script>window.__FH_EDIT__={"slug":"$slug","base":"$baseHref"};</script>
           |<script src="${FrontendAssets.url(
            "overlay"
          )}"></script>""".stripMargin
    // Two banners, separately sourced: HA down is the server's fact
    // (`healthy`, pushed as a signal); the SSE transport down only the client
    // can see, through [[Server.StreamEvent]] (not `datastar-fetch`, which
    // fires for every fetch — see the re-dispatch in `shell.ts`). A dead
    // transport freezes `haDown`, so the HA banner waits on `$_sse == 0`
    // (`theme.pkl`'s `offlineBanners`).
    // 2 = gave up, 1 = trying, 0 = fine.
    val sseState =
      "evt.detail.type === 'retries-failed' ? 2 : " +
        "(evt.detail.type === 'retrying' || evt.detail.type === 'error') ? 1 : 0"
    // 2 latches: any later event classifies as 0, and a `finished` in the same
    // debounce window swallowed the failure before it painted. Only a reload
    // gets out, which is all that reconnects once retries are exhausted.
    val sseLatched = s"$$${Server.SseSignal} >= 2 ? 2 : ($sseState)"
    // Inline, or the banners flash before the deferred module runs; and not
    // in CSS, because `data-show` only clears the inline `display`.
    val hidden = """style="display:none""""
    // Debounced so a visibility refetch or the outgoing page of a reload does
    // not flash "Reconnecting…". The pinned parser wants `__debounce.600ms`;
    // the docs' `.debounce_600ms` silently becomes part of the event name.
    val sseEvent = s"${Server.StreamEvent}__document__debounce.600ms"
    // Back and Forward move the URL first (`data-fh-url__history`, ADR 0005);
    // the server reads the whole query string again. Every popup close is a
    // `history.back()`, so this is also where a popup is closed from.
    val fromHistory =
      if (committed.isEmpty) ""
      else
        s"""
           |     data-on:popstate__window="@post('sse/history/$slug' + location.search, {retryMaxCount: 0})"""".stripMargin
    val varSeed = committed.toList
      .sortBy(_._1.committedSignal)
      .map { (key, v) =>
        s", ${key.committedSignal}: '${Server.escapeHtml(Server.escapeJsString(v))}', ${key.pendingSignal}: ''"
      }
      .mkString + initialValues.toList
      .sortBy(_._1.initialValueSignal)
      .map { (key, v) =>
        s", ${key.initialValueSignal}: '${Server.escapeHtml(Server.escapeJsString(v))}'"
      }
      .mkString
    // Every ask's clear in one effect: pending signals are client-only, so the
    // shell can hold them for every declarer, shown or not.
    val varClear =
      if (committed.isEmpty) ""
      else
        committed.keys.toList
          .sortBy(_.committedSignal)
          .map(_.pendingClear)
          .mkString(s"""\n  <div $hidden data-effect="""", "; ", "\"></div>")
    // The banners are the theme's markup, so its class rules apply as to a card's.
    val connBanner = themeClasses.rewriteTemplate(
      s"""<div data-signals="{${Server.HaDownSignal}: $haDown, ${Server.SseSignal}: 0, ${Server.ToastSignal}: '', ${Server.ReloadSignal}: false, ${Server.ConnSignal}: '${Server
          .escapeJsString(restore.conn)}'$varSeed}"
         |     data-effect="$$${Server.ReloadSignal} && window.location.reload()"$fromHistory
         |     data-on-signal-patch-filter="{include:/^${Server.ToastSignal}$$/}"
         |     data-on-signal-patch="$$${Server.ToastSignal} && (fhToast($$${Server.ToastSignal}), $$${Server.ToastSignal} = '')"
         |     data-on:$sseEvent="$$${Server.SseSignal} = $sseLatched">
         |  <div $hidden ${Server.PendingSweep}></div>$varClear
         |$offlineBanners
         |</div>""".stripMargin
    )
    val _ = out.append(s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  $themeColorTags
       |  <base href="$baseHref">
       |  <link rel="manifest" href="${PwaAssets.manifestUrl}">
       |  <link rel="icon" href="icon-192.png">
       |  $pageTitle
       |  <script>${Server.ShellScript}</script>
       |  <script>${Server.swRegisterCall}</script>
       |$links
       |  <script type="module" src="${Server.DatastarScript}"></script>
       |</head>
       |<body data-init="@get('sse/dashboard/$slug/patch${restore.query}', ${Server.SseRetry})">
       |<script>fhConn('${Server.escapeJsString(restore.conn)}')</script>
       |$connBanner
       |""".stripMargin)
    bodyInto(out)
    val _ = out.append(s"""
       |$editAssets
       |<script>${Server.scrollCall(slug)}</script>
       |</body>
       |</html>
       |""".stripMargin)
  }
}

object Server {

  /** A `Failed` dashboard stays registered and watched, is served as an error
    * page, and recovers on the next good reload.
    */
  private[runtime] enum RendererState:
    case Ready(renderer: Renderer)
    case Failed(message: String)

    def rendererOf: Option[Renderer] = this match
      case Ready(r)  => Some(r)
      case Failed(_) => None

  /** One slug's renderer and the log its cursors are valid for, in one value so
    * a swap cannot leave them out of step. A swap replaces the log ref's
    * contents, not the ref.
    */
  private[runtime] case class LiveSlug(
      renderer: SignallingRef[IO, RendererState],
      log: Ref[IO, FragmentLog],
      // The same cache across swaps, emptied by each ([[install]]).
      cache: RenderCache,
      // The newest store version the log covers.
      doorbell: SignallingRef[IO, Long]
  ) {

    /** The log first, so the new renderer's recorder finds its own log to seed
      * however soon it starts. Empties the cache after the swap, not before, or
      * a pull that read the old renderer just ahead of the set refills it. One
      * already rendering can still install afterwards; its entry holds its
      * bytes, not the renderer ([[Renderer.generation]]). Then rings, at the
      * same version, so an idle session steps and meets the swap
      * ([[swapAnswer]]). A log already numbered by this state's renderer is
      * kept: [[applySite]] publishes a new slug, whose pages may already quote
      * that log, before installing the state it was made with.
      */
    def install(state: RendererState): IO[Unit] =
      freshLog(state).flatMap(f =>
        log.update(l => if (sameStamp(l.stamp, f.stamp)) l else f)
      ) *> renderer.set(state) *> cache.clear *> doorbell.update(identity)

    private def sameStamp(
        a: Option[Renderer.Stamp],
        b: Option[Renderer.Stamp]
    ): Boolean = (a, b) match
      case (Some(x), Some(y)) => x eq y
      case _                  => false

    /** The recorder's first frame, only into the log its renderer numbers:
      * whether it did, since only then may it ring.
      */
    def seed(r: Renderer, version: Long): IO[Boolean] =
      log.modify(l =>
        if (l.numberedBy(r.generation)) (l.seeded(version), true)
        else (l, false)
      )
  }

  private[runtime] object LiveSlug {
    def of(state: RendererState): IO[LiveSlug] =
      SignallingRef[IO].of(state).flatMap(create)

    def create(renderer: SignallingRef[IO, RendererState]): IO[LiveSlug] =
      (
        renderer.get.flatMap(freshLog).flatMap(Ref[IO].of),
        RenderCache.create,
        SignallingRef[IO].of(0L)
      ).mapN(LiveSlug(renderer, _, _, _))
  }

  private def freshLog(state: RendererState): IO[FragmentLog] =
    IO.randomUUID.map(id =>
      FragmentLog.fresh(id.toString, state.rendererOf.map(_.generation))
    )

  /** Only a `FromSite` slug may be reclaimed by a reload (ADR 0010), and one
    * whose content is unchanged is not re-installed: a write rotates the log
    * and repaints every browser. The content is the model, not the renderer —
    * two evaluations of an unedited file give equal models, different
    * renderers.
    */
  private[runtime] enum Origin {
    case FromSite(content: Either[String, Dashboard])
    case Pushed
  }

  private[runtime] case class Entry(live: LiveSlug, origin: Origin)

  /** `Rebuilt` logs nothing: the reload's summary line covers it. */
  private[runtime] enum Change {
    case Added(slug: String, error: Option[String])
    case Broke(slug: String, error: String)
    case Recovered(slug: String)
    case Rebuilt(slug: String)
    case Removed(slug: String)

    def describe: (String, Option[String]) = this match {
      case Added(slug, None)      => (slug, Some(s"Dashboard '$slug' added"))
      case Added(slug, Some(err)) =>
        (slug, Some(s"Dashboard '$slug' added, but broken: $err"))
      case Broke(slug, err) =>
        (slug, Some(s"Dashboard '$slug' is now broken: $err"))
      case Recovered(slug) => (slug, Some(s"Dashboard '$slug' recovered"))
      case Rebuilt(slug)   => (slug, None)
      case Removed(slug)   =>
        (slug, Some(s"Dashboard '$slug' removed (no longer in the site)"))
    }
  }

  /** The live counterpart of the entrypoint's `dashboards` map (ADR 0021).
    * [[changes]] starts and stops the recorders, so installing a slug is
    * starting its publisher. An evaluated site is diffed here ([[applySite]]),
    * against the content kept beside each slug, not by the caller.
    */
  private[runtime] class LiveSite(
      entries: SignallingRef[IO, Map[String, Entry]],
      // May name a slug that no longer exists.
      preferred: Ref[IO, Option[String]],
      // The slug the boot registered its `Failed` state under, so a workspace
      // that never evaluated shows the error at `/`, not a 404.
      fallback: String
  ) {

    def liveFor(slug: String): IO[Option[LiveSlug]] =
      entries.get.map(_.get(slug).map(_.live))

    /** What anyone may do on one dashboard (issue #89). `None` means `/`, so
      * `/` is gated by whatever it serves. Uncached, so a reload's access
      * change applies on the next request.
      */
    def permissionFor(slug: Option[String]): IO[Permission] =
      slug
        .fold(defaultSlug)(IO.pure)
        .flatMap(liveFor)
        .flatMap {
          case None       => IO.pure(Permission.none)
          case Some(live) =>
            live.renderer.get.map {
              case RendererState.Ready(r) =>
                Permission(r.access, r.callOf)
              // Its page carries build diagnostics.
              case RendererState.Failed(_) => Permission.none
            }
        }

    def names: IO[List[String]] = entries.get.map(_.keys.toList.sorted)

    def changes: Stream[IO, Map[String, LiveSlug]] =
      entries.discrete.map(_.view.mapValues(_.live).toMap)

    /** The entity set the upstream subscription is narrowed to ([[HaFeed]]).
      * Live at both levels: the slug set, and each slug's renderer. Empty means
      * nothing is owed, not "unfiltered" (that is `None` one layer up).
      */
    def watchedEntities: Stream[IO, Set[String]] =
      changes.switchMap { slugs =>
        NonEmptyList.fromList(slugs.values.toList) match {
          case None       => Stream.emit(Set.empty[String])
          case Some(live) =>
            live
              .traverse(l =>
                l.renderer.map(
                  _.rendererOf.fold(Set.empty[String])(_.watchedEntities)
                )
              )
              .discrete
              .map(_.reduceLeft(_ ++ _))
        }
      }.changes

    /** A slug the entrypoint owns keeps its origin, so the next reload restores
      * it; a new one is [[Origin.Pushed]], out of [[applySite]]'s reach.
      */
    def installPushed(slug: String, state: RendererState): IO[Unit] =
      LiveSlug.of(state).flatMap { fresh =>
        entries
          .modify { es =>
            es.get(slug) match {
              case Some(existing) => (es, Some(existing.live))
              case None => (es + (slug -> Entry(fresh, Origin.Pushed)), None)
            }
          }
          .flatMap(_.traverse_(_.install(state)))
      }

    def setPreferred(slug: Option[String]): IO[Unit] = preferred.set(slug)

    /** Install what is new or changed, leave the unchanged alone, drop the
      * `FromSite` slugs the site no longer names.
      */
    def applySite(
        dashboards: List[(String, Either[String, Dashboard.Validated])],
        prefer: Option[String]
    ): IO[List[Change]] =
      for {
        _ <- setPreferred(prefer)
        current <- entries.get
        plan = planSite(current, dashboards)
        installs <- plan.installs.traverse { case (slug, result, change) =>
          val state = stateOf(result)
          val entry = Entry(_, Origin.FromSite(result.map(_.dashboard)))
          current.get(slug) match {
            case Some(existing) =>
              IO.pure((slug, entry(existing.live), state, change))
            case None =>
              LiveSlug.of(state).map(live => (slug, entry(live), state, change))
          }
        }
        _ <- entries.update { es =>
          installs.foldLeft(es -- plan.removals) {
            case (acc, (slug, e, _, _)) =>
              // A slug a concurrent push added keeps its live slug, rather than
              // having a fresh one swapped in under open connections.
              acc + (slug -> acc.get(slug).fold(e)(o => e.copy(live = o.live)))
          }
        }
        _ <- installs.traverse_ { case (_, entry, state, _) =>
          entry.live.install(state)
        }
      } yield installs.map(_._4) ++ plan.removals.toList.sorted.map(
        Change.Removed(_)
      )

    /** A site that will not evaluate: every `FromSite` slug shows `message`.
      * Membership is untouched, since the file no longer says what it is.
      */
    def failSite(message: String): IO[List[Change]] =
      entries.get.flatMap { current =>
        val broken = current.toList.sortBy(_._1).collect {
          case (slug, Entry(live, Origin.FromSite(was)))
              if was != Left(message) =>
            (
              slug,
              live,
              was.fold(
                _ => Change.Rebuilt(slug),
                _ => Change.Broke(slug, message)
              )
            )
        }
        entries.update(es =>
          broken.foldLeft(es) { case (acc, (slug, _, _)) =>
            acc.updatedWith(slug)(
              _.map(_.copy(origin = Origin.FromSite(Left(message))))
            )
          }
        ) *>
          broken
            .traverse_ { case (_, live, _) =>
              live.install(RendererState.Failed(message))
            }
            .as(broken.map(_._3))
      }

    def defaultSlug: IO[String] =
      (preferred.get, names).mapN(defaultSlugFor).map(_.getOrElse(fallback))
  }

  private[runtime] case class SitePlan(
      installs: List[(String, Either[String, Dashboard.Validated], Change)],
      removals: Set[String]
  )

  private[runtime] def planSite(
      current: Map[String, Entry],
      dashboards: List[(String, Either[String, Dashboard.Validated])]
  ): SitePlan = {
    val installs = dashboards.sortBy(_._1).flatMap { case (slug, result) =>
      val content = result.map(_.dashboard)
      current.get(slug) match {
        case Some(Entry(_, Origin.FromSite(was))) if was == content => None
        case Some(Entry(_, Origin.FromSite(was)))                   =>
          Some(
            (
              slug,
              result,
              (was, content) match {
                case (Left(_), Right(_))  => Change.Recovered(slug)
                case (_, Left(message))   => Change.Broke(slug, message)
                case (Right(_), Right(_)) => Change.Rebuilt(slug)
              }
            )
          )
        case Some(Entry(_, Origin.Pushed)) =>
          Some((slug, result, Change.Rebuilt(slug)))
        case None =>
          Some((slug, result, Change.Added(slug, content.left.toOption)))
      }
    }
    val named = dashboards.map(_._1).toSet
    val removals = current.collect {
      case (slug, Entry(_, Origin.FromSite(_))) if !named.contains(slug) => slug
    }.toSet
    SitePlan(installs, removals)
  }

  private[runtime] def stateOf(
      result: Either[String, Dashboard.Validated]
  ): RendererState = result match {
    case Right(validated) =>
      RendererState.Ready(Renderer(validated))
    case Left(message) => RendererState.Failed(message)
  }

  private[runtime] object LiveSite {
    def of(
        renderers: Map[String, SignallingRef[IO, RendererState]],
        content: Map[String, Either[String, Dashboard]],
        defaultSlug: String
    ): IO[LiveSite] =
      for {
        seeded <- renderers.toList
          .traverse { case (slug, r) =>
            LiveSlug
              .create(r)
              .map(live =>
                slug -> Entry(
                  live,
                  // No seeded content: treated as pushed, so a reload installs
                  // over it rather than reclaiming it blind.
                  content.get(slug).fold(Origin.Pushed)(Origin.FromSite(_))
                )
              )
          }
          .map(_.toMap)
        entries <- SignallingRef[IO].of(seeded)
        preferred <- Ref[IO].of(Option(defaultSlug).filter(_.nonEmpty))
      } yield new LiveSite(entries, preferred, defaultSlug)
  }

  /** Membership only, never build status: a preferred dashboard that failed
    * shows its error page rather than bouncing `/` elsewhere.
    */
  private[runtime] def defaultSlugFor(
      preferred: Option[String],
      slugs: List[String]
  ): Option[String] =
    preferred
      .filter(slugs.contains)
      .orElse(Option.when(slugs.contains(DefaultSlug))(DefaultSlug))
      .orElse(slugs.sorted.headOption)

  /** Also the slug a boot with nothing to serve registers its failure under. */
  val DefaultSlug: String = "dashboard"

  /** The server with its recorders running: with none, every pull finds an
    * empty log and the dashboard silently stops moving. `site` is the caller's,
    * since the reload path writes it.
    */
  private[runtime] def withSite(
      actions: ServiceCalls,
      stateStore: StateStore,
      site: LiveSite,
      sessions: Sessions,
      gate: AuthGate,
      assets: AssetCache,
      healthy: Signal[IO, Boolean],
      systemPkl: SystemPkl,
      dumpRefresh: Option[IO[DumpRefresh.Result]],
      windows: SessionWindows,
      tracer: Tracer[IO],
      loggerFactory: LoggerFactory[IO],
      meters: Meters,
      fetches: Option[FetchResolver],
      minifier: Minifier = Minifier.none
  ): Resource[IO, Server] =
    for {
      supervisor <- Supervisor[IO]
      server = new Server(
        actions,
        stateStore,
        site,
        sessions,
        gate,
        supervisor,
        assets,
        healthy,
        systemPkl,
        dumpRefresh,
        windows,
        tracer,
        loggerFactory,
        meters,
        fetches,
        minifier
      )
      _ <- server.sharedPatchPublishers.compile.drain.background
    } yield server

  /** Api, store and health all from one feed, so no caller can forget to
    * forward [[HaFeed.healthy]] and pin the banner off. [[ServerApp]] and
    * [[TestServer]] both come through here.
    */
  def fromFeed(
      feed: HaFeed,
      site: LiveSite,
      sessions: Sessions,
      gate: AuthGate,
      assets: AssetCache,
      systemPkl: SystemPkl,
      dumpRefresh: Option[IO[DumpRefresh.Result]],
      actions: HomeAssistantApi[IO] => ServiceCalls,
      tracer: Tracer[IO],
      loggerFactory: LoggerFactory[IO],
      meters: Meters,
      windows: SessionWindows,
      chart: Resource[IO, IO[ChartRenderer]],
      minifier: Minifier,
      historyClock: IO[java.time.Instant]
  ): Resource[IO, Server] =
    historyFetches(feed.api, chart, loggerFactory, historyClock).flatMap(
      fetches =>
        withSite(
          actions(feed.api),
          feed.store,
          site,
          sessions,
          gate,
          assets,
          feed.healthy,
          systemPkl,
          dumpRefresh,
          windows,
          tracer = tracer,
          loggerFactory = loggerFactory,
          meters = meters,
          fetches = Some(fetches),
          minifier = minifier
        )
    )

  private def historyFetches(
      api: HomeAssistantApi[IO],
      charts: Resource[IO, IO[ChartRenderer]],
      loggerFactory: LoggerFactory[IO],
      clock: IO[java.time.Instant]
  ): Resource[IO, FetchResolver] =
    for {
      chart <- charts
      log = loggerFactory.getLoggerFromName("fh.view.history")
      history <- History
        .create(
          SeriesSource.fromApi(api),
          onFailure = (k, e) =>
            log.warn(e)(
              s"history of ${k.entityId} over ${k.window.key} could not " +
                "be fetched; its charts show their error until a retry"
            ),
          now = clock
        )
        .toResource
      resolver <- FetchResolver
        .create(
          history,
          chart.map(_.render),
          onFailure = (k, e) =>
            log.warn(e)(
              s"${Step.key(k.stage)} of ${k.question._2} failed; " +
                "it shows its error until a retry"
            )
        )
        .toResource
    } yield resolver

  def dumpRefreshJson(result: DumpRefresh.Result): Json = {
    result match {
      case DumpRefresh.Unchanged =>
        Json.obj("status" -> Json.fromString("unchanged"))
      case DumpRefresh.Swapped(version, _) =>
        Json.obj(
          "status" -> Json.fromString("swapped"),
          "version" -> Json.fromString(version)
        )
      case DumpRefresh.Rejected(errors) =>
        Json.obj(
          "status" -> Json.fromString("rejected"),
          "errors" -> Json.fromValues(errors.map { case (slug, err) =>
            Json.obj(
              "slug" -> Json.fromString(slug),
              "error" -> Json.fromString(err)
            )
          })
        )
    }
  }

  /** What a document hands back on its first connect, as query params on the
    * `data-init` URL: **the first connect carries no view state** (`data-init`
    * fires from `<body>` before descendants' `data-signals` merge), so a
    * signals-only read renders the default tab. Signals win on a reconnect.
    */
  private[runtime] case class Restore(
      conn: String,
      // Without it the first connect repaints the body the document holds.
      cursor: Option[Cursor] = None
  ) {

    /** `&amp;`: this lands in an HTML attribute. */
    def query: String = {
      val params = cursor.toList.flatMap(c =>
        List(
          s"${cursorParam(HeadHashSignal)}=${encode(c.headHash)}",
          s"${cursorParam(StyleHashSignal)}=${encode(c.styleHash)}",
          s"${cursorParam(LogIdSignal)}=${encode(c.logId)}",
          s"${cursorParam(StoreVersionSignal)}=${c.version}"
        )
      ) :+ s"$ConnSignal=${encode(conn)}"
      params.mkString("?", "&amp;", "")
    }

    private def encode(s: String): String =
      java.net.URLEncoder.encode(s, UTF_8)
  }

  /** The committed values a reconnect carries for this build's declarations,
    * looked up by exact name: a declarer id may itself contain `__`, so the
    * signal name cannot be parsed back. An empty one is no value, not a choice.
    * Untrusted; the caller checks them.
    */
  private[runtime] def carriedVars(
      req: Request[IO],
      renderer: Renderer
  ): Map[VarKey, String] =
    signalsOf(req).fold(Map.empty) { c =>
      renderer.vars.defaults.keys.toList.flatMap { key =>
        c.downField(key.committedSignal)
          .as[String]
          .toOption
          .filter(_.nonEmpty)
          .map(key -> _)
      }.toMap
    }

  /** Untrusted and not narrowed; the caller narrows to declarations. */
  def varChoicesOf(req: Request[IO]): Map[VarKey, String] =
    req.uri.query.params.toList
      .flatMap((k, v) => VarKey.fromParam(k).map(_ -> v))
      .toMap

  private[runtime] def awaitAnswers(
      pending: java.util.concurrent.CompletableFuture[FetchSnapshot]
  ): FetchSnapshot =
    try pending.join()
    catch {
      case e: java.util.concurrent.CompletionException =>
        throw Option(e.getCause).getOrElse(e)
    }

  /** `X-Ingress-Path` as the page's `<base href>`. Attacker-suppliable on the
    * direct port and written into HTML, so anything but a strict safe path is
    * ignored, never escaped-and-trusted.
    */
  def ingressPrefixOf(req: Request[IO]): Option[String] =
    req.headers
      .get(CIString("X-Ingress-Path"))
      .map(_.head.value)
      .filter(IngressPathPattern.matches)

  // No `.` in the class, so no `..`.
  private val IngressPathPattern: scala.util.matching.Regex =
    "^(/[A-Za-z0-9_-]+)+$".r

  /** This server's base URL as the browser reached it: the OAuth `client_id`
    * and `redirect_uri` (issue #89), which HA checks share a host.
    *
    * `Host` is attacker-suppliable, which is acceptable only because the value
    * goes into a redirect back to the origin the request claimed. Never use it
    * to decide identity.
    */
  def baseUriOf(req: Request[IO]): Uri = {
    val scheme =
      req.headers
        .get(CIString("X-Forwarded-Proto"))
        .map(_.head.value)
        .orElse(req.uri.scheme.map(_.value))
        .getOrElse("http")
    val authority = req.headers
      .get(CIString("Host"))
      .map(_.head.value)
      .orElse(req.uri.authority.map(_.renderString))
      .getOrElse("localhost")
    val prefix = ingressPrefixOf(req).getOrElse("")
    Uri.unsafeFromString(s"$scheme://$authority$prefix")
  }

  /** Echoed in every action POST body so it finds its stream. Minted and seeded
    * by the document.
    */
  val ConnSignal: String = "conn"

  private[runtime] val WrongSlugMessage: String =
    "connection belongs to another dashboard"

  /** A press under a `conn` this process does not hold: the page's stream is
    * down or not yet back, and only the stream rebuilds the session.
    */
  private[runtime] val NotConnectedMessage: String =
    "Not connected — press again once the page has reconnected"

  /** A press on a page a dashboard edit has since replaced ([[swapAnswer]]). */
  private[runtime] val DashboardChangedMessage: String =
    "The dashboard changed — press again"

  /** What an action says about itself: the pressed control, filled at click
    * time (`core/tap.pkl`).
    */
  val NodeParam: String = "node"

  val ToastSignal: String = "_toast"

  /** Clears every pending ask when a non-200 arrived, whose body Datastar drops
    * unread, or a post never reached the server (`retries-failed`: a tap is
    * sent once), so no answer can come (ADR 0025). One rule on the shell, not
    * one per group. The stream's own failures (on `<body>`) are the banner's. A
    * dead stream does not clear them: it shows the banner, and the reconnect
    * restates every committed choice.
    *
    * `@setAll` peeks while it writes in the pinned bundle, so this neither
    * depends on every pending signal nor re-triggers itself. Busy signals are
    * not swept: the bundle clears them in a `finally`.
    */
  val PendingSweep: String =
    """data-on:datastar-fetch__document="(evt.detail.type === 'error' || evt.detail.type === 'retries-failed') && evt.detail.el !== document.body && @setAll('', {include:/__pending$/})""""

  /** An untrusted id that becomes a signal name. Real ids are `[A-Za-z0-9_]`,
    * so the check is exact.
    */
  private val IdClaim = "[A-Za-z0-9_]{1,128}".r

  private def idParam(req: Request[IO], name: String): Option[String] =
    req.uri.query.params.get(name).filter(IdClaim.matches)

  private[runtime] def actionSignals(
      req: Request[IO],
      message: String,
      pending: Option[String]
  ): Json = {
    val text = Json.fromString(message)
    Json.fromFields(
      idParam(req, NodeParam).map(id => s"_${id}__error" -> text).toList ++
        pending.map(_ -> Json.fromString("")) ++
        List(ToastSignal -> text)
    )
  }

  /** `_`-prefixed so the default filter keeps the cursor out of every request
    * but the SSE GET, which includes it back ([[SseInclude]]). Nested because
    * Datastar merges nested objects, so a live batch can patch only
    * `storeVersion`.
    */
  val CursorSignal: String = "_cursor"

  val HaDownSignal: String = "_haDown"

  /** The stream's state as the client sees it: 0 fine, 1 retrying, 2 gave up.
    * The offline banners (`theme.pkl`) and the slider and wheel read it.
    */
  val SseSignal: String = "_sse"

  /** Re-dispatched by `shell.ts` for the SSE stream's own fetch. Shared with
    * the TypeScript: rename one and the banner silently stops updating.
    */
  val StreamEvent: String = "fh-stream"

  /** The resume fields under [[CursorSignal]] (ADR 0011). A mismatched
    * `headHash` reloads, `styleHash` patches the head, `logId` repaints.
    */
  val HeadHashSignal: String = "headHash"
  val StyleHashSignal: String = "styleHash"
  val LogIdSignal: String = "logId"
  val StoreVersionSignal: String = "storeVersion"

  val PrevConnParam: String = "prev"

  /** A comment, so Datastar never sees it; the first element a test can await
    * to know the stream subscribed.
    */
  private[runtime] val recoverOpenMarker: SseFrame =
    SseFrame.comment("recover-open")

  /** `shell.ts`, inlined as a classic script: `fhConn` runs mid-body, so a
    * deferred module defines it too late. A missing resource fails hard —
    * without it a page looks fine and silently loses tab selection, session
    * handoff and scroll.
    */
  val ShellScript: String = FrontendAssets.content("shell")

  /** Datastar as the page loads it: the vendored bundle and our own attributes,
    * built into one module (`src/js/datastar.ts`).
    */
  val DatastarScript: String = FrontendAssets.url("datastar")

  /** Classic and inline for the same reason as [[ShellScript]]: it must run
    * before Datastar's deferred module.
    */
  val swRegisterCall: String =
    s"fhRegisterSw('${escapeJsString(PwaAssets.swUrl)}')"

  /** A separate `<script>` from the shell, so it still runs when the shell
    * failed to parse, and says so. The build guards this too
    * (`fh-assert-self-contained`); this catches what it cannot see.
    */
  private[runtime] def scrollCall(slug: String): String = {
    val id = escapeJsString(slug)
    s"if(window.fhScroll)fhScroll('$id');" +
      "else console.error('fh: the page shell did not run \\u2014 tab selection, " +
      "session handoff and scroll restore are all disabled on this page')"
  }

  val TitleId: String = "fh-title"

  private[runtime] def titleTag(title: Option[String], slug: String): String =
    s"""<title id="$TitleId">${escapeHtml(title.getOrElse(slug))}</title>"""

  /** Everything [[Renderer.styleHash]] covers, patched rather than reloaded so
    * a slider mid-drag and the scroll position survive.
    */
  private[runtime] def headPatches(
      renderer: Renderer,
      slug: String
  ): List[SseFrame] =
    List(
      Datastar.patchElements(renderer.themeStyleTag),
      Datastar.patchElements(titleTag(renderer.title, slug))
    )

  val ReloadSignal: String = "_reload"

  /** End the stream when its dashboard's rule stops holding, with a reload last
    * (issue #89): a frozen page would go on showing the house to someone signed
    * out. A merge, not `interruptWhen`, so the reload is delivered before the
    * close.
    *
    * '''Interrupt what this returns, never `events`''': fs2 interruption is
    * scoped, the merge never learns the branch ended, and against
    * [[fh.view.auth.AuthGate]]'s `Stream.never` the body never ends.
    */
  private[runtime] def untilRevoked(allowed: Stream[IO, Boolean])(
      events: Stream[IO, SseFrame]
  ): Stream[IO, SseFrame] =
    events.mergeHaltBoth(allowed.find(!_).as(reloadPatch))

  private[runtime] val reloadPatch: SseFrame =
    Datastar.patchSignals(s"""{"$ReloadSignal":true}""")

  /** `s` told to reload while `installed` is: nothing it holds is kept. */
  private[runtime] def reloaded(
      s: SessionState,
      installed: AnyRef
  ): Session.Step[Unit] =
    Session.Step(
      s.copy(holds = Map.empty, painter = Painter.Reloading(installed)),
      List(reloadPatch),
      ()
    )

  /** What a reconnecting browser claims its DOM holds. */
  private[runtime] case class Cursor(
      headHash: String,
      styleHash: String,
      logId: String,
      version: Long
  )

  /** One shape, not four lookups: a partial payload is a `Left` that
    * [[cursorAnomaly]] reports, rather than a silent fall-through to the
    * document's frozen params.
    */
  private val cursorDecoder: Decoder[Cursor] =
    Decoder.forProduct4[Cursor, String, String, String, Long](
      HeadHashSignal,
      StyleHashSignal,
      LogIdSignal,
      StoreVersionSignal
    )(Cursor.apply)

  /** A cursor that does not decode (a filter that dropped one of its fields, an
    * old page): the only symptom is a resume quietly larger than it should be,
    * forever. One dropped whole reads as a first connect ([[isReconnect]]),
    * which this cannot see.
    */
  private[runtime] def cursorAnomaly(req: Request[IO]): Option[String] =
    signalsOf(req)
      .map(_.downField(CursorSignal))
      .filter(_.succeeded)
      .flatMap(_.as(using cursorDecoder).left.toOption)
      .map(f =>
        "reconnect carried a cursor that does not decode " +
          s"(${f.getMessage}) — resuming from the document's frozen params " +
          "instead. Check the client's filterSignals: the four cursor signals " +
          "must reach the SSE GET."
      )

  /** Signals first: the `data-init` params are frozen at page render, and
    * preferring them resumes from the original version forever.
    */
  private[runtime] def cursorOf(req: Request[IO]): Option[Cursor] =
    signalsOf(req)
      .flatMap(_.downField(CursorSignal).as(using cursorDecoder).toOption)
      .orElse(cursorFromQuery(req))

  private def cursorFromQuery(req: Request[IO]): Option[Cursor] = {
    val p = req.uri.query.params
    for {
      hash <- p.get(cursorParam(HeadHashSignal))
      styleHash <- p.get(cursorParam(StyleHashSignal))
      logId <- p.get(cursorParam(LogIdSignal))
      version <- p.get(cursorParam(StoreVersionSignal)).flatMap(_.toLongOption)
    } yield Cursor(hash, styleHash, logId, version)
  }

  private[runtime] def cursorParam(field: String): String =
    s"$CursorSignal.$field"

  /** A reconnect, not a first connect: the document carries its cursor in the
    * stream URL's params and the opening frame first sets [[CursorSignal]], so
    * a store holding it is a page this server already streamed to. Emptiness is
    * no test: a first connect's store carries `conn`.
    */
  private[runtime] def isReconnect(req: Request[IO]): Boolean =
    signalsOf(req).exists(_.downField(CursorSignal).succeeded)

  /** A reconnect's cursor (signals) may hold only part of version V — one
    * version can produce several batches — so it resumes from V. A document's
    * (query params) was rendered from one snapshot and has all of V, so it
    * resumes from V + 1.
    */
  private[runtime] def resumeFrom(req: Request[IO], c: Cursor): Long =
    if (isReconnect(req)) c.version else c.version + 1

  /** Signals first, as in [[cursorOf]]. */
  private[runtime] def connOf(req: Request[IO]): Option[String] =
    signalsOf(req)
      .flatMap(_.get[String](ConnSignal).toOption)
      .orElse(req.uri.query.params.get(ConnSignal))
      .filter(_.nonEmpty)

  private def signalsOf(req: Request[IO]): Option[io.circe.ACursor] =
    req.uri.query.params
      .get("datastar")
      .flatMap(io.circe.parser.parse(_).toOption)
      .map(_.hcursor)

  /** Sent on connect and on a swap, where the hashes and log id can change;
    * live batches send [[versionSignal]] alone.
    */
  private[runtime] def cursorJson(
      renderer: Renderer,
      logId: String,
      version: Long
  ): Json =
    Json.obj(
      CursorSignal -> Json.obj(
        HeadHashSignal -> Json.fromString(renderer.headHash),
        StyleHashSignal -> Json.fromString(renderer.styleHash),
        LogIdSignal -> Json.fromString(logId),
        StoreVersionSignal -> Json.fromLong(version)
      )
    )

  private[runtime] def cursorSignals(
      renderer: Renderer,
      logId: String,
      version: Long
  ): SseFrame =
    Datastar.patchSignals(cursorJson(renderer, logId, version).noSpaces)

  /** A connect's last event: the cursor plus what only the server may assert
    * (ADR 0025) — the node variables, total so a choice this session does not
    * hold resets to the declared value — and every ask ended. What a connect
    * does not commit never will: a dead stream leaves an ask standing, and its
    * commit is in this event or was lost with a forgotten session.
    */
  private[runtime] def openingSignals(
      renderer: Renderer,
      chosen: Map[VarKey, String],
      logId: String,
      version: Long
  ): SseFrame = {
    val committed = renderer.vars.committed(chosen)
    Datastar.patchSignals(
      cursorJson(renderer, logId, version)
        .deepMerge(VarKey.signalsJson(committed))
        .deepMerge(
          Json.obj(
            committed.keys.toList
              .map(k => k.pendingSignal -> Json.fromString(""))*
          )
        )
        .noSpaces
    )
  }

  private[runtime] def versionSignal(version: Long): SseFrame =
    versionPatch(version).toSse

  /** A patch, so [[Patches.encode]] can merge it into the batch's own signal
    * frame.
    */
  private[runtime] def versionPatch(version: Long): Patch =
    Patch.Signals(
      Map(
        SignalId.derived(CursorSignal) ->
          Json.obj(StoreVersionSignal -> Json.fromLong(version))
      )
    )

  /** The tab's previous session, from `sessionStorage` (per tab, unlike a
    * cookie). A retirement notice, never an identity to adopt, so two tabs
    * never share a session.
    */
  private[runtime] def prevConnOf(req: Request[IO]): Option[String] =
    req.uri.query.params.get(PrevConnParam).filter(_.nonEmpty)

  /** Everything a reconnect tells the server; anything not named here is
    * invisible to it. The default exclude is neutralised (`(?!)`) because
    * include and exclude are ANDed.
    *
    * '''Declared before [[SseRetry]], which reads it''': a forward `val`
    * reference reads `null`. That shipped as `include:'null'`, and no reconnect
    * carried a cursor, `conn` or choice. [[cursorAnomaly]] cannot see it (an
    * empty store looks like a first connect); `ServerRoutesSuite` asserts the
    * served page.
    *
    * A committed node variable rides it, its pending ask does not: a session
    * this process forgot adopts the choices from it ([[carriedVars]]).
    */
  private[runtime] val SseInclude: String =
    s"^($ConnSignal$$|$CursorSignal\\.|${VarKey.SignalPrefix}(?!.*__pending$$))"

  /** `always`: the pinned bundle's default retries a dropped connection but not
    * a completed 200, and this stream should never end. A deleted slug's
    * non-200 then runs the retries out and shows the banner.
    */
  val SseRetry: String =
    s"{retry:'always',filterSignals:{include:'$SseInclude',exclude:'(?!)'}}"

  /** `OutputStreamWriter`'s encoder flushes every 8 kB anyway. */
  private[runtime] val PageChunkBytes = 8192

  /** Ingress (nginx) and remote hops close a connection quiet for a minute, and
    * a reconnect costs 30-60x the ~15-byte comment. A comment never reaches
    * Datastar. The cursor rides it only when the position moved (see
    * [[sseStream]]). Sent to LAN connections too (TODO2.md).
    */
  val KeepAliveInterval: FiniteDuration = 25.seconds

  /** How long a never-adopted session waits for its stream: a parse and a round
    * trip, and shorter than [[LingerWindow]] because an abandoned load (a burst
    * of reloads) is the common case.
    */
  val AdoptionWindow: FiniteDuration = 10.seconds

  /** How long a returning client is told only what moved: a phone waking, a
    * lid, a wifi handover. Too short costs a fatter first patch, and a tap in
    * that window is refused until the stream is back (ADR 0024).
    */
  val LingerWindow: FiniteDuration = 2.minutes

  /** A parameter so a suite can watch a reap without waiting out the real
    * windows.
    */
  final case class SessionWindows(
      adoption: FiniteDuration,
      linger: FiniteDuration
  )
  object SessionWindows {
    val default: SessionWindows = SessionWindows(AdoptionWindow, LingerWindow)
  }

  private[runtime] val keepAliveComment: SseFrame =
    SseFrame.comment("keepalive")

  // Backslash first, or its own escapes are escaped.
  private[runtime] def escapeJsString(s: String): String =
    s.replace("\\", "\\\\").replace("'", "\\'")

  // Ampersand first, or the entities are double-escaped.
  def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  /** What a write to node variables reaches on one viewer's screen: the
    * declarers shown, the picks and sets it moves, and the readers.
    */
  final case class Reach(
      declarersShown: Set[VarKey],
      groups: List[NodeId],
      targets: List[NodeId]
  )

  /** `tapMod.post`'s body: the page's `conn`, which only a choice reads, and
    * what the press posts and saw.
    */
  final case class CallBody(
      conn: Option[String],
      posted: JsonObject,
      seen: JsonObject
  )

  def callBody(body: String): Either[String, CallBody] =
    if (body.isBlank)
      Right(CallBody(None, JsonObject.empty, JsonObject.empty))
    else
      io.circe.parser
        .parse(body)
        .toOption
        .flatMap(_.asObject)
        .toRight("the call's body is not a JSON object")
        .flatMap { o =>
          def part(k: String) = o(k).fold(Right(JsonObject.empty))(
            _.asObject.toRight(s"the call's $k is not an object")
          )
          val c = Json.fromJsonObject(o).hcursor
          (part("posted"), part("seen")).mapN(
            CallBody(
              c.get[String](ConnSignal).toOption.filter(_.nonEmpty),
              _,
              _
            )
          )
        }

  /** Each posted value decoded as the source reading it declares, exactly the
    * keys the call reads: the one client input that reaches HA.
    */
  def postedValues(
      decl: CallDecl,
      posted: JsonObject
  ): Either[String, Map[String, Any]] = {
    val declared = decl.all
      .flatMap(_.all)
      .map(_.source)
      .collect { case Value.Posted(k, t) => k -> t }
      .toMap
    def as(t: ValueType, j: Json): Option[Any] = t match
      case ValueType.Int      => j.asNumber.flatMap(_.toLong)
      case ValueType.Real     => j.asNumber.map(_.toDouble)
      case ValueType.Bool     => j.asBoolean
      case ValueType.List(of) =>
        j.asArray.flatMap(_.toList.traverse(as(of, _)))
      case _ => j.asString
    posted.keys.find(!declared.contains(_)) match
      case Some(k) =>
        Left(s"the press posted '$k', which the call does not read")
      case None =>
        declared.toList
          .sortBy(_._1)
          .traverse { (k, t) =>
            posted(k)
              .toRight(s"the press posted no '$k'")
              .flatMap(j =>
                as(t, j)
                  .toRight(s"the posted '$k' is not ${t.name}: ${j.noSpaces}")
              )
              .map(k -> _)
          }
          .map(_.toMap)
  }

  /** Why the press is refused, if the page showed a field other than it is now:
    * the fields it posts must be exactly the ones the node sees.
    */
  def seenRefusal(
      now: List[(CallField, SlotValue)],
      seen: JsonObject
  ): Option[String] = {
    val shown: Map[String, Json] =
      seen.toList.flatMap {
        case ("data", d) =>
          d.asObject.toList
            .flatMap(_.toList)
            .map((k, v) => CallField.Data(k).name -> v)
        case (k, v) => List(k -> v)
      }.toMap
    val expected = now.map((f, v) => f.name -> v).toMap
    shown.keys.toList.sorted.find(!expected.contains(_)) match
      case Some(k) => Some(s"$k: not something this control shows")
      case None    =>
        now.collectFirst {
          case (f, _) if !shown.contains(f.name) =>
            s"${f.name}: not shown"
          case (f, v) if shown(f.name) != SlotValue.json(v) =>
            s"${f.name}: ${shown(f.name).noSpaces} shown, ${SlotValue.json(v).noSpaces} now"
        }
  }

  /** A field's value as HA takes it. */
  def json(v: Any): Json = v match
    case s: String       => Json.fromString(s)
    case b: Boolean      => Json.fromBoolean(b)
    case n: Long         => Json.fromLong(n)
    case n: Int          => Json.fromInt(n)
    case d: Double       => Json.fromDoubleOrString(d)
    case xs: Iterable[?] => Json.fromValues(xs.map(json))
    case other           => Json.fromString(Steps.text(other))
}
