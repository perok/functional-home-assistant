# ADR 0023 — Home Assistant is the identity provider; access is a per-dashboard rule

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** `fh/view/auth/` (new: `AuthGate`, `AuthMiddleware`, `AuthRoutes`,
  `AuthSessions`, `HaOAuth`), `fh/view/model/Access.scala` (new),
  `model/Dashboard.scala`, `build/Site.scala`, `runtime/Server.scala`,
  `runtime/ServerApp.scala`, `runtime/Renderer.scala`,
  `ha-api` (`auth/current_user`), `lib/core/access.pkl` (new), `lib/entry.pkl`,
  `lib/site.pkl`, `lib/components.pkl`, `src/js/shell.ts`, `scripts/fh.sc`
- **Closes:** issue #89, and the three "unauthenticated, deliberately" TODOs it
  was deferred to (`Server.scala`, `EditorRoutes.scala`).
- **Uses:** ADR 0021's entrypoint — this is the site-wide setting it reserved
  `site.pkl` for.

## Context

Every route was unauthenticated. That was safe only because the server binds
loopback by default, and the codebase said so in three places, each pointing at
this issue. Remote access (ADR 0014's PWA, a reverse proxy) makes that not a
default any more but a wish.

The instance already has a user database with roles, and it is not ours: Home
Assistant's. Inventing a second one would mean a second password to rotate, a
second admin flag to keep in step, and a login screen that is worse than the one
HA already ships (which has MFA).

The load-bearing distinction, and the one that is easy to get wrong: **this is a
new, browser-side notion of user.** The server↔HA connection is unchanged — one
machine token, one shared `HaFeed`, and every service call a dashboard makes
still runs as that machine identity. What is added is *who is looking at the
page*.

## The decision

**HA is the identity provider, over its own OAuth2 + IndieAuth endpoints.**
Verified live against HA 2026.8.2 rather than assumed, because all four are
load-bearing:

| Checked | Result |
|---|---|
| `GET /auth/authorize` with a **LAN** `client_id` | `200` + the login page. `redirect_uri` sharing the client_id's host+port means HA never fetches `client_id` for an IndieAuth `link rel`, so this server needs **no public reachability**. |
| `POST /auth/token`, bad code | `400 invalid_request` |
| `POST /auth/token`, `grant_type=refresh_token`, dead token | `400 invalid_grant` — a revoked session is distinguishable from an unreachable HA. |
| WS `auth/current_user` | `{id, name, is_admin, is_owner}` — the whole identity + role source. |

No add-on and no crypto library is needed. `auth/current_user` answers for
whichever access token authenticated *that* connection, so the login flow opens
a one-shot WS with the user's freshly-exchanged token and closes it
(`ServerApp`'s `identify`). It is the only use of somebody else's token, and the
shared feed never sees one.

**The cookie is an opaque handle; the state lives server-side.** 256 random bits,
`HttpOnly`, `SameSite=Lax`, `Secure` only over https. `AuthSessions` holds
`id -> {user, refresh, verifiedAt}` and write-throughs to `.fh/sessions.json`
(`0600`) so a restart — which happens on every dashboard edit — does not log
everyone out. The HA refresh token is kept for exactly one purpose: the periodic
re-check that this user still exists and still holds this role. It never reaches
the browser, so a stolen cookie is a session rather than an HA credential.

`SameSite=Lax` is the CSRF control for the action POSTs — it is the only thing
between a cookie-authenticated `POST /sse/action/...` and any other site. `Lax`
and not `Strict` because the OAuth callback is a cross-site top-level GET that
must arrive with the cookie.

**The gate wraps the whole app, and classification is pure and total.**
`AuthGate.requirementFor(request)` decides for *every* path, including ones that
do not exist — an unknown path is a dashboard request, never `Open`. The
alternative, annotating routes inside the route table, protects only what
somebody remembered to annotate and fails silently; that is not a property a
review catches twice.

**Admission is not a one-time event.** An SSE stream is admitted once and then
runs for hours. `AuthSessions` is a `SignallingRef`, so the stream is wrapped in
one `interruptWhen` over *the same* `Access.permits` the door used — logging
out, being revoked in HA, or losing the admin role reaches a dashboard that is
already open, and admission and continued admission cannot drift because they
are the same predicate.

**Denial shape is part of the classification, not a guess at the denial site.**
An HTML `GET` gets `303` to `/auth/login?next=…`; a stream, a POST or a JSON
endpoint gets `401`/`403` and never a redirect — a redirected SSE stream reports
as a broken connection, which is unreadable. An authenticated user who merely
lacks the role gets `403`, so there is no login loop.

**The rule itself is authored data.** `lib/core/access.pkl` declares four cases
(`public`, `authenticated`, `admin`, `users(ids)`), written per dashboard on
`entry.pkl` with a site-wide default on `site.pkl`. `public` is the "guest" of
the issue — no login at all, which is what a wall tablet needs, and the one case
HA has no equivalent for. `users` is deliberately literal: an admin who is not
listed is refused, because an implicit override would make "only these two
people" quietly untrue.

**Precedence is resolved in Scala, once.** ADR 0021 rejected a Pkl
hoisting/inheritance mechanism for per-dashboard wire fields, and this needs
none: `Site.decode` sees both the site default and each dashboard's
`Option[Access]`, folds them, and `Dashboard.Validated` carries a resolved,
non-optional `access`. Parse, don't validate — by the time the registry holds a
dashboard, "which rule applies" is a fact in the type rather than a lookup every
gate re-derives.

**Machines carry the same HA identity in a different carrier.** The `fh` script
sends an HA long-lived access token as `Authorization: Bearer`; the server
resolves it exactly as it resolves a login. One identity source, two carriers —
so `fh` needs no shared secret of its own and its `is_admin` genuinely comes
from HA. Stored in `.fh/user_secret.json` (`{"token": "..."}`), gitignored.

## Alternatives rejected

**A stateless encrypted cookie carrying the user and refresh token.** Designed
and then dropped: it puts a full-HA-access credential in the browser (sealed,
but present), it cannot be revoked without a key rotation that logs everyone
out, and a `Set-Cookie` cannot be delivered on an already-open SSE stream — so
keeping a long-lived tab alive needed the page to poll a refresh endpoint. The
server-side store removes the poll entirely: the map IS the liveness signal.

**A client-triggered refresh ping.** Follows from the above. Interrupting the
stream server-side is the same fact expressed once, on the side that knows it.

**A test-only "auth off" mode.** The harness runs the real gate with a real
minted session (`TestAuth`). A gate disabled in every test is a gate nothing
tests, and the failure it hides — auth that passes its suite and refuses every
real browser, or admits one — is exactly the failure that matters.

**Trusting an ingress user header.** Under add-on ingress HA has already
authenticated the user, but only `X-Ingress-Path` is documented as forwarded; no
user header is confirmed. The OAuth flow works unchanged under ingress (the
browser reaches HA at its own origin), so ingress needs no special case. Worth
probing later as an optimisation, not assumed.

## Consequences

- Dashboards require a login by default. A workspace that wants otherwise says
  so, per dashboard or site-wide.
- `/system/pkl/*` stays ungated in this change (issue #166): pkl-lsp consumes
  it and it is not confirmed that it can send a header. The bearer carrier
  lands here, so the follow-up is only a matter of applying it.
- `.fh/sessions.json` and `.fh/user_secret.json` hold HA refresh tokens inside a
  workspace users keep in git. Both are gitignored and `0600`, and that they
  live there at all is tracked as issue #165.
- The browser-facing HA URL is `FH_HA_PUBLIC_URL`, falling back to the address
  this server dials (`SERVER`). Those are two different URLs for two different
  clients and a split-horizon setup needs both; HA's own `get_config` carries
  `external_url` and an often-null `internal_url`, which would make this
  discoverable rather than configured. Not done here.
- `Access.Users` holds raw HA id strings. HA's WS `config/auth/list` returns the
  real user list (admin-only, and admin is `group_ids` containing
  `system-admin`, not an `is_admin` field), so typing these off codegen is
  available and deferred.
