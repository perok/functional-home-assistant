# Plan: after the regions series

The regions series (#714–#726, #731) left four rules held by a comment, a hand-kept list or a
runtime throw, a gap in the oracle and in two benches, and two things a popup cannot do. Steps A–F
put the rules where a mistake cannot be written and close the test gaps; G and H are the popup
work. Each step is a commit (or a few) that is green on its own.

**State:** planned 2026-11-26, nothing built. Branch `after-regions`, stacked on `d6-benches`
(#731).

## Working this plan

For a session that starts here, cold:

- Read this plan, then only the files a step names. The decisions are settled; reopen one by
  asking, not by rewriting the step.
- Per step: write the failing test first and see it fail, then the change, then the docs the step
  lists, in the same commit. A step that cannot fail at run time (the type holds it) says so.
- Tests: `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` (no browser driver here; the
  smoke suites run on CI). Check `Total` is not 0.
- Bench gate, where a step names benches: run them before and after on the same machine,
  `sbt 'benchmarks/Jmh/run -f 1 -wi 3 -i 5 -w 2s -r 2s -prof gc "<filter>"'`. B/op equal or better,
  times within error; the numbers go in the commit message.
- Commit by file name. Push each green step; only the branch's last commit must be green on CI.
- Update the State line when a step lands.

## A. The oracle reloads and goes Back

**Why.** `OracleSuite`'s steps are a state change, a leave, a press, a drop, a connect and a call
press. The renderer-swap bug fixed in ed39c541 was found by reading the code, and Back's post
(`/sse/history/:slug`) is not one of the steps.

**Shape.** Two steps, the invariant unchanged (what each viewer was sent, applied in order, is a
fresh render for that viewer now):

- **Reload**: install the other of two variants of the run's dashboard into the same slug while
  viewers are connected. The variants differ by a tab and a card, so a swap drops a case a viewer
  chose and the session's choices are checked again.
- **Back(viewer)**: the page's history steps back and fires `popstate`, and the shell posts the
  query. The oracle page's history shim (`src/test/js/oracle/setup.js`, `history`) keeps entries
  and dispatches `popstate`, which the page does not get today.

**Test.** The steps are the test. Mutation-check each: undo ed39c541's change to
`Renderer.recheck` locally (Reload must fail), break the popstate post (Back must fail). Neither
undo is committed; both are named in the commit message.

## B. `Logged` only with evidence

**Why.** Under `Coverage.Logged`, `Patches.resume` trusts the log to name everything a viewer is
owed. Passed where the log does not reach the session's position, the page silently misses
nodes. Only a comment at the one call site (`Server.resumed`) keeps that from happening.

**Shape.** `Coverage.Logged` carries a value with a private constructor, minted by one function
where both facts are known: the log reaches the session's position (`FragmentLog.reaches`) and the
session has been watched since its page (the decision that reaches `Server.resumed`). Tests and
benches mint it the same way. `FetchBench.pull`'s default coverage goes.

**Test.** The mint refuses a log that does not reach the position.

**Gate.** `RenderBench.resumeMorphs`, `viewerPull`.

**Docs.** `docs/architecture-rendering-pipeline.md`, the coverage paragraph.

## C. The opening as a value

**Why.** `Server.openingPatches` decides reload, resume or repaint with six conditions written
inline and explained by a comment. Nothing tests the decision without the server harness, and a
repaint does not say why it happened.

**Shape.** A pure function returning
`enum Opening { case Reload; case Resume(from: Long); case Repaint(reason: RepaintReason) }`.
The reasons are the comment's: no cursor, another log, a cursor ahead of the store, one before a
gap nobody watched, one behind `told`, `holds` painted by another renderer. A failed slug or a
moved head is `Reload`. `openingPatches` runs it and logs a repaint's reason at debug.

**Test.** A table, one row per outcome, against the function alone.

**Docs.** The pipeline doc's opening paragraph; ADR 0011 where it lists the conditions.

## D. What a decision reads, in its type

**Why.** What a decision needs is known when it compiles: whether its `by` reads a variable, a
fetch, both or neither. `Shared` enforces it at run time instead: `viewer` throws when a decision
reading a variable is asked with no viewer, and `answered` throws when one reading a fetch is
reached outside a run.

**Shape.** `Ready` compiles each decision (a pick, a set member, a `valueWhile`, an order key) into
a type that says what it needs, and `Shared` decides each only through a method that takes that: a
viewer's values, a run, both. The two throws go. A read after its run has finished (`Run.answers`)
is about time, not type, and stays a check.

**Where.** `Ready`, `Shared` (`decideAt` and the methods routed through it), `PickGraph`,
`MemberGraph`, `VarGraph`.

**Test.** Cannot fail at run time once the types hold it: the suites stay green and `git grep`
finds neither message.

**Gate.** `RenderBench.publishSet`, `publishSetOrdered`, `publishSetNamedGuard`, `viewerPull`,
`resumeMorphsFanout`.

**Docs.** The pipeline doc, §1 (`Shared` and the two levels); `docs/terminology.md` if a type
name becomes a word the docs use.

**Open.** The type names, settled at the step.

## E. A reload compares what a dashboard was resolved with

**Why.** `planSite` compares `Server.Content(dashboard, access)`, a hand-picked pair. A second site
setting folded into each dashboard at decode, as `Site.decode` folds the access rule, would be
missed: the bug af075d3e fixed, again.

**Shape.** `Dashboard.Validated(source: Validated.Source(dashboard, access), ready, templates)`.
Everything else in it is compiled from `source`, so a site setting can only land in `Source`, and
`planSite` compares `source`. `withAccess` updates it; `Origin.FromSite` holds
`Either[String, Validated.Source]`; `Server.Content` goes.

**Test.** Cannot fail at run time once the type holds it: the reload test in
`FailedDashboardSuite` ("a reload applies a changed site access rule…") stays green.

## F. Benches check their premise

**Why.** `FetchBench` and `VarEnvBench` priced a whole-screen sweep under names that said pull
(9258dac6). `RenderBench`'s setups fail when a fixture stops being what a bench claims; these did
not.

**Shape.** Setup checks that fail naming the premise: `FetchBench.pullPlain` sends one node and
asks no read, `pullChart` asks its chart's read and no other, `reconnectTabOpen` asks the tab's
four charts, `VarEnvBench.pullPlain` sends one node and asks nothing.

**Test.** Break each premise locally once and see the error.

## G. A popup reads what its taps read

**Why.** A popup is a case of the page's `popups` match, so it has the page's scope. A popup body
reading a variable its button sees (a window chooser above the card) fails the build — "reads the
variable 'window', which no ancestor declares" — although the build knows every tap that opens it.

**Rule** (ADR 0033 rule 6). A case starts with its host's scope, as today. A name the host does not
see is in scope too when every tap that chooses the case sees it from the same declaration.

- A tap chooses a case when its `v.choose(k)` resolves `v` to the declaration the choice's `by`
  reads, and `k` is the case's key. Only a `by` that is a plain variable read has such taps; any
  other case keeps its host's scope.
- A tap inside another case sees what that case's scope gives it, by the same rule. Scopes only
  grow, so the build repeats until nothing changes; two popups opening each other add nothing to
  each other by themselves.

**Consequences.**

- The read still binds once, at build time (rule 2): nothing changes at run time, in the URL or on
  the wire. An open popup follows its chooser live, as any reader does.
- The host's scope comes first, so no dashboard that builds today changes meaning; only some that
  fail today build.
- Taps that disagree, two choosers each over a card opening one popup that reads `window`: the
  name is out of scope there, and the error names both declarations and the way out (give each
  its own popup id).
- Each set member is its own declarer, so a popup reads a member's variable only if one member
  opens it.

**Where.** `Dashboard.varScopes`, a map from each case to the taps choosing it (each tap's
`SetVar` resolved where the tap is), `Validate`'s message for a read with no declarer.

**Test.** (a) a popup under one chooser reading `window` builds, shows the chooser's value, and
repaints when the chooser is written while it is open; (b) taps under two choosers: the build
error names both; (c) two popups opening each other, a name declared above only one tap: builds;
(d) a popup still open after its chooser left the screen (a tab switched away) keeps reading the
chooser's value, since values are kept by declarer (ADR 0033 rule 9), and a write elsewhere does
not move it; (e) every golden and snapshot unchanged.

**Gate.** `ValidateBench.validated`, equal within error.

**Docs.** ADR 0033 rule 6; ADR 0002's popups; `writing-dashboards.md`, Popups ("a popup reads the
choices its buttons read"); `writing-components.md` where it states a case's scope;
`docs/terminology.md`, Popup.

## H. Alerts: popups that state shows

**Why.** A popup opens only through a tap or a link; "show the camera while the doorbell rings"
has no form. Decided: the state controls it and nothing else, so an alert has no close and no
per-viewer dismissal.

**Shape.**

- An entry's `alerts { c.view.alert("doorbell", q.entity(doorbell).stateIs("on"), body) }`, shown
  to every viewer while its condition holds. The id is the alert's DOM id, as a popup's is.
- An alert is the popup card with no ✕, no Escape and no backdrop close (`base/popup.pkl`).
- The page card's `popups` slot holds a group per alert (`nodeMod.iff(cond).then(alert)`; a card
  holds one choice, so each alert is its own host) before the `popups` group, so a popup opened
  from an alert's own button shows above it. A dashboard with no alerts sends today's wire.
- `popupMod.cases` walks each alert's body too, so a popup an alert's button opens exists.
- No history entry, so Back is unaffected. An alert has the page's scope (no tap chooses it).

**Where.** `entry.pkl`, `core/page.pkl`, `components/popup.pkl`, `components/view.pkl`,
`components/base/popup.pkl`; a `FixtureEntries` dashboard (snapshot and golden), added to the
oracle's fixtures so a state change shows and hides it.

**Test.** A Pkl fact (an alert's markup has no close); the fixture's alert shown, then hidden by a
state change; the oracle over it.

**Docs.** `writing-dashboards.md` (Alerts), ADR 0002, `docs/terminology.md` (Alert). The guide
says an alert covers the page for as long as its condition holds, so a sensor stuck `on` blocks
the tablet, and shows the way out through HA: a helper in the condition
(`doorbell on && ack off`), a button in the alert turning `ack` on, an automation resetting it on
the next ring. That is a dismissal shared by every viewer; a per-viewer one is out of scope.

**Open.** The names (`alerts`, `c.view.alert`). The order: before user popups (recommended, so an
alert's buttons open above it), which means an alert arriving while a viewer has a popup open
shows beneath it until that closes.

## Not in this plan

- Dismissing an alert per viewer: it needs a general per-viewer value, designed on its own
  (decided 2026-11-26).
- Passing a value into a popup when it opens: G covers the static case, which is every case a
  build can check.
- #732–#735.
