# Plan: a history fetch says what it reads, raw rows or HA's statistics

Agreed in chat on 2026-10-26 and 2026-10-27, after measuring why the more-info history popup
opens slowly. One PR (#652).

**State:** built. The first cut (2e628b66) read statistics first for `24h`/`7d`/`30d` with a raw
tail, and deleted the learned `retention`; the second replaced its fixed windows with a window the
author spells out, and dropped the tail. The decision now lives in ADR 0031 (Decision 4). What is
left is review, then deleting this file.

## Why

A popup waits for its fetches before it renders (architecture doc: every input is resolved
before the walk), so a slow history read is a slow popup. On main, `History.fetch` asks
`history_during_period` for every window, and also asks `statistics_during_period` in parallel
until a learned `retention` covers the window. It keeps whichever covers more time, then thins
the result to about 300 points with LTTB.

Measured against the live house over WS, for `sensor.ams_1a4e_p`, a power meter that reports
every few seconds:

| Read | Time | Rows | Size |
|---|---|---|---|
| Raw, 24 h | 439 ms | 23,691 | 847 KB |
| 5-minute statistics, 24 h | 43 ms | 287 | 27 KB |
| Hourly statistics, 7 d | 77 ms | 167 | |
| Statistics of an entity with none (dishwasher remaining time) | ~10 ms | 0 | |

Popup opens through a local `dashboardServe`, the same script on main and on 2e628b66:

| Popup open | main | 2e628b66 |
|---|---|---|
| First after server start | 1133 ms | 661 ms |
| Cold, 3 more popups | 417 / 124 / 317 ms | 236 / 100 / 112 ms |

The four windows (`1h`/`24h`/`7d`/`30d`) were meant as more-info's defaults, not as the API. HA
takes any start and end time, and statistics at `5minute`, `hour`, `day`, `week` or `month`.

## The design

### A window is typed, and the author picks it

`providers/history.pkl`, as `p.history`. The type stays **Window**: it was already the fetch's
field and the glossary's term, and `read` is a reserved word in Pkl.

```pkl
c.history.chart(e).over(p.history.raw(1.h))                          // every row, thinned to ~300
c.history.chart(e).over(p.history.statistics(7.d, "hour"))           // hourly means
c.history.chart(e).over(p.history.statistics(30.d, "hour").orRaw())  // raw when there are none
p.history.of(e, p.history.raw(7.d))                                  // every reading, for a computation
```

- **`Raw(span)`**: `history_during_period` over the span.
- **`Statistics(span, period, fallback)`**: `statistics_during_period`. The value is the bucket's
  `mean`, else a total sensor's `state`, plotted at the bucket's end, as `Series.fromStatistics`
  does today. `fallback` is off unless asked for (`.orRaw()`): an entity with no `state_class` then
  draws nothing rather than paying the raw read.
- **No raw tail.** The newest bucket ends minutes before now (1–5 minutes at `5minute`, up to an
  hour at `hour`), and `heldUntil` carries its value to now. HA's own more-info chart does the
  same: statistics only, no tail (`ha-more-info-history.ts`). The 2e628b66 tail goes.
- **The answer is every point:** every row of a raw read, every bucket of a statistics read. The
  chart stage thins to ~300 before drawing (ADR 0031: how many points a drawing needs is the
  drawing's), so a chart and a computation over one window are one fetch, and `everyPoint` is
  gone from the question, the wire and the cache key. Cost: the cache holds whole answers, and a
  data card gets every point; with statistics that is at most 720. Thinning cached at the provider
  would be a designed API of its own, later, if a case needs it.
- **The defaults:** a window has no default fallback, and `p.history.of(e, w)` has no default
  window. `c.history.chart(e)` defaults to more-info's `24h` preset.
- **The span is a Pkl `Duration`**, sent as whole seconds. `period` is a string-literal union.
  A period not shorter than the span is a build error: `hour` on one hour returns nothing (measured).

### On the wire: typed JSON, decoded straight into the request

The history fetch stops being `provider` plus a string map. It carries its fields, and circe
decodes them into `FetchRequest.History`, as the fast expressions decode into `Transform.Simple`
(ADR 0028 point 3). Nothing is parsed after the decode.

```json
{"provider": "history", "entity": "sensor.p",
 "window": {"kind": "Statistics", "span": 604800, "period": "hour", "fallback": false}}
```

`Fetch` in `core/slot.pkl` stays provider-agnostic, holding only `provider`. The typed history
class (`HistoryFetch`) lives in `components/history.pkl`, and `FetchTemplate`'s decoder checks
`provider`. A resolved read carries its `FetchRequest`, so the build's "parsed at declared values"
memo (`Validated.fetches`, `Renderer.fetchRequests`) is gone.

### A chooser picks a window by key

A node variable holds a string, because it lives in the URL (`v.window=…`) and in a signal. A
window can't ride in it, so the fetch carries the table (`Choice.Chosen`, beside `Choice.Fixed`):

```json
"window": {"kind": "Chosen", "by": {"var": "window"},
         "options": {"1h":  {"kind": "Raw", "span": 3600},
                     "24h": {"kind": "Statistics", "span": 86400, "period": "5minute", "fallback": true}}}
```

- `by` is the existing variable reference (`varMod.Ref`), resolved by name up the ancestors. No
  `@@VAR:` token is involved: that token names a declarer inside strings (a tap's route), not a
  fetch's read.
- **A viewer's value must be a key.** `Renderer.refusals` already refuses a value no reader can
  take, so a key the table lacks is refused at the write, with no new check.
- **The entity is never a variable.** Pkl already forbade it; the typed fetch makes it
  unrepresentable, so ADR 0023's read-side bound (`fetchedEntities`) is structural and goes.
- **More-info's presets are one Pkl mapping**, used by both `c.history.chooser` (its options) and
  `fromChooser()` (the table), so the two can't drift:

  | Key | Window |
  |---|---|
  | `1h` | `raw(1.h)` |
  | `24h` | `statistics(24.h, "5minute").orRaw()` |
  | `7d` | `statistics(7.d, "hour").orRaw()` |
  | `30d` | `statistics(30.d, "hour").orRaw()` |

  The fallback is on here because more-info shows any sensor, and the dishwasher's countdown has
  no statistics.

- **Considered: Simple's `Match`.** It is the same idea, a lookup table, under a different
  contract. `Match` maps a live entity's property, which we don't control, so it must be total
  (`otherwise` is required), and its arms are `String | Boolean`. A chooser's value is controlled
  at the write, so a missing key is refused rather than defaulted, and its arms are windows. Reusing
  `Match` would widen both its input and its arms, and its `otherwise` would hide a bad value
  instead of refusing it.

### The cache bucket is derived

The bucket is the answer's version and its expiry, and it's why ten viewers make one HA query.
There is no author knob:

- `Statistics`: the period;
- `Raw`: span ÷ 720, at least one minute.

That gives today's buckets for the presets (1 min, 5 min, 1 h, 1 h). Cache entries are the distinct
reads on screen, and each one goes when its bucket ends. A viewer can only pick a declared key, so
a viewer can't add variants.

### What goes

- The closed `Window` enum, Pkl's `Window` string union, `HistoryFetch.parse` and its string
  grammar (`"24h"`, `points = "all"`), and `Fetches.parse`.
- The generic fetch params: `FetchTemplate.params: Map[String, Ref]`, `Ref`, `SlotFetch`,
  `core/slot.pkl`'s `fetchSlot` and history's unused `chartSlot`. The history window was their
  only variable reader.
- `Dashboard.fetchedEntities` and `FetchRequest.entities`: the entity check in
  `Renderer.refusals` they fed can no longer fail.
- Already gone in 2e628b66: `retention`, `History.widest`, `Series.span`/`oldest`.

### Known costs

- **A mean flattens spikes.** A one-minute 2 kW kettle shows as about 400 W in a 5-minute bucket.
  HA's more-info chart draws `min`/`max` as a band for this. Not part of this change.
- **A bucket may roll just before HA writes its newest statistic**, which leaves that bucket out
  for one more period.

## Where the history API lives

- **`providers.pkl` + `providers/history.pkl`**, a tier of its own for what a component reads
  (ADR 0015), as `p.history`: `HistoryFetch` (typed sensor, required window), `of(e, w)`, the
  windows (`raw`, `statistics`, `chosen`, `presets`). The core still names no provider (ADR 0031).
  "Window" stopped being a namespace: in a house it means a real window (`binary_sensor.vindu`).
- **`c.history`** holds only cards: `chart(e)` and `chooser`. **`c.examples.historyReadings(e)`**
  is the passthrough example, kept out of the everyday set.
- **A chart's window, two ways:** (c) import the providers, `.over(p.history.statistics(7.d,
  "hour"))`; (d) a function handed them, `.over((p) -> p.history.statistics(7.d, "hour"))`. Both
  evaluate alike, and a function answering anything but a window is a type error (pinned in
  `components.test.pkl`). pkl-lsp 0.8.0 offers no completion inside d's function (0 items after
  `p.`, measured headless); the maintainer will try it in IntelliJ, and d is one `typealias` and
  one `if` to remove.
- Gone: `c.fetch`, `c.window`, `c.historyChart`, `c.historyReadings`, `c.windowChooser`, and
  `fetch.pkl`'s `History` subclass with its 24-hour default.

## Review follow-ups (2026-10-27)

- `c.history` holds only `chart`, `chooser` and `windowVar` (the chooser's variable). The drawing
  stage moved to `components/base/chart.pkl`; the window-or-function helper is the providers
  facade's own (`p.HistoryWindow`, `p.historyWindow`), where a component author writing a card of
  their own finds it too. Pkl takes a type alias over the module's own type (spiked).
- `HistoryFetch.entity` is `fixed`, so an object body cannot point a sensor-typed fetch elsewhere.
- `HistorySuite`'s cache tests read raw windows, so what they count is the fetch itself.
- Statistics calls name their `types` (`mean`, `state`); `StatisticPoint` is flat options. The
  ha-api decode tests that pinned the old `min`/`mean`/`max` grouping were rewritten or removed
  with it ("an incomplete band is not half a band", "a bucket carrying neither is rejected").
- A statistics bucket rolls a minute after its period (`Window.StatisticsLag`). This is our
  cache's timing; HA's API has no such parameter.
- A missing variable reads "the variable 'window' has no value here".

## For later: `history/stream`

HA's WS `history/stream` subscribes to an entity's rows: it answers the history over a window,
then pushes each new row as it is written. HA's more-info uses it for sensors without statistics.
A raw chart that must be live would use it instead of a small bucket, as a pushed provider whose
version moves on each row. It is not part of this change.

## Tests

- **`HistorySuite`:** the window-parse tests became decode tests (an unknown `kind`, an unknown
  period, a span of 0) and a validate rule (a period not shorter than the span). Sharing,
  buckets, failures and identities run on the presets, unchanged in what they assert. New: each
  window asks its source and nothing else; the newest bucket is carried to now; no statistics
  draw nothing unless the window falls back; every point of a statistics read is every bucket;
  the derived bucket.
- **Moved:** "an unknown provider is a build error" is a decode test now
  (`FetchRenderInputsSuite`), and "the provider's own parse error" became the period rule.
- **Removed, because the shape is unrepresentable:** `VarTapSuite`'s "a variable moves a chart
  only to an entity its dashboard shows" and `FetchValueSuite`'s `fetchedEntities` assertion. Two
  `VarTapSuite` tests that used an entity variable only as a second variable now use a second
  window variable, so what they assert (two writes landing together; one refused carry not
  costing another) is unchanged.
- **`FetchDriftSuite`** renders at the declared values (`vars.env`), as `Server` does; it passed
  an empty environment, which a chosen window cannot resolve.
- **Pkl:** `components.test.pkl` and `progress.test.pkl` assert the typed fetch; the chooser fact
  also checks that the bar's keys are the chart's table. The wire snapshots were regenerated
  with `sbt dashboardSnapshotsUpdate` and the diff read: only the fetch's shape moved.

## Docs

- **ADR 0031:** Decision 4 (the window is the author's, typed on the wire), and its consequences
  rewritten for a typed provider.
- **ADR 0033:** a fetch reads a variable by choosing a window by key.
- **ADR 0023 and ADR 0034:** the read-side bound holds by construction.
- **`docs/terminology.md`:** **Window**, **Bucket**, **Reference**.
- **`docs/writing-dashboards.md` and `docs/writing-components.md`:** `.over(…)` with `p.history`, imported or handed in.
- **The architecture doc:** a fetch's window may be chosen; both halves decode pure.

## Checks

- Re-run the popup timings above.
- Run `sbt fh-datastar-view/testFull`, or the `--exclude-tags=Slow` variant where no browser is
  available.
- Run CI-mode compile and scalafix.
