# Plan: the periodic re-check signs everyone out (`client_id` mismatch on refresh)

**Status: implemented.** Sessions carry their mint-time `client_id`
(`AuthSession.clientId`), the sweep sends it back, and an undecodable
`sessions.json` refuses to boot. The decision lives in ADR 0023; this file
records what was wrong and why the fix looks as it does.

## The symptom

Roughly half an hour after logging in — and immediately after a restart for a session that
outlived it — the console prints

```
[auth] session for <name> is no longer valid at HA; signing out
```

(`ServerApp.scala:504`) and the browser is bounced to the login page, while Home Assistant →
Profile → Security still lists the token as live and unrevoked.

## The cause: we refresh with the wrong `client_id`

Login and the periodic re-check disagree about what this OAuth client is called.

- **Login** uses this server's own browser-facing base URL, derived per request:
  `AuthRoutes` computes `base = baseUriOf(req)` (`Server.baseUriOf`, `Server.scala:2695`) and
  passes it as `client_id` to both `/auth/authorize` and the `authorization_code` exchange —
  e.g. `http://192.168.1.50:8080`. HA stores exactly that string on the refresh token.
- **Revalidation** passes `haPublicUrl` (`ServerApp.scala:328`) — *Home Assistant's* URL,
  e.g. `http://192.168.1.174:8123` — as the `client_id` of the `refresh_token` grant.

HA's token endpoint compares the two, unguarded, in
`homeassistant/components/auth/__init__.py::_async_handle_refresh_token`:

```python
if refresh_token.client_id != client_id:
    return self.json({"error": "invalid_request"}, status_code=HTTPStatus.BAD_REQUEST)
```

So every refresh we send is answered `400 invalid_request`, no matter how healthy the grant is.
Note the comparison is against the raw value: omitting `client_id` entirely does **not** help
(`None != "http://…"`), and neither would a value that differs only by a trailing slash. The
string has to be the one login sent.

`haPublicUrl` was the wrong value to reach for in the first place — it is chosen by
`HaOAuth.browserBase` to answer "where do I send the browser to log in", which is a different
question from "what is this client called".

## The second half: any non-`200` evicts — and stays that way

`HaOAuth.refresh` maps every non-`200` to `RefreshOutcome.Dead`, and `revalidateOnce` evicts on
`Dead`. While the `client_id` is wrong this turns our own bug into "the whole household is signed
out" — but that is the *correct* reading, not a defect to soften. If HA answers at all and the
answer is not a refreshed token, we cannot tell a revoked grant from a request bug, and a session
we cannot vouch for has no business staying logged in. Signing out is loud; silently carrying a
possibly-dead session is not.

The only lenient case is **no answer at all** — a network error or timeout. Then everything else
is down too, so there is nothing to decide: leave the session alone and let the next sweep try.
That distinction already exists (`RefreshOutcome.Dead` vs the transport failure path) and is the
whole of the classification we need. No parsing of error bodies, no third outcome.

ADR 0023's sentence — "a `400 invalid_grant` is HA ANSWERING that the grant is gone" — is
narrower than this rule; §5 widens the wording to match: *any* answer other than a `200` is HA
answering, and an answer ends the session.

## Why the tests are green

`RevalidateSessionsSuite`'s stub replies to `/auth/token` without ever looking at the submitted
form, so the one field that is wrong in production is the one field no test reads. The suite
even passes the *correct* conceptual value (`uri"http://fh.test"`, the fh server) as the
`clientId` argument — the test documents the intent that the production wiring does not honour.

## The fix

### 1. Remember the `client_id` a session was minted with

Per-session, not per-process: `baseUriOf` is derived from the request, so the same instance
legitimately hands out different `client_id`s — direct IP, hostname, and the add-on ingress
prefix are three different clients as far as HA is concerned. A single startup-time value cannot
be right for all of them.

- Add `clientId: String` to `AuthSession` (`AuthSessions.scala`), written by `create` from the
  `base` that `AuthRoutes.complete` already has in hand, and carried through `renew` unchanged.
- Thread it into `create`/`renew` signatures; `revalidateOnce` then passes `session.clientId`
  to `oauth.refresh` and the `clientId` parameter of `revalidateSessions`/`revalidateOnce`
  disappears — the session knows its own client, so there is nothing for `ServerApp` to guess.
- `ServerApp.scala:328` loses its `haPublicUrl` argument. `haPublicUrl` stays what it is:
  the browser's login destination, and only that.

**Rejected: a minted per-session id instead of the base URL.** A bare UUID is not a legal
`client_id` at all — HA runs it through `indieauth._parse_client_id`, which requires an
`http`/`https` scheme, so login itself would fail with `400 "Invalid client id"`. A UUID *path*
under our own origin (`http://192.168.1.50:8080/<uuid>`) would pass, since `verify_redirect_uri`
compares only scheme and netloc — but it stores the same field with the same plumbing while
making HA's Profile → Security list a pile of opaque URLs the user cannot attribute to anything.
That list is the revocation UI this whole loop exists to honour. Stability comes from *storing*
the value, not from what the value is.

### 2. An old `sessions.json` refuses to boot, loudly

`AuthSession` derives its `Decoder`, so an existing file written before this change (no
`clientId` field) fails to decode — which today takes the *whole map* with it and prints "was
unreadable; starting with no sessions", quietly logging everyone out on every restart. Do not
soften the decoder to tolerate the missing field: a session without a `clientId` cannot be
refreshed, and pretending otherwise just moves the failure to the first sweep.

Instead make the failure honest and fatal: if `.fh/sessions.json` exists and does not decode,
boot stops with a message that says the file predates the stored-`client_id` format and that
deleting it (one re-login per person) fixes it. The ADR's current "a corrupt or unreadable file
logs and starts empty" sentence is rewritten along with this — a file that is *there* and wrong
is a broken workspace state we refuse to guess around, not a recoverable inconvenience.

### 3. Leave `RefreshOutcome` two-valued

No third case. `Dead` keeps meaning "HA answered, and the answer was not a refreshed token" —
exactly what eviction should act on — and transport failures
keep meaning "try again next sweep". The classification work lives in fixing the `client_id`,
not in second-guessing HA's error codes.

### 4. Make the stub check the field that broke

- `RevalidateSessionsSuite.haStub` should read the submitted `UrlForm` and reply
  `400 invalid_request` when `client_id` does not match what the session was created with —
  the same rule HA applies. With that in place, today's wiring fails the suite (the sweep evicts,
  because any non-`200` is an answer).
- Add a round-trip test through `AuthRoutes`: complete a login against a fake HA that records
  the `client_id` from the `authorization_code` exchange, then run one sweep and assert the
  refresh carried the *same* string. That is the property, not the line: it stays true if
  `baseUriOf` changes, if ingress is involved, or if a second base URL appears.

### 5. Update ADR 0023

Rewrite in place — no dated update section. Three passages change:

- **The revalidation paragraph** ("Two background mechanisms"): the eviction rule widens from
  "a `400 invalid_grant` is HA answering" to "any answer other than a `200` is HA answering";
  a timeout remains not-an-answer. Add the `client_id` round-trip requirement: a session stores
  the client it was minted for, because the browser-facing base is per-request and only HA's
  stored copy decides.
- **The persistence paragraph**: "logs and starts empty" becomes "refuses to boot" for a file
  that exists and does not decode, with the delete-and-re-login recovery stated.
- **The verified-endpoints table** gains no rows; but the `invalid_grant` row's claim that a
  revoked session is distinguishable from an unreachable HA stays true under the widened rule —
  that distinction is exactly the one we keep.

## Verification

Automated tests cannot prove the string matches HA's stored value, only that it round-trips
through our own code. Confirm once against the live instance: log in, then either wait out the
staleness window or temporarily run a sweep with `after = 0.seconds`, and check that the console
stays quiet and `verifiedAt` advances — while Profile → Security keeps showing one entry rather
than accumulating one per refresh.
