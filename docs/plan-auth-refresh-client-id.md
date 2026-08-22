# Plan: the periodic re-check signs everyone out (`client_id` mismatch on refresh)

**Status: not implemented.** This file describes a defect and the fix; nothing here exists in
the sources yet.

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

## The second half: any non-200 is read as "the grant is gone"

`HaOAuth.refresh` maps every non-`200` to `RefreshOutcome.Dead`, and `revalidateOnce` evicts on
`Dead`. So a *malformed request of ours* is indistinguishable from *HA disowning the user*, and
the failure mode of a bad `client_id` is "log the whole household out" rather than "log an
error". ADR 0023 already says the narrower thing — "a `400 invalid_grant` is HA ANSWERING that
the grant is gone" — the code is wider than the decision.

HA's answers here are distinct and worth keeping distinct:

| HA answer | Meaning | What we should do |
|---|---|---|
| `400 invalid_grant` | The refresh token is unknown/revoked | Evict |
| `403 access_denied` | The user exists but may not authenticate (deactivated) | Evict |
| `400 invalid_request` | *Our* request was wrong (bad/mismatched `client_id`, no token) | Log loudly, keep the session |
| network error | Not an answer at all | Leave it alone (already correct) |

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

### 2. Decode old `sessions.json` files without a scary failure

`AuthSession` derives its `Decoder`, so an existing file (no `clientId` field) fails to decode,
which today takes the *whole map* with it and prints "was unreadable; starting with no
sessions". Decode the field as absent-allowed and treat a session without one as un-refreshable:
it cannot be re-checked, so evict it on the first sweep with a message that says a re-login is
needed. One re-login, once, and stated plainly — rather than a warning that reads like
corruption.

### 3. Narrow `RefreshOutcome`

Give the enum the third case the table above needs — the answers that mean "our request was
wrong" must not evict. Parse the error body's `"error"` field (`invalid_grant` vs
`invalid_request`) plus the status on the `Left` branch of `HaOAuth.post`, so the classification
lives next to the wire format and `revalidateOnce` just matches on a value. Keep `Dead` meaning
exactly "HA says this grant is gone", so ADR 0023's sentence stays true of the code.

### 4. Make the stub check the field that broke

- `RevalidateSessionsSuite.haStub` should read the submitted `UrlForm` and reply
  `400 invalid_request` when `client_id` does not match what the session was created with —
  the same rule HA applies. With that in place, today's wiring fails the suite.
- Add a round-trip test through `AuthRoutes`: complete a login against a fake HA that records
  the `client_id` from the `authorization_code` exchange, then run one sweep and assert the
  refresh carried the *same* string. That is the property, not the line: it stays true if
  `baseUriOf` changes, if ingress is involved, or if a second base URL appears.
- Add a test that `400 invalid_request` (as opposed to `invalid_grant`) leaves the session in
  place — the regression guard for §3.

### 5. Update ADR 0023

The "Two background mechanisms" section describes the eviction rule; extend it with the
`client_id` round-trip requirement (a session stores the client it was minted for, because the
browser-facing base is per-request) and with the fuller answer table. Rewrite in place — no
dated update section.

## Verification

Automated tests cannot prove the string matches HA's stored value, only that it round-trips
through our own code. Confirm once against the live instance: log in, then either wait out the
staleness window or temporarily run a sweep with `after = 0.seconds`, and check that the console
stays quiet and `verifiedAt` advances — while Profile → Security keeps showing one entry rather
than accumulating one per refresh.
