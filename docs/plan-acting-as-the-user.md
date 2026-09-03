# Plan — acting as the user

Work in flight for [issue #198](https://github.com/perok/functional-home-assistant/issues/198).
The decisions that are already true live in [ADR 0023](adr/0023-dashboard-access.md); this file is
only the route from here to a dashboard where a tap is never the add-on's.

Delete it when the last box below is either done or refiled as its own issue.

## The problem, in one paragraph

Home Assistant attributes a `call_service` to whoever owns the **connection**, decided once in its
auth handshake. Every read and every write in this app rides one shared socket authenticated with
the machine token, so every button press by every person is logged as Supervisor. Who pressed is a
property of the **request**. The two can only meet if the call gets a connection of its own.

## Landed (iteration 1) — PR #311

`ServiceCalls` is the seam: `asInstance` is what this always did, `asUser` opens a short-lived
socket authenticated as the person behind the request's cookie, and a request with no session falls
back to the first. `AuthSession` now carries the short-lived access token beside the refresh token
it is minted from.

What that PR decided, with reasons, is in ADR 0023 and in the PR body — not repeated here. Two
facts matter for what comes next:

- **A tap costs a connect plus a handshake.** That is the price of needing no lifecycle at all,
  which is why this shape is first rather than best.
- **`connectAs` is the seam that survives.** `ServiceCalls.asUser` takes `String =>
  Resource[IO, HomeAssistantApi[IO]]`, not a URL, so replacing the transport underneath it touches
  one expression in `ServerApp` and nothing else.

## Next

### 1. Verify against a live Home Assistant

**The only step that cannot be skipped, and the only one nothing here can do.** Every test is
against a fake. Whether HA's logbook actually names the user is the whole point of the change, and
it needs `sbt dashboardServe` against a real instance, a login on the direct port, and one tap.

Check while there: that the tap still works after the access token has expired (leave the tab open
past 30 minutes, or shorten `Margin` locally), and that revoking the session in HA →  Profile →
Security both refuses the next tap and closes the page.

### 2. Ingress, or a decision not to

Behind the Supervisor proxy HA has authenticated the user and forwards **who** they are, but never
gives this server a token **for** them — so there is nothing to act as, and ingress taps stay the
add-on's. That is the default way to reach the add-on, so "iteration 1 landed" and "taps are the
user's" are not the same statement yet.

The options are a login on the ingress route (which is the second login ADR 0023 exists to avoid),
or living with it and saying so in the UI. Worth deciding before iteration 2, because a per-user
transport that most deployments never reach is not worth optimising.

### 3. Iteration 2 — a cheaper transport

Two candidates, and they are not a sequence: whichever is chosen, the other is dead.

| | pooled socket per person | REST, token on the request |
|---|---|---|
| cost per tap | none after the first | one HTTP request |
| lifecycle | reaping, idle timeouts, teardown on `AuthSessions.watch` | none |
| sockets at HA | one per logged-in person | zero |
| fits per-user identity | poorly — identity is per connection, and a pool is a cache of connections | exactly — the token rides the request |

**The REST one is the better fit and the smaller change**, and `ha-api` already has the smithy4s
client. The pooled socket is here as the alternative that was considered, not as a plan.

The measurement that decides whether either is needed: **how long a tap takes today, on a Pi**. The
connect and handshake are the added cost, and nobody has timed them on the target. If a tap is
imperceptible, this step is not worth taking at all — say so and delete it.

## Open questions

- **Where the fallback should NOT apply.** Today a request with no session silently acts as the
  instance. That is right for an unauthenticated deployment and for ingress; it is arguably wrong
  for a dashboard that requires a login, where "no session" should not have reached the action
  route at all. Currently unreachable — `Requirement.FromDashboard` gates it — so this is about
  whether the fallback should be a type-level impossibility rather than a runtime branch.
- **Whether an expired grant should also revoke locally.** `sessions.remove` drops our session;
  HA's refresh token is already dead, so there is nothing to revoke. Confirm rather than assume.
