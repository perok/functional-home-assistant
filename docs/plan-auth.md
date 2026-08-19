# Dashboard authentication, reusing Home Assistant's own users (issue #89)

Status: **planned, not implemented.** Per the repo convention, nothing described here exists in
the sources yet.

## Context

Every route this server exposes is currently unauthenticated. That is safe only because
`ServerApp` binds loopback by default, and the codebase says so in three places that all defer to
this issue: `Server.scala:196-201` and `205-211` ("unauthenticated, deliberately … when auth lands
for the direct port it must cover this route"), and `EditorRoutes.scala:41-45` ("deliberately
ungated for now, safe only because the server binds loopback"). ADR 0021 names "Guest/ACL rules
(issue #89)" as the site-spanning concern its entrypoint design was built to accommodate, and
reserves `site.pkl` for it ("`default` today, auth rules next").

This is strictly a **new, browser-side notion of user**. The server↔HA connection is unchanged:
one machine token (`SECRET`), one shared `HaFeed` WS, and every service call the dashboard makes
still runs as that machine identity. What we add is *who is looking at the page* — and we do not
invent a user database for it, we reuse HA's users and HA's `is_admin` role.

Requirements, as stated:

1. A dashboard requires an authenticated user by default.
2. A dashboard may override that to: a specific HA user id (untyped string now, codegen later),
   admin-only, or guest.

Guest means **public — no login at all** (a wall tablet, a shared screen). It is the one of the
three that HA has no equivalent for, so it is ours; the other two are read off HA.

## Is HA's own auth reusable? Yes — verified live against the instance (HA 2026.8.2)

HA is an OAuth2 + IndieAuth provider, so this server becomes an ordinary OAuth client. Four things
were checked against the live instance rather than assumed, because all four are load-bearing:

| Checked | Result |
|---|---|
| `GET /auth/authorize` with a **LAN** `client_id` | `200` + the HA login page. `redirect_uri` sharing the client_id's host+port means HA never fetches `client_id` for an IndieAuth `link rel`, so the server needs **no public reachability**. |
| `POST /auth/token`, bad code | `400 {"error":"invalid_request","error_description":"Invalid code"}` — per spec. |
| `POST /auth/token`, `grant_type=refresh_token`, bad token | `400 {"error":"invalid_grant"}` — a dead session is distinguishable from a transport failure. |
| WS `{"id":1,"type":"auth/current_user"}` | `{"id":…,"name":…,"is_owner":true,"is_admin":true,…}` — **this is the whole identity + role source.** |

TTLs (HA docs): access token `expires_in: 1800` (30 min); refresh tokens have no fixed expiry but
are deleted after **90 days unused**, and are user-revocable per device under Profile → Security.
`POST /auth/revoke` always returns `200` with an empty body.

No add-on (`hass-oidc-auth`) and no JWT/crypto dependency is needed — `javax.crypto` from the JDK
covers the cookie, and the JDK `HttpClient` pattern already used by `FHApi`/`AssetCache` covers the
token exchange. `http4s-ember-client` is *not* on this module's classpath and does not need to be.

## The login flow

```
browser → GET /d/kitchen           no cookie
       ← 303 /auth/login?next=/d/kitchen
       ← 303 <HA>/auth/authorize?client_id=<fh base>&redirect_uri=<fh base>/auth/callback&state=<sealed>
   user logs in to HA (HA's own login page, HA's own MFA)
       ← 303 <fh base>/auth/callback?code=…&state=…
server: POST <HA>/auth/token (authorization_code)  → access_token + refresh_token
server: one-shot WS with THAT access token → auth/current_user → {id, name, is_admin}
       ← 303 /d/kitchen  + Set-Cookie: fh_session=<AES-GCM sealed>
```

`state` is sealed with the same key and carries `next` + a nonce + a 10-minute deadline, so the
pending-authorization map that would normally be needed server-side does not exist.

## Sessions: a stateless cookie, plus live interruption

No server-side session store. One cookie carries everything durable.

- **Cookie** `fh_session` — `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` when the request arrived
  over https, `Max-Age` 90 days (HA's own refresh-token inactivity window).
- **Payload**, sealed with AES-GCM: `{ uid, name, admin, owner, refresh, verifiedAt }`. The
  *access* token is deliberately **not** kept — after login it has no further use, because all HA
  traffic runs on the machine token. The refresh token is kept for one purpose: as the
  periodically re-checked proof that this HA user still exists and still holds that role.
- **Encrypted, not signed**, because that payload is a full-HA-access credential: sealing means the
  raw HA refresh token never exists outside this process even if the cookie leaks through a proxy
  log or a stolen cookie jar.
- **`SameSite=Lax` is the CSRF control** for the action POSTs — worth stating explicitly, because
  it is the only thing standing between a cookie-authenticated `POST /sse/action/...` and any other
  site.

### Two mechanisms, because they answer two different questions

**Cookie renewal is lazy, on ordinary requests.** Any gated request whose `verifiedAt` is older
than `revalidateAfter` (30 min, matching HA's access-token life) re-exchanges the refresh token,
re-reads `auth/current_user`, and re-issues the cookie with a fresh `verifiedAt` and a freshly-read
`admin`. Every action POST, navigation and reload carries the cookie and gets a fresh one back. A
page nobody touches does not need a renewed cookie; the moment it *is* touched, it gets one.

**Session death interrupts the open stream, server-side.** An SSE stream cannot receive a
`Set-Cookie`, but it does not need one — it needs to *stop*. This is entirely a backend concern:

- The authenticated identity is captured onto the existing per-connection `Session`
  (`Sessions.scala`) when the SSE stream is adopted, alongside a validity `SignallingRef[IO, Boolean]`.
- A background fiber revalidates that session's refresh token against HA on an interval; `400
  invalid_grant` (revoked in HA, user deleted) or a role change that no longer satisfies the
  dashboard's rule flips the signal.
- The SSE stream — already a composition of several streams in `Server.sseStream` — gains an
  `.interruptWhen(session.invalid)`. `POST /auth/logout` flips it immediately, and because the
  logout knows the user id it can flip *every* live session for that user.

There is deliberately **no client-side refresh ping**. It was considered and rejected: it would
have been a slower, less reliable duplicate of the interruption above, and cookie renewal is
already covered by ordinary traffic.

## Machines: the same HA identity, a different carrier

The `fh` laptop script has no cookie. Rather than invent a shared secret, it sends an **HA
long-lived access token** as `Authorization: Bearer <token>`; the server resolves that token to a
user exactly the way login does (one-shot WS `auth/current_user`, memoised token→user for a few
minutes since `fh push` is rare) and applies the same `is_admin` check. One identity source, two
carriers — `is_admin` genuinely comes from HA in both cases, which is the point.

`fh` stores its token in **`.fh/user_secret.json`** — separate from `machine.json`, because that
file is per-machine *configuration* and this is a credential; keeping them apart is what lets the
security follow-up below move the credential without touching the config.

## Route classification

| Routes | Requirement |
|---|---|
| `/web/:file`, `/assets/:name`, `/manifest.webmanifest`, `/sw.js`, `/icon-*.png`, `/auth/*` | **Never gated.** The PWA shell and the login page itself must load pre-auth. |
| `/`, `/d/:slug`, `/sse/dashboard/:slug/{patch,recover}` | The **dashboard's own rule** (`/` resolves via `LiveSite.defaultSlug`). |
| `POST /sse/action/*`, `/sse/surface/open/:id`, `/sse/popup/close` | The rule of the **session's** dashboard. These carry `conn` in the Datastar POST body (`conn` is one of the two signals deliberately left un-`_`-prefixed), so `Server.connOf(body)` → session → slug → rule. Note `/sse/action/*` does not currently go through `withSession` and will need the same body parse. |
| `/edit`, `/edit/*`, `/lsp/pkl`, `POST /system/push/:slug`, `POST /system/dump/refresh` | **Admin**, by cookie or Bearer. Closes the three deferred TODOs quoted above. |
| `GET /system/pkl/*` | **Ungated in this PR** — follow-up issue. The Bearer carrier lands here, so the follow-up is only a matter of applying it. |

Denial shape matters: an HTML `GET` gets `303` to `/auth/login?next=…`; an SSE stream, a POST or a
JSON endpoint gets `401`/`403` and never a redirect — a redirected SSE stream fails confusingly. An
authenticated user who merely lacks the role gets `403`, not a redirect, so there is no loop.

## The access rule, in Pkl and in Scala

New `lib/core/access.pkl` — ADR 0015 puts a wire/schema concept in `core/`, beside
`core/predicate.pkl` and `core/surface.pkl`:

```pkl
abstract class Access { kind: String }
class Public        extends Access { kind = "public" }         // guest: no login
class Authenticated extends Access { kind = "authenticated" }  // the default
class Admin         extends Access { kind = "admin" }
class Users         extends Access { kind = "users"; ids: Listing<String>(!isEmpty) }
```

- `entry.pkl` gains `access: Access? = null` beside `title` — the same optional-field pattern, and
  `omitNullProperties` keeps it off the wire when unset, so **existing wire snapshots must not
  move**. If they do, that is a signal to investigate, not to regenerate.
- `site.pkl` gains `access: Access = acc.authenticated` — the site-wide default. ADR 0021 reserved
  `site.pkl` for exactly this.
- Re-export through the `components.pkl` facade with **explicit types and no `extends`** (ADR 0015),
  so an author writes `access = c.access.admin` without a new import.
- **Precedence is resolved in Scala, not Pkl.** ADR 0021 explicitly rejected building a Pkl
  hoisting/inheritance mechanism for per-dashboard wire fields. `Site.decode` sees both the site
  default and each dashboard's `Option[Access]`, so it fills the `None` there and
  `Dashboard.Validated` carries a resolved, non-optional `access: Access`. Parse, don't validate: by
  the time the registry holds it, "which rule applies" is a fact in the type, not a lookup.

Scala side, `fh/view/model/Dashboard.scala`: `Access` as an `enum … derives ConfiguredDecoder`,
using the `kind` discriminator already configured at the top of that file. **Check the module's
`given Configuration` for a name transformation** — the Pkl `kind` literals must match whatever it
produces for the case names, and a silent mismatch decodes to a default rather than failing loudly.

## Files

New, under `modules/fh-datastar-view/src/main/scala/fh/view/auth/`:

- `Access.scala` — the ADT and `Access.permits(user: Option[HaUser]): Boolean`. Pure.
- `HaOAuth.scala` — authorize-URL construction, `/auth/token` exchange and refresh, `/auth/revoke`.
- `SessionCookie.scala` — AES-GCM seal/open for both the cookie and the OAuth `state`; key
  load-or-create; cookie build/parse.
- `AuthGate.scala` — `AuthGate.requirementFor(request): Requirement`, a **pure** classifier (the
  table above), plus the `HttpApp[IO] => HttpApp[IO]` middleware.
- `AuthRoutes.scala` — `/auth/login`, `/auth/callback`, `/auth/logout`.

Modified:

- `fh/view/runtime/ServerApp.scala` — compose the gate beside `FHError.handle` at the single
  existing seam (`.withHttpWebSocketApp(wsb => FHError.handle((server.routes <+> editor.routes(wsb)).orNotFound))`,
  ~L246), and thread out the two values the routes now need: `FHApi.Env.server` (resolved at L125
  but never passed to route construction) and a new `FH_HA_PUBLIC_URL` override, since the
  browser-facing HA URL is not always the one the server dials (split-horizon remote access).
- `fh/view/runtime/Sessions.scala` — carry the authenticated identity and a validity
  `SignallingRef` on `Session`.
- `fh/view/runtime/Server.scala` — `.interruptWhen` on the SSE stream; reuse `ingressPrefixOf`
  (L2446) with `Host`/`X-Forwarded-Proto` to derive the browser-facing base for
  `client_id`/`redirect_uri`; parse `conn` on the action routes.
- `fh/view/model/Dashboard.scala` — the `Access` enum + `access: Option[Access] = None`.
- `fh/view/build/Site.scala` — resolve the site default into each dashboard.
- `fh/view/build/AddonBootstrap.scala` — add `.fh/session-key` and `.fh/user_secret.json` to
  `GitignoreTemplate` (L329). **Note the write-once seeding at L135**: an existing workspace already
  has a `.gitignore` and will *not* pick up the new lines, so a user who keeps their workspace in
  git could commit a secret. This is part of the security follow-up below.
- `modules/ha-api/.../ws/protocol/` + `HomeAssistantApi.scala` — add `auth/current_user` to
  `CommandPhase` and a one-shot `identify(accessToken)` that opens a short-lived WS with a *user's*
  token. The only ha-api change, and it does not touch the shared feed.
- `lib/core/access.pkl` (new), `lib/entry.pkl`, `lib/site.pkl`, `lib/components.pkl`.
- `scripts/fh.sc` — send `Authorization: Bearer` from `.fh/user_secret.json`.

Under **add-on ingress** HA has already authenticated the user before proxying ("Users are
previously authenticated via Home Assistant"), but only `X-Ingress-Path` is documented as
forwarded — no user header is confirmed. The OAuth flow works unchanged under ingress (the browser
reaches HA at its own origin), so ingress needs no special case; whether `X-Remote-User-Id` is
actually sent is worth probing later as an optimisation, not assumed.

## Verification

1. `pkl test modules/fh-datastar-view/src/test/pkl/*.test.pkl` — **required**, any `.pkl` byte moves
   the `@fh-dashboard` package hash. Add a `site.test.pkl` fact for the default and an override.
2. `sbt fh-datastar-view/testFull` — one sbt command at a time; overlapping runs fake timeouts in
   the pkl suites.
3. `cd scripts && SCALA_TEST_MODE=true scala-cli test .` — the `fh` script changed.
4. New suites: `AccessSuite` (the rule table), `SessionCookieSuite` (round-trip, tamper rejection,
   wrong key, expiry), `AuthGateSuite` (route → requirement, and the 303-vs-401 shape), and an
   interruption test asserting an open SSE stream terminates when its session's validity signal
   flips. Add `access` to `WireShapeSuite`, which does not currently cover `Dashboard`/`entry` at
   all — a field added on one side and forgotten on the other would otherwise decode to its default
   and be silently ignored, which for an *access rule* fails open.
5. Existing suites hit `/d/:slug` and will now be gated. The harness gets a
   `TestSession.cookieFor(user)` helper (it holds the key, so it can mint one) rather than a
   test-only "auth off" mode — a gate that is disabled in every test is a gate nothing tests. A few
   suites assert the un-authenticated redirect instead.
6. Live, in the browser (`sbt dashboardServe`, per ADR 0006 — flow changes cannot be confirmed from
   a terminal): log in and land back on the requested dashboard; restart the server and confirm the
   session survives (the point of the stateless cookie); revoke the session in HA → Profile →
   Security and confirm the open dashboard's stream is cut; confirm a `public` dashboard loads in a
   private window with no login.

## Docs and follow-ups

- `docs/adr/0023-dashboard-access.md` — new decision: HA is the identity provider, the rule is a
  per-dashboard field with a site default, sessions are stateless encrypted cookies, and liveness
  is a server-side interruption.
- `docs/architecture-rendering-pipeline.md` — the gate is a new box in front of every route, and
  the SSE interruption is a new edge; update in the same commit (repo rule).
- ADR 0021's "auth rules next" line becomes "auth rules, see 0023".
- **Follow-up issue**: gate `/system/pkl/*` behind the Bearer carrier this work introduces.
- **Follow-up issue (security)**: `.fh/` will hold two secrets at rest — the AES-GCM
  `session-key` and the `fh` user token in `user_secret.json` — in a directory that lives inside a
  user's dashboards workspace, which is frequently kept in git. The write-once `.gitignore` seeding
  means existing workspaces never gain the ignore lines. This needs a better home (OS keychain, an
  add-on-private path outside the workspace, or supervisor-provided storage).

`home-addon/config.yaml` `version:` is untouched — releasing is the maintainer's bump.
