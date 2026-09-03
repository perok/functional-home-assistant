# ADR 0023 — Home Assistant is the identity provider; access is a per-dashboard rule

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** `fh/view/auth/` (new: `AuthGate`, `AuthRoutes`, `AuthSessions`,
  `HaOAuth`), `fh/view/model/Access.scala` (new),
  `model/Dashboard.scala`, `model/Transform.scala`, `build/Site.scala`,
  `runtime/Server.scala`, `runtime/ServerApp.scala`, `runtime/Renderer.scala`,
  `runtime/EditorRoutes.scala`, `lib/core/tap.pkl`, `lib/components/slider.pkl`,
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
(`ServerApp`'s `identify`).

That per-connection identity is also why an ACTION opens its own socket (issue
#198): HA attributes a `call_service` to whoever owns the connection, so the
shared feed — which stays on the machine token and never sees a user's — makes
every tap the add-on's. `ServerApp`'s `connectAs` is the one expression both
uses share, because WHERE a per-user credential is accepted is a third address
and writing that ranking twice is how the two would drift.

**The cookie is an opaque handle; the state lives server-side.** A v4 UUID —
no identity, no token, no claims — `HttpOnly`, `SameSite=Lax`, `Path=/`,
`Secure` only over https, `Max-Age` 90 days (HA's own refresh-token inactivity
window). Because it is a bearer id rather than a payload there is nothing to
sign or encrypt, which is what removed an earlier sealed-cookie design, its key
file, and the whole `javax.crypto` surface with it.

`AuthSessions` holds `id -> {user, refresh, verifiedAt, clientId, access}` in a
`SignallingRef`, deliberately a SEPARATE registry from the runtime's
`Sessions`: that one is keyed by `conn` and is a TAB, this one is keyed by a
cookie and is a PERSON, and merging two distinct facts into one shape fakes one
with the other. The HA refresh token is kept for two purposes — the periodic
re-check that this user still exists and still holds this role, and minting the
short-lived `access` token an ACTION goes out under (issue #198, `ServiceCalls`).
Neither ever reaches the browser, so a stolen cookie is a session rather than an
HA credential, and storing `access` beside `refresh` widens nothing: `refresh`
is already there and is strictly the more powerful of the two, since it mints
these on demand and does not expire. `clientId` rides along because HA accepts a refresh only under the
EXACT client_id string the login sent (`_async_handle_refresh_token` compares
it raw), and that string is derived per request — direct IP, hostname and the
ingress prefix are three different clients as far as HA is concerned — so it
cannot be re-derived at refresh time. A session stores the client it was minted
for; stability comes from storing the value, not from what the value is.

**The flow has TWO addresses, because one cannot serve both halves.** The
authorize redirect is built from the BROWSER-facing HA URL
(`browserBase`/`haPublicUrl` — where the user must reach HA to log in), while
`/auth/token`, `/auth/revoke`, every session refresh and the socket that asks HA
who a token belongs to are DIALLED at whatever `HaOAuth.coreBase` picks —
`SERVER` when that is HA itself, and something else when it is the supervisor
proxy, which serves nothing a per-USER credential can use (see Consequences). Sending the exchange to the browser-facing address instead failed
every production login with a bare 500: a laptop resolves HA's mDNS name, the
container running this server does not.
The `client_id` STRING stays the browser-facing base either way — it is what HA
compares raw at exchange and refresh time, not a routing instruction.

**Persistence is a write-through file, not a store.** Memory is the truth;
every mutation also writes `.fh/sessions.json` (`0600`), and boot reads it
back. That is all it is for — surviving a restart, which happens on every
dashboard edit. A missing file starts empty, which is the ordinary first boot.
A file that EXISTS but does not decode — corrupt, or a session from before
sessions carried their `clientId` — refuses to boot with a message naming the
recovery: delete the file, log in again once. Starting empty instead would sign
the whole household out on every restart while reading as somebody else's
warning; a session we cannot even decode is one nobody can vouch for, and loud
beats sorry. Softening the decoder to tolerate a missing `clientId` is mercy
that buys nothing: such a session cannot be refreshed anyway, so the failure
would merely move to its first sweep.

`SameSite=Lax` is the CSRF control for the action POSTs — it is the only thing
between a cookie-authenticated `POST /sse/action/...` and any other site. `Lax`
and not `Strict` because the OAuth callback is a cross-site top-level GET that
must arrive with the cookie.

**The requirement is declared where the route is.** A route wraps its handler
in `gate.handleRequirement(req, requirement)`, instead of a table elsewhere that
has to be kept in step with the route list. What that gets right which a central
classifier could not: a route that knows its own slug says so rather than being
guessed at.

There is no `Open` requirement. A public route — the PWA shell, the bundled
assets, the auth routes themselves — does not go through the gate at all. An
`Open` case was tried and removed: it returned the handler untouched, so it was
indistinguishable to the compiler from not wrapping, and it could not be the
inventory of public routes it looked like, because nothing obliges a route to
declare anything. The exemptions that are not self-evident say so in a comment
where they are granted — `/system/pkl/*` names issue #166.

**One predicate admits both a route and a dashboard.** The two remaining cases
are `FromDashboard(slug)` and `FromAccess(access)`, and both resolve to an
`Access` that `Access.permits` then answers — the registry's for a slug, the
route's own for everything else, with `Requirement.Admin` a name for
`FromAccess(Access.Admin)`. The editor being admin-only and a dashboard authored
`access = c.access.admin` are then the SAME rule evaluated the same way, rather
than a route-side `is_admin` check written a second time next to the one the
model already has.

**A route GROUP with one rule declares it once.** `AuthGate.require` wraps a
whole surface — the editor is admin, all of it — so a route added there later
inherits the rule instead of having to remember it. It is built from the route
table's own `PartialFunction` rather than a finished `HttpRoutes`, because
whether a request MATCHES has to be answerable without running the handler:
otherwise an unauthorised `PUT /edit/file` would write the file and then be told
no.

**Two background mechanisms, because they answer different questions.**
Revalidation is ONE fiber over the whole store (not one per session): entries
whose `verifiedAt` is older than 30 minutes — HA's access-token life — are
re-exchanged against their refresh token, under the `clientId` the session was
minted with, and re-read through `auth/current_user`, writing back a fresh
clock and a freshly-read role. The eviction rule is strict on purpose: ANY
answer other than a refreshed token — `invalid_grant`, `invalid_request`,
whatever the words — is HA ANSWERING, and an answer ends the session. A bug of
ours (say a mismatched client_id) is indistinguishable from a revoked grant
from where we sit, and a session nobody can vouch for has no business staying
logged in; signing out is loud, silently carrying a possibly-dead session is
not. A timeout or a refused connection is NOT an answer: an unreachable HA says
nothing about anybody's account, so the entry stays and the next sweep tries.
`HaOAuth.refresh` therefore stays TWO-valued — renewed or dead, no third case
and no parsing of HA's error bodies: the classification work belongs to sending
the right request, not to second-guessing the answer.
That is what makes revoking fh in HA reach a dashboard nobody is touching,
within 30 minutes plus one 5-minute tick.

The sweep runs once immediately and then on the interval, which is the whole of
what a RESTART needs. `verifiedAt` is persisted and absolute, so every session
that outlived downtime is already past the staleness bound on boot and is swept
before it can be used — no separate first-access check, no re-verification on
the request path. The alternative, checking lazily when a stale session is next
used, was rejected: it puts a network round trip inside `AuthGate.of` (which
every gated route calls), needs single-flight de-duplication because one page
load races several requests against the same session, and turns an unreachable
HA into a stall on every request rather than a background error. It would buy
only the difference between "the next request" and "within one tick".

Pending authorizations live in the same style and nowhere else: the OAuth
`state` is a random nonce keyed to `{next, deadline}` in memory with a
10-minute TTL, swept on the next login rather than by a fiber, since a login is
the only moment the map can have grown. Not persisted — a login interrupted by
a restart simply starts again. The state is consumed before the exchange so a
captured callback cannot be replayed; the cost is that any failure after the
claim burns the login, which is why everything after it maps onto a named
`FHError` (an unreachable HA answers 503, naming the address it could not
reach) rather than escaping as a raw exception — a failure a user can read is
one they can recover from by starting a fresh login.

**Admission is not a one-time event.** An SSE stream is admitted once and then
runs for hours. `AuthSessions` is a `SignallingRef`, so the stream is wrapped in
one `interruptWhen` over *the same* `Access.permits` the door used — logging
out, being revoked in HA, or losing the admin role reaches a dashboard that is
already open, and admission and continued admission cannot drift because they
are the same predicate.

That lives on `handleStream`, which the two SSE routes call instead of
`handleRequirement`. The route knows it is returning a stream; the gate would
have had to guess from the path, and did.

**What is watched follows the carrier that admitted the request.** The session
store is what a logout empties, so a COOKIE session is the only admission this
server can withdraw. Ingress and bearer requests hold their own credential and
are re-authenticated from scratch on every request; they are in no session, and
watching the store for one asks a map that will never hold them. That is not a
harmless extra check — `permits(None)` is false on the very FIRST element
(`SignallingRef.discrete` emits the current value), so the stream said goodbye
with `_reload` the moment it opened and the page came back to be told the same
thing. Behind ingress that was an endless reload loop on a dashboard the user
could see perfectly well.

So a non-cookie stream watches nothing. Nothing, specifically, means a stream
that never speaks and never ENDS: `Server.untilRevoked` halts on either side,
so an empty stream would cut the connection exactly like a revocation. Ingress
is asked first, for the same reason `AuthGate.of` prefers it — it is what
admitted the request, so a stale cookie beside it has no say in whether the
stream lives.

Withdrawing an ingress user's access is therefore HA's job, which is where it
belongs: remove them there and the next request resolves to nobody.

**An action may only touch an entity its own dashboard names.** The access rule
says WHO may use a dashboard; this says WHAT that lets them do, and without it
the two come apart badly — the action route forwards whatever `entity_id` is in
its URL, so admission to the most permissive dashboard in the house would be
admission to every entity in it. `Public` makes that sharp: no login, and the
front door lock one URL edit away from the street. So `POST /sse/action/:slug/…`
carries its dashboard, and `Server` refuses an entity that dashboard does not
reference.

It is decidable statically because a candidate set's membership is live but its
candidate LIST is not (ADR 0003) — `Dashboard.referencedEntities` walks the
layout and every surface once and cannot grow at runtime. A failed dashboard has
no renderer, names nothing, and therefore permits no action; that matters
because a failed dashboard's page is a diagnostics dump.

The slug cannot be authored: a dashboard module does not know its own (the
entrypoint supplies it as a key, and `fh push --slug` can rename it), so the
renderer supplies it. Two spellings, one fact, and each names the mechanism
that actually fills it: `$dashboardSlug` is a JSONata binding for a tap, whose
URL is a transform; `{{dashboardSlug}}` is a Mustache var for a card that
builds its URL in its own template (the slider's commit). Spelling both
`{{…}}` was tried and reverted — in a transform it reads as Mustache and never
is one, because a transform's OUTPUT is inserted raw at `{{{onclick}}}` and
Mustache never sees it.

Rejected in the other direction too: making the slider's URL a transform so one
spelling would do. Its `action`/`key` are deliberately baked LITERALS — a whole
`$lookup($domain)` tier was removed to stop computing build-time facts at
runtime — and six tests pin that.

**A dashboard is validated under the slug it will be served as.** The slug is
applied in `DashboardBuild.decode`, before `validated`, rather than to the
proof afterwards; `Validated.withSlug` is gone. That was harmless only while
nothing derived from the slug, and a compiled tap URL derives from it — a
`--slug` rename would otherwise leave every tap posting to a dashboard it is
not on.

**Only the page load redirects — and that is the CALLER's choice, not a second
requirement.** There is one `Requirement.FromDashboard(slug)`: the rule is the
same for a dashboard's page, its stream and its action POSTs. What differs is
only the shape of a refusal, so `handleRequirement` takes an `onInvalid`
`(Status, String) => Response`, defaulting to "say so". The page routes pass
`AuthGate.orLogIn`, which turns a `401` — and only a `401` — into a `303` to
`/auth/login?next=…`; a `403` means the wrong person, and bouncing them to a
login they are already past would loop.

The asymmetry is about who is asking, not what a client can parse. A page load
is where a human is waiting, so it is where a login can be sent for; everything
that page then opens was already admitted, so a later refusal on one of those
means the session DIED, which is an error and should read as one.

A 401 on an SSE stream carries no "log in here" hint, because nothing would act
on it: the browser learns that from the page.

**A revoked stream says goodbye before it closes.** Ending it stops the
dashboard UPDATING but leaves the tab SHOWING what it last received, so
somebody signed out on another device would go on reading the house off a
frozen page. The last thing the stream sends is the `_reload` signal every page
already declares a `window.location.reload()` effect for — the reload then hits
the page route, which redirects to login. Server-driven, over a channel that
already existed; the client needs nothing, and an earlier client-side listener
for this is gone.

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

**Behind ingress there is nobody left to log in.** When the add-on is reached
through HA's own ingress, HA has already authenticated the user and the
Supervisor forwards who they are — `X-Remote-User-Id`, `X-Remote-User-Name`,
`X-Remote-User-Display-Name` (verified in `supervisor/api/ingress.py` and
`supervisor/const.py`, not assumed). It strips those three from the incoming
request before re-adding them, in its own words "to prevent client spoofing".
So the OAuth flow above is for one case only: somebody reaching the direct
port.

The header is worth nothing on its own — anyone can send it to that direct
port, which `home-addon/config.yaml` gives the SAME 8080 as ingress, so the
socket cannot tell them apart. The boundary is where the connection came from:
ingress arrives from the Supervisor at a fixed address, and the add-on
documentation makes that a requirement rather than an observation ("Only
connections from `172.30.32.2` must be allowed"). A source address cannot be
forged on an established TCP connection. `FH_TRUSTED_PROXY` overrides it; set
empty, ingress trust is off.

What the headers do NOT carry is a ROLE, so the id is resolved against HA's own
account list — the one this server already fetches for the dump — cached for
five minutes. Two consequences worth stating: an id this instance cannot place
is NOT an identity (treating it as a logged-in nobody would let
`Access.Authenticated` admit it), and a failed lookup is not cached, so an
unreachable HA makes ingress users anonymous rather than making them admins.

**Machines carry the same HA identity in a different carrier.** The `fh` script
sends an HA long-lived access token as `Authorization: Bearer`; the server
resolves it exactly as it resolves a login. One identity source, two carriers —
so `fh` needs no shared secret of its own and its `is_admin` genuinely comes
from HA. `fh login` writes it to `.fh/user_secret.json` at `0600`, reading it
from stdin so a token does not land in shell history; gitignored —
deliberately separate from `machine.json`, because that file is per-machine
CONFIGURATION and this is a CREDENTIAL, and keeping them apart is what lets the
security follow-up move the credential without touching the config.

## Alternatives rejected

**A minted per-session id instead of the stored base URL.** A bare UUID is not
a legal `client_id` at all — HA runs it through IndieAuth's parser, which
requires an `http(s)` scheme, so login itself fails with `400 "Invalid client
id"`. A UUID *path* under our own origin passes (redirect verification compares
only scheme and netloc) and would store fine — but HA's Profile → Security then
lists a pile of opaque URLs nobody can attribute to anything, and that list is
the revocation UI this whole loop exists to honour.

**A stateless encrypted cookie carrying the user and refresh token.** Designed
and then dropped: it puts a full-HA-access credential in the browser (sealed,
but present), it cannot be revoked without a key rotation that logs everyone
out, and a `Set-Cookie` cannot be delivered on an already-open SSE stream — so
keeping a long-lived tab alive needed the page to poll a refresh endpoint. The
server-side store removes the poll entirely: the map IS the liveness signal.

**A client-triggered refresh ping.** Follows from the above. Interrupting the
stream server-side is the same fact expressed once, on the side that knows it.

**A central classifier over the whole app.** The first version was a pure,
total `requirementFor(request)` deciding for every path, with a vault-stamp
backstop refusing anything a matched route served without one. It was replaced
because the thing it optimised for — nobody forgets — is better served by
declaring at the route and wrapping a whole surface at once, and the thing it
cost was real: a route that knew its own slug had to be guessed at instead. The
guess was wrong in practice, which is how it was caught (see the action POSTs
above).

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

- Under the add-on's ingress — the default way to reach it — nobody logs in
  twice; the login flow is what the direct port needs. Both end at the same
  `HaUser` with the same role, so a rule cannot mean different things by route.
- Dashboards require a login by default. A workspace that wants otherwise says
  so, per dashboard or site-wide.
- `/system/pkl/*` stays ungated in this change (issue #166): pkl-lsp consumes
  it and it is not confirmed that it can send a header. The bearer carrier
  lands here, so the follow-up is only a matter of applying it.
- `.fh/sessions.json` and `.fh/user_secret.json` hold HA refresh tokens — and
  now the short-lived access tokens minted from them — inside a workspace users
  keep in git. Both are gitignored and `0600`, and that they live there at all
  is tracked as issue #165.
- **An action costs a connect and an auth handshake.** One socket per button
  press is the shape that needs no lifecycle at all, which is why it is first;
  the two cheaper answers (a pooled socket per logged-in person, or the REST API
  with the token on the request) are the same decision made once this one is
  known to work. Issue #198 has the comparison.
- **Ingress taps are still the add-on's.** Behind the Supervisor proxy HA has
  authenticated the user and forwards who they are, but never gives this server
  a token for them — so there is nothing to act as, and `ServiceCalls` falls
  back to the instance's own identity. Closing that means a login on the ingress
  route too, which is exactly the second login this design exists to avoid.
- **The browser-facing HA URL is not the one this server dials**, and under the
  add-on the difference is fatal rather than cosmetic: `home-addon/run.sh` dials
  `http://supervisor/core`, which resolves for this process and for nothing a
  browser runs in. `HaOAuth.browserBase` ranks four sources by how much each
  actually knows — `FH_HA_PUBLIC_URL`, then HA's own `internal_url` from
  `get_config`, then `SERVER` (a *verified* address, since this process holds a
  socket to it) unless it is the supervisor host, then
  `http://homeassistant.local:8123`. The mDNS name is last because it is a
  guess: the host is renameable and `.local` needs the client to do mDNS.
  That difference is exactly why the flow keeps two addresses (see above):
  redirects are built from this one, the exchange is dialled at the other. When
  they shared one base, a deployment whose browser-facing address was an mDNS
  name failed EVERY login with a bare 500 — the exchange went where only
  browsers could follow.
- **Nor is the dialled address the one a USER's credential goes to.** `SERVER` under the add-on is the
  supervisor proxy, and the supervisor routes only `/core/api/…` and the
  websocket to core — HA's `/auth/…` endpoints are not under `/api/`, so no path
  through the proxy reaches them. An unauthenticated POST there is answered by
  the supervisor's own security middleware with a plain `401: Unauthorized`
  ("No API token provided"), which surfaced as *"Home Assistant rejected the
  login code: 401: Unauthorized"* and killed every add-on login; sending the
  SUPERVISOR_TOKEN with it would only have turned that into a 404. So
  `HaOAuth.coreBase` ranks its own four sources, by who is dialling:
  `FH_HA_TOKEN_URL`, then `SERVER` unless it is the supervisor host, then
  `internal_url`, then `http://homeassistant:8123` — the direct name HA's add-on
  docs give core on the internal network, on the default port. The two chains
  share their sources and rank them differently on purpose: a browser prefers
  what HA calls itself, a container prefers the socket it already holds.
- **The websocket the proxy DOES carry is no exception**, which cost a second
  add-on login: fixing the exchange only moved the failure one step on, to
  *"could not ask Home Assistant who this login belongs to: Wrong msg:
  auth_invalid(Invalid access)"*. The supervisor's websocket authenticates its
  client as an ADD-ON — the auth frame it accepts carries `SUPERVISOR_TOKEN`,
  and the token it forwards to core is its own — so a USER's access token is
  rejected before core ever sees it. "Invalid access" is the supervisor's
  wording, not core's ("Invalid access token or password"), which is what
  placed the failure. The identity socket therefore dials `coreBase` too, and
  `HaOAuth.coreWs` drops the `SERVER_WS` override when it does: that setting
  exists to name the proxy's `/core/websocket` path, so carrying it along would
  send the user token back to the address the chain just routed around. The
  general rule, and the one to check the next such wiring against: **anything
  holding somebody else's credential bypasses the supervisor; only the
  machine-token feed goes through it.**
- That is resolved **once at startup**, so every visitor gets one answer — which
  is wrong for a remote browser, whose correct target is HA's `external_url`.
  Deferred with the PWA's local-vs-internet work, which is where the per-request
  local/remote distinction already lives.
- `Access.Users` still carries raw HA ids ON THE WIRE, but an author never
  writes one: `@fh-home`'s dump now has a `users` map, so a rule is
  `c.access.users(List(dump.users.peri))` and a misspelled name is a build
  error rather than a dashboard nobody can open. The dump keeps only real
  PEOPLE — HA's `config/auth/list` also returns Supervisor, Cast and the
  content user, and two of those three are admins. A role there is membership
  of the `system-admin` group, not an `is_admin` field, so `HaAccount.isAdmin`
  derives it.
