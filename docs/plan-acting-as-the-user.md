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

## Landed

`ServiceCalls` is the seam: `asInstance` is what this always did, `asUser` acts as the person
behind the request's cookie, and a request with no session falls back to the first. `AuthSession`
carries the short-lived access token beside the refresh token it is minted from (PR #311).

A tap is a REST `POST /api/services/<domain>/<service>` with that token (`ServiceCalls.overRest`),
not a socket per press: no handshake, no lifecycle. Checked against a live HA with the machine
token — the logbook's `context_user_id` is the token's user. What was given up is in ADR 0023's
consequences (a bare `400` names no field).

## Next

### 1. Verify against a live Home Assistant

**The step nothing here can finish.** HA attributing a REST call to its token's user is confirmed
(machine token, logbook). What is not: the whole path with a PERSON's token, which needs `sbt
dashboardServe` against a real instance, a login on the direct port, and one tap whose logbook
entry names that person.

Check while there: that the tap still works after the access token has expired (leave the tab open
past 30 minutes, or shorten `Margin` locally), and that revoking the session in HA →  Profile →
Security both refuses the next tap and closes the page.

### 2. Ingress, or a decision not to

Behind the Supervisor proxy HA has authenticated the user and forwards **who** they are, but never
gives this server a token **for** them — so there is nothing to act as, and ingress taps stay the
add-on's. That is the default way to reach the add-on, so "the seam landed" and "taps are the
user's" are not the same statement yet.

The options are a login on the ingress route (which is the second login ADR 0023 exists to avoid),
or living with it and saying so in the UI.

## Open questions

- **Where the fallback should NOT apply.** Today a request with no session silently acts as the
  instance. That is right for an unauthenticated deployment and for ingress; it is arguably wrong
  for a dashboard that requires a login, where "no session" should not have reached the action
  route at all. Currently unreachable — `Requirement.FromDashboard` gates it — so this is about
  whether the fallback should be a type-level impossibility rather than a runtime branch.
- **Whether an expired grant should also revoke locally.** `sessions.remove` drops our session;
  HA's refresh token is already dead, so there is nothing to revoke. Confirm rather than assume.
