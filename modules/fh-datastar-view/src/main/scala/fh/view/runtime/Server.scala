package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import scala.util.chaining.*
import cats.data.{NonEmptyList, OptionT}
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
  Site,
  SystemPkl
}
import fh.view.FHError
import fh.view.auth.{AuthGate, Requirement}
import fh.view.history.{ChartRenderer, History, SeriesSource}
import fh.view.query.{QueryIdentity, QueryResolver, QuerySnapshot}
import fh.view.model.{
  ChromeColors,
  Dashboard,
  DomId,
  NodeId,
  Permission,
  SignalId,
  SlotRead,
  Transform
}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.{Decoder, Json}
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
    // Parameters so a suite can watch a reap without waiting out the real
    // windows.
    adoptionWindow: FiniteDuration = Server.AdoptionWindow,
    lingerWindow: FiniteDuration = Server.LingerWindow,
    tracer: Tracer[IO] = Tracer.noop,
    loggerFactory: LoggerFactory[IO] = Logging.console,
    meters: Meters = Meters.noop,
    queries: Option[QueryResolver] = None
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

    // `domain` is the service's, not always the entity's
    // (`homeassistant.toggle` on a `light`).
    case req @ POST -> Root / "sse" / "action" / slug / domain / service / entityId =>
      gate.handleRequirement(req, Requirement.FromDashboard(Some(slug)))(
        actionResponse(req, slug, entityId)(
          callService(domain, service, entityId, Json.obj(), req)
        )
      )

    case req @ POST -> Root / "sse" / "action" / slug / domain / service / entityId / dataKey / dataValue =>
      gate.handleRequirement(req, Requirement.FromDashboard(Some(slug)))(
        actionResponse(req, slug, entityId)(
          callService(
            domain,
            service,
            entityId,
            Json.obj(dataKey -> Server.parseValue(dataValue)),
            req
          )
        )
      )

    // The slug is what the rule checks (ADR 0023), and the only way to
    // re-establish a `conn` this process forgot ([[withSession]]).
    case req @ POST -> Root / "sse" / "surface" / slug / "open" / id =>
      withSession(req, slug)((session, renderer, uiState) =>
        openSurface(session, renderer, id, uiState)
      )

    case req @ POST -> Root / "sse" / "popup" / slug / "close" =>
      withSession(req, slug)((session, renderer, uiState) =>
        swapHost(session, renderer, Dashboard.PopupHostId, None, uiState)
      )

    // Addressed to the node that declared the variable (issue #209).
    case req @ POST -> Root / "sse" / "var" / slug / node / name / value =>
      withSession(req, slug)((session, renderer, uiState) =>
        setVar(session, renderer, NodeId.derived(node), name, value, uiState)
      )
  }

  /** An action may only touch an entity its own dashboard names (issue #89);
    * otherwise anyone admitted to a `Public` dashboard could drive the front
    * door lock by editing the URL. Decided from the static index, which is
    * sound because a candidate list is static even though membership is live
    * (ADR 0003). A missing or failed dashboard permits nothing.
    */
  private def actionResponse(req: Request[IO], slug: String, entityId: String)(
      handler: IO[Response[IO]]
  ): IO[Response[IO]] =
    (site.permissionFor(Some(slug)), gate.of(req)).flatMapN {
      (permission, user) =>
        if (permission.mayAct(user, entityId)) handler
        else
          actionRefused(req, s"$entityId is not on this dashboard")
    }

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
    * A change landing in the reload `switchMap` window may be dropped: every
    * connection repaints on reload ([[reloadRepaints]]).
    */
  private def publisherFor(
      slug: String,
      live: Server.LiveSlug
  ): Stream[IO, Nothing] =
    live.renderer.discrete.zipWithIndex.switchMap {
      case (Server.RendererState.Failed(_), _) =>
        // No renderer to diff against. The return to `Ready` rotates the log
        // (arm > 0), so every old cursor repaints.
        Stream.empty
      case (Server.RendererState.Ready(renderer), arm) =>
        // A fresh log identity per swap: an old cursor names versions this log
        // never had (ADR 0011). Not on the first arm: `discrete` emits the
        // current renderer at once, and rotating there refuses the cursor of a
        // page served in that window.
        Stream.exec(
          IO.whenA(arm > 0)(Server.freshLog.flatMap(live.log.set))
        ) ++
          stateStore.changes.evalMap(
            // Ring AFTER the write, or a woken session reads a log that does
            // not yet hold the version it was told about, and skips past it.
            recordFrame(slug, renderer, live.log, _).flatMap(live.doorbell.set)
          )
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
    * recorded with that client's surfaces in view.
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
      Server.RendererState.Ready(Renderer.fromValidated(validated))
    )

  /** The shell around [[Patches.plan]] + [[Patches.record]]. No `uiState`
    * reaches it, so a slug pays one selection pass per frame however many
    * viewers it has.
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
      changes: List[StateChange]
  ): IO[Long] =
    (stateStore.current, sessions.openSets(slug), sessions.floor(slug))
      .flatMapN { (store, opens, floor) =>
        val before = Patches.beforeSnapshot(store.entities, changes)
        // Before the gate and for every group: the graph tracks the state
        // stream, not who is watching, and the next page renders from it.
        IO(renderer.members.syncMembers(changes, before, store.entities))
          .flatMap { membership =>
            if (opens.isEmpty)
              log.update(_.skipped(store.version)).as(store.version)
            else {
              // Only surfaces some client can see: a tab inside a hidden `If`
              // is selected but on no screen. Filtered per session before the
              // union, because a chain is one client's.
              val visible = opens
                .flatMap(o =>
                  o.filter(
                    renderer.surfaces.visibleSurface(_, o, store.entities)
                  )
                )
                .toSet
              val req = Patches.plan(
                renderer,
                store.entities,
                before,
                membership,
                store.version,
                changes,
                visible
              )
              // Written and pruned in one update, so a concurrent write cannot
              // be lost between them.
              log
                .update(l =>
                  floor.foldLeft(Patches.record(renderer, l, req))(_.pruned(_))
                )
                .as(store.version)
            }
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

  /** The per-connection stream: [[openingPatches]], then what this session
    * pulls off the doorbell ([[pull]]), merged with its control channel, reload
    * repaints, HA health and a heartbeat.
    */
  private def sseStream(
      slug: String,
      req: Request[IO],
      allowed: Stream[IO, Boolean]
  ): IO[Response[IO]] =
    val uiState = Server.uiStateOf(req)
    for {
      // `None`: a bookmarked SSE URL; every document names its own.
      named = Server.connOf(req)
      conn <- named.fold(IO.randomUUID.map(_.toString))(IO.pure)
      // A reload mints a fresh `conn`, so without this the replaced session
      // lingers holding an old `position`, and the floor is the lowest
      // position: a few reloads keep the log unprunable for minutes.
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
      // The popup it still has open included, so a reconnect does not orphan
      // the dialog on screen.
      _ <- rendererOpt.traverse_ { r =>
        warnAnomalies(r, uiState) *>
          session.open.set(
            r.surfaces.selectedSurfaces(uiState)
          )
      }
      healthPatch = (h: Boolean) =>
        Datastar.patchSignals(s"""{"${Server.HaDownSignal}":${!h}}""")

      control = Stream.fromQueueUnterminated(session.control)
      reloads = reloadRepaints(session, uiState, rendererOpt)
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
          (session.position.get, session.told.get).flatMapN {
            (position, sent) =>
              if (position == sent) IO.pure(Server.keepAliveComment)
              else
                session.told.set(position).as(Server.versionSignal(position))
          }
        )

      // `discrete` coalescing is wanted: versions landing while this session
      // renders collapse into one pull, which is what a slow client should get.
      live = Stream
        .eval(liveOpt.traverse(l => session.open.get.map(l -> _)))
        .flatMap {
          case None            => Stream.empty
          case Some((l, open)) =>
            Stream
              .eval(openingPatches(slug, l, session, req, uiState, open))
              .flatMap(Stream.emits) ++
              l.doorbell.discrete
                .evalMap(pull(l, session, _))
                .flatMap(Stream.emits)
                .merge(control)
                .merge(reloads)
                .merge(haDown)
                .merge(keepAlive)
        }

      // Bracketed to the stream, not done in the handler: a handler that
      // registers and never reaches a body (it raised, or ember dropped the
      // response) would leak the session into every frame's `openSets`.
      //
      // Release hands the session to the linger ([[Session.release]]), and only
      // while this stream still owns it: a displaced stream releases after its
      // successor took over.
      stream = Stream.bracket(sessions.register(conn, session))(_ =>
        session
          .release(epoch)
          .flatMap(_.traverse_(reapAfter(conn, session, _, lingerWindow)))
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
    */
  private[runtime] def pull(
      live: Server.LiveSlug,
      session: Session,
      version: Long
  ): IO[List[SseFrame]] =
    session.position.get.flatMap { position =>
      if (version <= position) IO.pure(Nil)
      else
        (
          OptionT(live.renderer.get.map(_.rendererOf)),
          OptionT.liftF(live.log.get),
          OptionT.liftF(stateStore.current),
          OptionT.liftF(session.holds.get),
          OptionT.liftF(session.open.get)
        ).flatMapN { (renderer, log, store, holds, open) =>
          envOf(session, renderer)
            .flatMap(env =>
              Patches.resume(
                renderer,
                live.cache,
                log,
                holds,
                store.entities,
                answer(renderer, _, env),
                env,
                position + 1,
                open,
                // Live, not the arriving selection: a tab select moves it.
                renderer.surfaces.uiStateFrom(open)
              )
            )
            .flatMap { patches =>
              session.holds
                .update(
                  patches.foldLeft(_)(Patches.applied(renderer.ancestry, _, _))
                ) *>
                // The cursor rides LAST, which makes it an ack: a client
                // echoing it applied what came before. A silent frame announces
                // nothing, so `told` does not move for it; the keepalive
                // carries the cursor instead ([[Session.position]]).
                IO.whenA(patches.nonEmpty)(session.told.set(version)) *>
                session.position
                  .set(version)
                  .as(
                    if (patches.isEmpty) Nil
                    else
                      // Through `encode`, so it merges with a trailing signal
                      // frame and stays a separate event after an element one.
                      Patches.encode(
                        patches :+
                          Addressed(Server.versionPatch(version))
                      )
                  )
            }
            .pipe(OptionT.liftF)
        }.value
          .map(_.getOrElse(Nil))
    }

  /** A reconnect's cursor (signals) may hold only part of version V — one
    * version can produce several batches — so it resumes from V. A document's
    * (query params) was rendered from one snapshot and has all of V, so it
    * resumes from V + 1.
    */
  private def resumeFrom(req: Request[IO], c: Server.Cursor): Long =
    if (Server.hasSignals(req)) c.version else c.version + 1

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
      session: Session,
      req: Request[IO],
      uiState: Map[String, String],
      open: Set[String]
  ): IO[List[SseFrame]] =
    (
      OptionT.liftF(live.doorbell.get),
      OptionT(live.renderer.get.map(_.rendererOf)),
      OptionT.liftF(live.log.get),
      OptionT.liftF(stateStore.current),
      OptionT.liftF(session.holds.get),
      OptionT.liftF(session.told.get)
    )
      .flatMapN { (covered, renderer, log, store, holds, told) =>
        val cursor = Server.cursorOf(req)
        if (cursor.exists(_.headHash != renderer.headHash))
          OptionT.pure[IO](List(Server.reloadPatch))
        else
          OptionT
            .liftF(envOf(session, renderer))
            .flatMap { env =>
              val head =
                if (cursor.exists(_.styleHash != renderer.styleHash))
                  Server.headPatches(renderer, slug)
                else Nil
              // `Patches.resume` is total, so only global reasons repaint: no
              // cursor, another log's (restart or swap), one ahead of the store
              // (rewound counter), one from before a gap nobody watched
              // ([[FragmentLog.reaches]]) — or one behind `told`. `holds`
              // records what was SENT; a cursor rides last in its batch, so an
              // echo behind `told` means claimed bytes were lost.
              val resumedIO = cursor
                .filter(c =>
                  c.logId == log.id && c.version <= store.version &&
                    log.reaches(c.version) && c.version >= told
                )
                .traverse(c =>
                  Patches.resume(
                    renderer,
                    live.cache,
                    log,
                    holds,
                    store.entities,
                    answer(renderer, _, env),
                    env,
                    resumeFrom(req, c),
                    open,
                    uiState
                  )
                )
              // Traced, so the repaint claims what it painted; clearing `holds`
              // instead would re-send the open surfaces on the next pull.
              val painted =
                pageAnswers(renderer, open, store.entities, env).map(
                  renderer.renderBodyTraced(store.entities, uiState, _)
                )
              // A popup whose surface this dashboard no longer has is in no
              // open set and would sit on screen forever.
              val orphan = Option
                .when(
                  uiState.get(Dashboard.PopupHostId).exists(_.nonEmpty) &&
                    renderer.surfaces.openPopup(uiState).isEmpty
                )(
                  Datastar.patch(
                    s"""<div id="${Dashboard.PopupHostId}"></div>""",
                    PatchMode.Outer,
                    None
                  )
                )
                .toList
              val result = (resumedIO, session.vars.get).tupled.flatMap {
                (resumed, chosen) =>
                  val claim = resumed.fold(store.version)(_ => covered)
                  val record = resumed.fold(
                    painted.flatMap(p =>
                      session.holds
                        .set(p.own.map { case (id, n) =>
                          id -> Held(Some(n.digest), n.signals)
                        })
                        .as(
                          List(
                            Datastar
                              .patch(
                                p.html,
                                PatchMode.Inner,
                                Some("#dashboard")
                              )
                          )
                        )
                    )
                  )(patches =>
                    session.holds
                      .update(
                        patches
                          .foldLeft(_)(Patches.applied(renderer.ancestry, _, _))
                      )
                      .as(patches.map(_.patch.toSse))
                  ) <* session.position.set(claim) <* session.told.set(claim)
                  record.map(sent =>
                    head ++ sent ++ orphan :+
                      // With the selections: a stream that died between a
                      // swap's patch and its signal left them disagreeing.
                      Server
                        .openingSignals(renderer, open, chosen, log.id, claim)
                  )
              }

              OptionT.liftF(result)
            }
      }
      .value
      // A failed slug: the error page is a reload away.
      .map(_.getOrElse(List(Server.reloadPatch)))

  /** Repaint this connection's body on every renderer swap, or reload the page
    * when the head's unpatchable part moved ([[Renderer.headHash]]): a morph
    * leaves old stylesheets and scripts in place.
    */
  private def reloadRepaints(
      session: Session,
      uiState: Map[String, String],
      // The renderer the handler read. Seeded, not taken from the
      // subscription: this merges after the opening block and `discrete` hands
      // a late subscriber only the current value, so a swap in between would
      // look like no change and leave the client on a dead dashboard.
      served: Option[Renderer]
  ): Stream[IO, SseFrame] =
    Stream
      .eval(liveFor(session.slug))
      .unNone
      .flatMap { live =>
        (Stream.emit(served) ++ live.renderer.discrete.map(
          _.rendererOf
        )).zipWithPrevious
          .drop(1)
          .collect { case (Some(previous), current) => (previous, current) }
          .filterNot(Server.sameRenderer)
          .evalMap {
            // To or from the error page, which has no `#dashboard`.
            case (_, None) | (None, _) =>
              IO.pure(List(Server.reloadPatch))
            case (Some(prev), Some(r)) if prev.headHash != r.headHash =>
              IO.pure(List(Server.reloadPatch))
            case (Some(prev), Some(r)) =>
              (session.open.set(r.surfaces.selectedSurfaces(uiState)) *>
                (stateStore.current, live.log.get).tupled)
                .flatMap { case (store, log) =>
                  pageSnapshot(
                    session,
                    r,
                    r.surfaces.selectedSurfaces(uiState),
                    store.entities
                  ).flatMap { fragments =>
                    val head =
                      if (prev.styleHash != r.styleHash)
                        Server.headPatches(r, session.slug)
                      else Nil
                    // Traced and claimed as in [[openingPatches]]. `told` too, or
                    // the keepalive announces a lower version than the swap did.
                    val painted =
                      r.renderBodyTraced(store.entities, uiState, fragments)
                    session.holds.set(painted.own.map { case (id, p) =>
                      id -> Held(Some(p.digest), p.signals)
                    }) *>
                      session.position.set(store.version) *>
                      session.told
                        .set(store.version)
                        .as(
                          head ++ List(
                            Datastar.patch(
                              painted.html,
                              PatchMode.Inner,
                              Some("#dashboard")
                            ),
                            // A swap rotated the log id; without this a
                            // reconnect quotes a dead log and repaints.
                            Server.cursorSignals(r, log.id, store.version)
                          )
                        )
                  }
                }
          }
          .flatMap(Stream.emits)
      }

  /** An unknown surface is a stale document, not a bad request: ids are
    * location-derived, so an edit above a card renames it. Raised, so the tap
    * says so instead of doing nothing (ADR 0024).
    */
  private def openSurface(
      session: Session,
      renderer: Renderer,
      id: String,
      uiState: Map[String, String]
  ): IO[Unit] =
    renderer.surface(id) match {
      case None =>
        IO.raiseError(
          FHError.notFound(s"no surface '$id' on this dashboard — reload")
        )
      case Some(surf) =>
        swapHost(session, renderer, surf.hostId, Some(id), uiState)
    }

  /** The one open/switch/close primitive: make `newSurface` the sole occupant
    * of `host` (`None` closes a popup) and patch the DOM. No server state
    * tracks whether a popup is open.
    *
    * One client's choice, so unlike a state-group flip it records no
    * [[Mutation]]: the trace goes to this session alone. Ends by committing
    * `ui_<hostId>`; the tap only wrote a pending value (ADR 0025).
    */
  private def swapHost(
      session: Session,
      renderer: Renderer,
      host: DomId,
      newSurface: Option[String],
      uiState: Map[String, String]
  ): IO[Unit] =
    for {
      _ <- session.open.update { open =>
        val evict = open.filter(sid =>
          !newSurface.contains(sid) &&
            renderer.surface(sid).exists(_.hostId == host)
        )
        (open -- evict) ++ newSurface.toSet
      }
      store <- stateStore.current
      states = store.entities
      // Only the arriving surface's queries, so an unopened popup costs
      // nothing.
      env <- envOf(session, renderer)
      fragments <- resolveQueries(renderer, newSurface, states, uiState, env)
      filled =
        Patches.hostFill(renderer, host, newSurface, states, uiState, fragments)
      _ <- filled match {
        case Some((patch, html)) =>
          session.holds.update(Patches.applied(renderer.ancestry, _, patch)) *>
            session.control.offer(
              Datastar.patch(html, PatchMode.Inner, Some("#" + host))
            )
        // Only a close reaches here: [[openSurface]] 404s an unknown surface.
        case None =>
          session.holds.update(
            _ -- Patches.hostEvicts(renderer, host)
          ) *> IO.whenA(newSurface.isEmpty)(
            session.control.offer(
              Datastar.patch(
                s"""<div id="$host"></div>""",
                PatchMode.Outer,
                None
              )
            )
          )
      }
      // Committed here only, so a tap that never got this far cannot leave the
      // URL claiming a panel the DOM does not have (ADR 0025).
      _ <- renderer.surfaces
        .committedSelection(host, newSurface)
        .traverse_ { case (id, value) =>
          session.control.offer(
            Datastar.patchSignals(
              Json
                .obj(
                  Server.UiSignalPrefix + id -> Json.fromString(value)
                )
                .noSpaces
            )
          )
        }
    } yield ()

  private def resolveQueries(
      renderer: Renderer,
      arriving: Option[String],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      env: VarEnv
  ): IO[QuerySnapshot] =
    answer(
      renderer,
      arriving.toList.flatMap(
        renderer.queriesForSurface(_, states, uiState, env)
      ),
      env
    )

  private def pageSnapshot(
      session: Session,
      renderer: Renderer,
      open: Set[String],
      states: Map[String, EntityState]
  ): IO[QuerySnapshot] =
    envOf(session, renderer).flatMap(pageAnswers(renderer, open, states, _))

  private def pageAnswers(
      renderer: Renderer,
      open: Set[String],
      states: Map[String, EntityState],
      env: VarEnv
  ): IO[QuerySnapshot] =
    answer(renderer, renderer.queriesForPage(open, states, env), env)

  /** Set a node variable for this viewer and re-render the readers it is shown
    * (issue #209). Per session, like a tab switch: no `Mutation`.
    *
    * The untrusted value is checked ([[Renderer.refusals]]) inside the
    * `modify`, so two choices landing together cannot drop one. Committed last
    * even when no bytes moved: the commit ends the pending ask (ADR 0025).
    */
  private def setVar(
      session: Session,
      renderer: Renderer,
      declarer: NodeId,
      name: String,
      value: String,
      uiState: Map[String, String]
  ): IO[Unit] = {
    val readers = renderer.readersOf(declarer, name)
    for {
      store <- stateStore.current
      open <- session.open.get
      shown = renderer.surfaces.visibleNode(_, open, store.entities)
      _ <- IO.raiseWhen(readers.isEmpty || !shown(declarer))(
        FHError.notFound(
          s"no node this viewer is shown reads the variable '$name' declared on '$declarer'"
        )
      )
      proposed <- session.vars
        .modify { current =>
          val proposed = current + ((declarer, name) -> value)
          renderer.refusals(proposed) match {
            case Nil     => (proposed, Right(proposed))
            case refused => (current, Left(refused))
          }
        }
        .flatMap(
          _.leftMap(r => FHError.badCondition(r.mkString("; "))).liftTo[IO]
        )
      env = renderer.varEnv(proposed)
      targets = readers.filter(shown)
      snapshot <- answer(
        renderer,
        renderer.readsForPull(targets, Nil, store.entities, uiState, env),
        env
      )
      holds <- session.holds.get
      live <- liveFor(session.slug)
      patches <- live.toList.flatTraverse(l =>
        targets.traverseFilter(
          Patches.morph(
            renderer,
            l.cache,
            holds,
            store.entities,
            uiState,
            snapshot,
            _
          )
        )
      )
      _ <- session.holds.update(
        patches.foldLeft(_)(Patches.applied(renderer.ancestry, _, _))
      )
      _ <- patches.traverse_(p => session.control.offer(p.patch.toSse))
      _ <- session.control.offer(
        Datastar.patchSignals(
          Server.varJson(Map((declarer, name) -> value)).noSpaces
        )
      )
    } yield ()
  }

  // From the SESSION, not the request: a live pull has no request.
  private def envOf(session: Session, renderer: Renderer): IO[VarEnv] =
    session.vars.get.map(renderer.varEnv)

  /** With no resolver wired only a render that reads a query raises, so a
    * dashboard without charts still works.
    */
  private def answer(
      renderer: Renderer,
      wanted: List[SlotRead],
      env: VarEnv
  ): IO[QuerySnapshot] =
    (queries, wanted) match {
      case (Some(resolver), qs) if qs.nonEmpty =>
        QuerySnapshot
          .resolve(
            resolver,
            renderer.queryRequests,
            qs,
            env,
            QueryIdentity.Instance
          )
      // Keep the env: `QuerySnapshot.empty` would resolve declared values.
      case _ => IO.pure(QuerySnapshot.of(Map.empty, env))
    }

  /** Resolve `conn` (from the signals body) to its session and renderer, and
    * run `f`. Only success is NoContent; every refusal is [[actionRefused]]
    * (ADR 0024).
    */
  private def withSession(
      req: Request[IO],
      slug: String
  )(
      f: (Session, Renderer, Map[String, String]) => IO[Unit]
  ): IO[Response[IO]] = {
    req.bodyText.compile.string
      .map(io.circe.parser.parse(_).toOption.flatMap { body =>
        connOf(body).map(_ -> Server.uiFromSignals(body.hcursor))
      })
      .flatMap {
        case None =>
          actionRefused(req, "missing conn")
        case Some((conn, uiState)) =>
          gate.handleRequirement(req, Requirement.FromDashboard(Some(slug)))(
            rendererFor(slug)
              .flatMap {
                case None =>
                  IO.raiseError(
                    FHError.notFound(s"no dashboard '$slug' is being served")
                  )
                case Some(renderer) =>
                  sessionFor(slug, conn, renderer, uiState).flatMap {
                    case None => actionRefused(req, Server.WrongSlugMessage)
                    case Some(session) =>
                      f(session, renderer, uiState) *> NoContent()
                  }
              }
              // A 4xx becomes a refusal; a 5xx is our bug and stays with
              // [[FHError.handle]] rather than showing the user an internal
              // message.
              .recoverWith {
                case e: FHError if e.status < 500 =>
                  actionRefused(req, e.message)
              }
          )
      }
  }

  /** Mints a session when `conn` names nothing (an idle page whose session was
    * reaped); its patches queue in `control` until the reconnecting stream
    * adopts it. `None`: `conn` is another dashboard's, and re-registering it
    * would unroute that page.
    */
  private def sessionFor(
      slug: String,
      conn: String,
      renderer: Renderer,
      uiState: Map[String, String]
  ): IO[Option[Session]] =
    sessions.get(conn).flatMap {
      case Some(session) => IO.pure(Option.when(session.slug == slug)(session))
      case None          =>
        Session
          .create(slug)
          .flatTap(_.open.set(renderer.surfaces.selectedSurfaces(uiState)))
          .flatTap(sessions.register(conn, _))
          .flatTap(reapAfter(conn, _, Tenure.Fresh, adoptionWindow))
          .map(Some(_))
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
        case None => Session.create(slug).flatTap(_.adopt).map(_ -> 1)
      }

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

  private def warnAnomalies(
      renderer: Renderer,
      uiState: Map[String, String]
  ): IO[Unit] =
    renderer.surfaces
      .uiStateAnomalies(uiState)
      .traverse_(w => logger.warn(w))

  private def callService(
      domain: String,
      service: String,
      entityId: String,
      serviceData: Json,
      req: Request[IO]
  ): IO[Response[IO]] =
    actions
      .call(req, domain, service, entityId, serviceData)
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
    * control's `_<node>__error`, clear its group's `_<group>__pending` (ADR
    * 0025) and raise `_toast`. Both ids are the client's claim from the query
    * string; nothing is authorized off them.
    */
  private def actionRefused(
      req: Request[IO],
      message: String
  ): IO[Response[IO]] =
    Ok(Server.actionSignals(req, message))

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
    stateStore.snapshot.flatMap { states =>
      val entities = renderer.entitiesForNode(NodeId.derived(id))
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
              .decode(json, slug = Some(slug))
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
      .decode(json)
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
          // Narrowed to declarations: an undeclared URL key would become a
          // signal name in the opening frame.
          val choices = Server
            .varChoicesOf(req)
            .view
            .filterKeys(renderer.declarations.contains)
            .toMap
          renderer.refusals(choices) match {
            case Nil     => renderPage(slug, renderer, log, conn, req, choices)
            case refused =>
              FHError.logged(FHError.badCondition(refused.mkString("; ")))
          }
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
      choices: Map[(NodeId, String), String]
  ): IO[Response[IO]] = {
    val uiState = Server.uiStateOf(req)
    val editMode = req.uri.query.params.get("edit").contains("1")
    // Narrowed: a popup this dashboard cannot serve is not shown, so it must
    // not be seeded back either.
    val restoreUi = renderer.surfaces.openPopup(uiState) match {
      case Some(sid) => uiState.updated(Dashboard.PopupHostId, sid)
      case None      => uiState - Dashboard.PopupHostId
    }
    val open = renderer.surfaces.selectedSurfaces(uiState)
    for {
      // Read once: the banner rendered and the value recorded as told must
      // agree, or the stream repeats it or skips a real change.
      live <- healthy.get
      session <- Session
        .create(slug)
        .flatTap(_.open.set(open))
        .flatTap(_.haDown.set(Some(!live)))
      // REGISTERED BEFORE THE SNAPSHOT IS READ — see [[recordFrame]].
      _ <- sessions.register(conn, session)
      _ <- reapAfter(conn, session, Tenure.Fresh, adoptionWindow)
      store <- tracer
        .span("dashboard.page.store", Attribute("fh.slug", slug))
        .surround(stateStore.current)
      // Captured now: the walk runs when the body is pulled, after this `for`
      // returns, and would otherwise open its own trace (#75).
      parentSpan <- tracer.currentSpanContext
      // Filled by the walk; `holds` is committed in the finalizer, once the
      // bytes are out.
      ownRef <- IO.ref(Map.empty[NodeId, Painted])
      _ <- session.vars.set(choices)
      _ <- session.position.set(store.version)
      // The document carries the cursor, so it is the first announcement.
      _ <- session.told.set(store.version)
      _ <- warnAnomalies(renderer, uiState)
      restore = Server.Restore(
        restoreUi,
        conn,
        Some(
          Server.Cursor(
            renderer.headHash,
            renderer.styleHash,
            log.id,
            store.version
          )
        )
      )
      // Answered while the head goes out, so a cold chart's fetch overlaps
      // the browser fetching stylesheets (architecture §0). A
      // `CompletableFuture`, not a `Deferred`: the waiter is the synchronous
      // walk on a blocking thread. Cancelled with the body, or a walk parked
      // on it never wakes.
      pending = new java.util.concurrent.CompletableFuture[QuerySnapshot]()
      fetch <- pageSnapshot(session, renderer, open, store.entities).attempt
        .flatMap { r =>
          IO {
            val _ = r.fold(pending.completeExceptionally, pending.complete)
          }
        }
        .onCancel(IO(pending.cancel(false)).void)
        .start
      // The walk writes straight into the response body, so a render holds
      // one node rather than the page and the head arrives first. This
      // `IO.blocking` is why `ServerHarness` fetches documents with `testReal`:
      // `TestControl` cannot run `readOutputStream`'s two blocking sides.
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
            var own = Map.empty[NodeId, Painted]
            pageInto(
              Sink.streaming(w),
              slug,
              sink => {
                // Out of the buffer, or the head waits for the answers too.
                if (!pending.isDone) w.flush()
                own = renderer.renderPageInto(
                  sink,
                  store.entities,
                  uiState,
                  renderer.surfaces.openPopup(uiState),
                  Server.awaitAnswers(pending)
                )
              },
              renderer.themeColorTags,
              renderer.stylesheets.map(assets.rewrite),
              renderer.deferredStylesheets.map(assets.rewrite),
              renderer.scripts.map(assets.rewrite),
              renderer.inlineScripts,
              renderer.title,
              Server.ingressPrefixOf(req),
              restore,
              editMode,
              haDown = !live,
              committed = Server.committedVars(renderer, choices)
            )
            // Flush, not close: `readOutputStream` owns the stream.
            w.flush()
            own
          }.flatMap(own =>
            ownRef.set(own) *>
              tracer.currentSpanOrNoop.flatMap(
                _.addAttribute(Attribute("fh.nodes", own.size.toLong))
              ) *> meters.pageNodes.record(own.size.toLong)
          ).pipe(walk =>
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
            ownRef.get.flatMap(own =>
              session.holds.set(own.map { case (id, p) =>
                id -> Held(Some(p.digest), p.signals)
              })
            )
          case Resource.ExitCase.Errored(e) =>
            logger.warn(e)(s"page render for '$slug' failed mid-walk")
          case Resource.ExitCase.Canceled => IO.unit
        }
        .onFinalize(fetch.cancel)
      resp <- Ok(body)
    } yield resp.withContentType(`Content-Type`(MediaType.text.html))
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
         |  <script type="module" src="${assets.rewrite(
          Server.DatastarCdn
        )}"></script>
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
      committed: Map[(NodeId, String), String]
  ): Unit = {
    // Inline theme scripts are emitted verbatim (authored source, not user
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
    // transport freezes `haDown`, so the HA banner waits on `$_sse == 0`.
    //
    // This pinned build needs the `$` sigil even for a bare read.
    val ha = "$" + Server.HaDownSignal
    // 2 = gave up, 1 = trying, 0 = fine.
    val sseState =
      "evt.detail.type === 'retries-failed' ? 2 : " +
        "(evt.detail.type === 'retrying' || evt.detail.type === 'error') ? 1 : 0"
    // 2 latches: any later event classifies as 0, and a `finished` in the same
    // debounce window swallowed the failure before it painted. Only a reload
    // gets out, which is all that reconnects once retries are exhausted.
    val sseLatched = s"$$_sse >= 2 ? 2 : ($sseState)"
    // Inline, or the banners flash before the deferred module runs; and not
    // in CSS, because `data-show` only clears the inline `display`.
    val hidden = """style="display:none""""
    // Debounced so a visibility refetch or the outgoing page of a reload does
    // not flash "Reconnecting…". The pinned parser wants `__debounce.600ms`;
    // the docs' `.debounce_600ms` silently becomes part of the event name.
    val sseEvent = s"${Server.StreamEvent}__document__debounce.600ms"
    // The popup host is the one selection with no card template to seed it,
    // so the shell declares `ui_<hostId>` and mirrors it to the URL
    // ([[Server.UrlSyncScript]], ADR 0005).
    //
    // Escaped twice: a JS string literal inside an HTML attribute, and
    // `&#39;` alone decodes back to a bare `'`.
    val popupSignalName = Server.UiSignalPrefix + Dashboard.PopupHostId
    val popupParamName = Server.UiParamPrefix + Dashboard.PopupHostId
    val popupSeed = Server.escapeHtml(
      Server.escapeJsString(
        restore.uiState.getOrElse(Dashboard.PopupHostId, "")
      )
    )
    val varSeed = committed.toList.sorted.map { case ((declarer, name), v) =>
      s", ${Server.varSignal(declarer, name)}: '${Server.escapeHtml(Server.escapeJsString(v))}'"
    }.mkString
    val connBanner =
      s"""<div data-signals="{${Server.HaDownSignal}: $haDown, _sse: 0, ${Server.ToastSignal}: '', ${Server.ReloadSignal}: false, $popupSignalName: '$popupSeed', ${Server.ConnSignal}: '${Server
          .escapeJsString(restore.conn)}'$varSeed}"
         |     data-effect="$$${Server.ReloadSignal} && window.location.reload(); fhUrl('$popupParamName', $$$popupSignalName)"
         |     data-on-signal-patch-filter="{include:/^${Server.ToastSignal}$$/}"
         |     data-on-signal-patch="$$${Server.ToastSignal} && (fhToast($$${Server.ToastSignal}), $$${Server.ToastSignal} = '')"
         |     data-on:$sseEvent="$$_sse = $sseLatched">
         |  <div $hidden ${Server.PendingSweep}></div>
         |  <div class="fh-offline fh-offline-sse" $hidden role="status" aria-live="assertive" data-show="$$_sse > 0">
         |    <span $hidden data-show="$$_sse < 2">Reconnecting to the dashboard…</span>
         |    <span $hidden data-show="$$_sse >= 2">Dashboard connection lost. <button class="fh-offline-action" data-on:click="window.location.reload()">Reload</button></span>
         |  </div>
         |  <div class="fh-offline fh-offline-ha" $hidden role="status" aria-live="polite" data-show="$ha && $$_sse == 0">Home Assistant unavailable — reconnecting…</div>
         |</div>""".stripMargin
    val _ = out.append(s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  $themeColorTags
       |  <base href="$baseHref">
       |  <link rel="manifest" href="${PwaAssets.manifestUrl}">
       |  $pageTitle
       |  <script>${Server.UrlSyncScript}</script>
       |  <script>${Server.swRegisterCall}</script>
       |$links
       |  <script type="module" src="${assets.rewrite(
                           Server.DatastarCdn
                         )}"></script>
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
      // Not rotated on a swap: [[RenderCache]] invalidates by renderer
      // identity.
      cache: RenderCache,
      // The newest store version the log covers.
      doorbell: SignallingRef[IO, Long]
  )

  private[runtime] object LiveSlug {
    def of(state: RendererState): IO[LiveSlug] =
      SignallingRef[IO].of(state).flatMap(create)

    def create(renderer: SignallingRef[IO, RendererState]): IO[LiveSlug] =
      (
        freshLog.flatMap(Ref[IO].of),
        RenderCache.create,
        SignallingRef[IO].of(0L)
      ).mapN(LiveSlug(renderer, _, _, _))
  }

  private[runtime] val freshLog: IO[FragmentLog] =
    IO.randomUUID.map(id => FragmentLog(id.toString))

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
                Permission(r.access, r.references)
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
          .flatMap(_.traverse_(_.renderer.set(state)))
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
          entry.live.renderer.set(state)
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
              live.renderer.set(RendererState.Failed(message))
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
      RendererState.Ready(Renderer.fromValidated(validated))
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
    * empty log and the dashboard silently stops moving.
    */
  def resource(
      actions: ServiceCalls,
      stateStore: StateStore,
      renderers: Map[String, SignallingRef[IO, RendererState]],
      defaultSlug: String,
      sessions: Sessions,
      gate: AuthGate,
      assets: AssetCache = AssetCache.empty,
      healthy: Signal[IO, Boolean] = Signal.constant(true),
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
      adoptionWindow: FiniteDuration = AdoptionWindow,
      lingerWindow: FiniteDuration = LingerWindow
  ): Resource[IO, Server] =
    LiveSite
      .of(renderers, Map.empty, defaultSlug)
      .toResource
      .flatMap(
        withSite(
          actions,
          stateStore,
          _,
          sessions,
          gate,
          assets,
          healthy,
          systemPkl,
          dumpRefresh,
          adoptionWindow,
          lingerWindow
        )
      )

  /** [[resource]] against a site the caller owns, which the reload path writes.
    */
  def withSite(
      actions: ServiceCalls,
      stateStore: StateStore,
      site: LiveSite,
      sessions: Sessions,
      gate: AuthGate,
      assets: AssetCache,
      healthy: Signal[IO, Boolean],
      systemPkl: SystemPkl,
      dumpRefresh: Option[IO[DumpRefresh.Result]],
      adoptionWindow: FiniteDuration = AdoptionWindow,
      lingerWindow: FiniteDuration = LingerWindow,
      tracer: Tracer[IO] = Tracer.noop,
      loggerFactory: LoggerFactory[IO] = Logging.console,
      meters: Meters = Meters.noop,
      queries: Option[QueryResolver] = None
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
        adoptionWindow,
        lingerWindow,
        tracer,
        loggerFactory,
        meters,
        queries
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
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
      actions: HomeAssistantApi[IO] => ServiceCalls = ServiceCalls.asInstance,
      tracer: Tracer[IO] = Tracer.noop,
      loggerFactory: LoggerFactory[IO] = Logging.console,
      meters: Meters = Meters.noop
  ): Resource[IO, Server] =
    historyQueries(feed.api, loggerFactory).flatMap(queries =>
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
        tracer = tracer,
        loggerFactory = loggerFactory,
        meters = meters,
        queries = Some(queries)
      )
    )

  /** `memoizedAcquire` makes the JavaScript engine lazy: an instance with no
    * chart never pays ECharts' ~300 ms or the isolate's heap.
    */
  private def historyQueries(
      api: HomeAssistantApi[IO],
      loggerFactory: LoggerFactory[IO]
  ): Resource[IO, QueryResolver] =
    for {
      chart <- ChartRenderer.resource(loggerFactory).memoizedAcquire
      log = loggerFactory.getLoggerFromName("fh.view.history")
      history <- History
        .create(
          SeriesSource.fromApi(api),
          onFailure = (k, e) =>
            log.warn(e)(
              s"history of ${k.entityId} over ${k.window.name} could not " +
                "be fetched; its charts show their error until a retry"
            )
        )
        .toResource
      resolver <- QueryResolver
        .create(
          history,
          chart.map(_.render),
          onFailure = (k, e) =>
            log.warn(e)(
              s"${Transform.Stage.key(k.stage)} of ${k.question._2} failed; " +
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
    * `data-init` URL: **the first connect carries no signals** (`data-init`
    * fires from `<body>` before descendants' `data-signals` merge), so a
    * signals-only read renders the default tab. Signals win on a reconnect
    * ([[uiStateOf]]).
    */
  private[runtime] case class Restore(
      uiState: Map[String, String],
      conn: String,
      // Without it the first connect repaints the body the document holds.
      cursor: Option[Cursor] = None
  ) {

    /** `&amp;`: this lands in an HTML attribute. */
    def query: String = {
      val params = uiState.toList.sorted.map { case (id, v) =>
        s"$UiParamPrefix${encode(id)}=${encode(v)}"
      } ++ cursor.toList.flatMap(c =>
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

  /** Bake-group id -> selected member, from the URL's `ui.<id>` params and the
    * `ui_<id>` signals (ADR 0005). Signals win: the URL trails them. Values are
    * untrusted; [[SurfaceGraph.resolveActive]] clamps them.
    */
  def uiStateOf(req: Request[IO]): Map[String, String] =
    uiFromQuery(req) ++ signalsOf(req).fold(Map.empty)(uiFromSignals)

  // The URL, not a cookie: a cookie is per origin, and two tabs would
  // overwrite each other's selection.
  private def uiFromQuery(req: Request[IO]): Map[String, String] =
    req.uri.query.params.collect {
      case (k, v) if k.startsWith(UiParamPrefix) =>
        k.drop(UiParamPrefix.length) -> v
    }

  private[runtime] def uiFromSignals(c: io.circe.ACursor): Map[String, String] =
    c.keys.toList.flatten
      .filter(_.startsWith(UiSignalPrefix))
      .flatMap { k =>
        c.downField(k)
          .focus
          .flatMap(j => j.asString.orElse(j.asNumber.map(_.toString)))
          .map(k.drop(UiSignalPrefix.length) -> _)
      }
      .toMap

  /** Framework protocol, not authoring names: ADR 0005's "no signal-name
    * literals in the backend" is about `tab_`/`_val_`.
    */
  val UiParamPrefix: String = "ui."
  val UiSignalPrefix: String = "ui_"

  /** `v.<declarer>.<name>`, outside `ui.` (bake selections). URL only: a choice
    * enters through the route and lives on the session.
    */
  val VarParamPrefix: String = "v."

  /** Keyed by declarer, so two choosers do not move each other. `_`-prefixed so
    * it never rides a request; its pending twin matches `PendingSweep`.
    */
  private[runtime] def varGroupId(declarer: NodeId, name: String): String =
    s"var_${declarer}__$name"

  private[runtime] def varSignal(declarer: NodeId, name: String): String =
    "_" + varGroupId(declarer, name)

  /** Total over declarations, so a control never shows the declared value over
    * a choice.
    */
  private[runtime] def committedVars(
      renderer: Renderer,
      chosen: Map[(NodeId, String), String]
  ): Map[(NodeId, String), String] =
    renderer.declarations.map { case (key, declared) =>
      key -> chosen.getOrElse(key, declared)
    }

  private[runtime] def varJson(
      values: Map[(NodeId, String), String]
  ): Json =
    Json.obj(values.toList.map { case ((declarer, name), value) =>
      varSignal(declarer, name) -> Json.fromString(value)
    }*)

  /** Untrusted and not narrowed; the caller narrows to declarations. */
  def varChoicesOf(req: Request[IO]): Map[(NodeId, String), String] =
    req.uri.query.params.toList
      .collect {
        case (k, v) if k.startsWith(VarParamPrefix) =>
          k.drop(VarParamPrefix.length).split('.').toList match {
            case node :: name :: Nil if node.nonEmpty && name.nonEmpty =>
              Some((NodeId.derived(node), name) -> v)
            case _ => None
          }
      }
      .flatten
      .toMap

  private[runtime] def awaitAnswers(
      pending: java.util.concurrent.CompletableFuture[QuerySnapshot]
  ): QuerySnapshot =
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

  /** What an action says about itself: the pressed control and the waiting
    * selection group, filled at click time (`core/tap.pkl`).
    */
  val NodeParam: String = "node"
  val GroupParam: String = "group"

  val ToastSignal: String = "_toast"

  /** Clears every pending ask when no answer can come (ADR 0025): the stream is
    * down (`_sse`), or a non-200 arrived, whose body Datastar drops unread. One
    * rule on the shell, not one per group.
    *
    * `@setAll` peeks while it writes in the pinned bundle, so this neither
    * depends on every pending signal nor re-triggers itself. Busy signals are
    * not swept: the bundle clears them in a `finally`.
    */
  val PendingSweep: String = {
    val clear = """@setAll('', {include:/__pending$/})"""
    s"""data-on-signal-patch-filter="{include:/^_sse$$/}" """ +
      s"""data-on-signal-patch="$$_sse > 0 && $clear" """ +
      s"""data-on:datastar-fetch__document="evt.detail.type === 'error' && $clear""""
  }

  /** An untrusted id that becomes a signal name. Real ids are `[A-Za-z0-9_]`,
    * so the check is exact.
    */
  private val IdClaim = "[A-Za-z0-9_]{1,128}".r

  private def idParam(req: Request[IO], name: String): Option[String] =
    req.uri.query.params.get(name).filter(IdClaim.matches)

  private[runtime] def actionSignals(
      req: Request[IO],
      message: String
  ): Json = {
    val text = Json.fromString(message)
    Json.fromFields(
      idParam(req, NodeParam).map(id => s"_${id}__error" -> text).toList ++
        idParam(req, GroupParam).map(id =>
          s"_${id}__pending" -> Json.fromString("")
        ) ++
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

  /** `shell.ts`, inlined as a classic script: `fhConn` runs mid-body and
    * `fhUrl` in the first `data-effect`, so a deferred module defines them too
    * late. A missing resource fails hard — without it a page looks fine and
    * silently loses tab selection, session handoff and scroll.
    */
  val UrlSyncScript: String = FrontendAssets.content("shell")

  /** Classic and inline for the same reason as [[UrlSyncScript]]: it must run
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
    * an open popup, a slider mid-drag and the scroll position survive.
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

  /** By reference: a rebuild with identical bytes is a new instance and still
    * repaints.
    */
  private[runtime] val sameRenderer
      : ((Option[Renderer], Option[Renderer])) => Boolean = {
    case (None, None)       => true
    case (Some(a), Some(b)) => a eq b
    case _                  => false
  }

  private[runtime] val reloadPatch: SseFrame =
    Datastar.patchSignals(s"""{"$ReloadSignal":true}""")

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

  /** A non-empty signal store with no readable cursor (a filter that dropped
    * it, an old page): the only symptom is a resume quietly larger than it
    * should be, forever.
    */
  private[runtime] def cursorAnomaly(req: Request[IO]): Option[String] =
    signalsOf(req)
      // A first connect sends `{}` ([[hasSignals]]).
      .filter(_.keys.exists(_.nonEmpty))
      .flatMap(_.downField(CursorSignal).as(using cursorDecoder).left.toOption)
      .map(f =>
        "reconnect carried a signal store with no readable cursor " +
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

  /** A reconnect, not a first connect. Emptiness is the test: Datastar sends
    * the `datastar` param on every GET, and a first connect's is `{}`.
    */
  private[runtime] def hasSignals(req: Request[IO]): Boolean =
    signalsOf(req).exists(_.keys.exists(_.nonEmpty))

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

  /** Only the server writes `ui_*`; a pending ask ends when one agrees with it
    * (ADR 0025).
    */
  private[runtime] def selectionJson(
      renderer: Renderer,
      open: Set[String]
  ): Json =
    Json.obj(
      renderer.surfaces
        .committedSelections(open)
        .toList
        .map { case (id, v) =>
          UiSignalPrefix + id -> Json.fromString(v)
        }*
    )

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
    * (ADR 0025) — selections and node variables, the latter total so a
    * forgotten choice resets to the declared value.
    */
  private[runtime] def openingSignals(
      renderer: Renderer,
      open: Set[String],
      chosen: Map[(NodeId, String), String],
      logId: String,
      version: Long
  ): SseFrame =
    Datastar.patchSignals(
      cursorJson(renderer, logId, version)
        .deepMerge(selectionJson(renderer, open))
        .deepMerge(varJson(committedVars(renderer, chosen)))
        .noSpaces
    )

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
    * carried a cursor, `conn` or selection. [[cursorAnomaly]] cannot see it (an
    * empty store looks like a first connect); `ServerRoutesSuite` asserts the
    * served page.
    */
  private[runtime] val SseInclude: String =
    s"^($ConnSignal$$|${UiSignalPrefix}|$CursorSignal\\.)"

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
    *
    * Too short drops a tap-minted session's queued patch (ADR 0024), so the
    * popup does not open and the user taps again. Measure a real reconnect
    * before shortening it.
    */
  val AdoptionWindow: FiniteDuration = 10.seconds

  /** How long a returning client is told only what moved: a phone waking, a
    * lid, a wifi handover. Too short costs a fatter first patch, not
    * correctness — true only because a tap mints (ADR 0024).
    */
  val LingerWindow: FiniteDuration = 2.minutes

  private[runtime] val keepAliveComment: SseFrame =
    SseFrame.comment("keepalive")

  val DatastarCdn: String =
    "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"

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

  /** Int, then double, else string, so HA gets `brightness: 128`. */
  def parseValue(raw: String): Json =
    raw.toIntOption
      .map(Json.fromInt)
      .orElse(raw.toDoubleOption.flatMap(Json.fromDouble))
      .getOrElse(Json.fromString(raw))
}
