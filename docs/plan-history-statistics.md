# Plan: a history fetch says what it reads, raw rows or HA's statistics

Agreed in chat on 2026-10-26 and 2026-10-27, after measuring why the more-info history popup
opens slowly. One PR (#652).

**State:** the first cut is on the branch (2e628b66): statistics first for `24h`/`7d`/`30d`, a raw
tail to now, and the learned `retention` deleted. This plan replaces its fixed windows with a read
the author spells out, and drops the raw tail.

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

### A read is typed, and the author picks it

```pkl
c.historyChart(e).over(h.raw(1.h))                           // every row, thinned to ~300
c.historyChart(e).over(h.statistics(7.d, "hour"))            // hourly means
c.historyChart(e).over(h.statistics(30.d, "hour").orRaw())   // raw when there are no statistics
c.fetch.history(e).over(h.raw(7.d))                          // every reading, for a computation
```

- **`Raw(span)`**: `history_during_period` over the span.
- **`Statistics(span, period, fallback)`**: `statistics_during_period`. The value is the bucket's
  `mean`, else a total sensor's `state`, plotted at the bucket's end, as `Series.fromStatistics`
  does today. `fallback` is off unless asked for (`.orRaw()`): an entity with no `state_class` then
  draws nothing rather than paying the raw read.
- **No raw tail.** The newest bucket ends minutes before now (1–5 minutes at `5minute`, up to an
  hour at `hour`), and `heldUntil` carries its value to now. HA's own more-info chart does the
  same: statistics only, no tail (`ha-more-info-history.ts`). The 2e628b66 tail goes.
- **Every point** (`c.fetch.history`'s answer) is every row of a raw read, and every bucket of a
  statistics read. Nothing is thinned.
- **The defaults:** the API's default read is `Raw` with no fallback. `c.fetch.history(e)` keeps
  24 hours, now `h.raw(24.h)`. `c.historyChart(e)` defaults to more-info's `24h` preset.
- **The span is a Pkl `Duration`**, sent as whole seconds. `period` is a string-literal union.
  A period not shorter than the span is a build error: `hour` on one hour returns nothing (measured).

### On the wire: typed JSON, decoded straight into the request

The history fetch stops being `provider` plus a string map. It carries its fields, and circe
decodes them into `FetchRequest.History`, as the fast expressions decode into `Transform.Simple`
(ADR 0028 point 3). Nothing is parsed after the decode.

```json
{"provider": "history", "entity": "sensor.p", "everyPoint": false,
 "read": {"kind": "Statistics", "span": 604800, "period": "hour", "fallback": false}}
```

`Fetch` in `core/slot.pkl` stays provider-agnostic, holding only `provider`. The typed history
class lives in `components/`, and the Scala side picks the decoder by `provider`.

### A chooser picks a read by key

A node variable holds a string, because it lives in the URL (`v.window=…`) and in a signal. A
read can't ride in it, so the fetch carries the table:

```json
"read": {"kind": "Chosen", "by": {"var": "window"},
         "options": {"1h":  {"kind": "Raw", "span": 3600},
                     "24h": {"kind": "Statistics", "span": 86400, "period": "5minute", "fallback": true}}}
```

- `by` is the existing variable reference (`varMod.Ref`), resolved by name up the ancestors. No
  `@@VAR:` token is involved: that token names a declarer inside strings (a tap's route), not a
  fetch's read.
- **A viewer's value must be a key.** `Renderer.refusals` already refuses a value no reader can
  read, so a key the table lacks is refused at the write, with no new check.
- **More-info's presets are one Pkl mapping**, used by both `c.windowChooser` (its options) and
  `fromChooser()` (the table), so the two can't drift:

  | Key | Read |
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
  at the write, so a missing key is refused rather than defaulted, and its arms are reads. Reusing
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

- `Window` (the closed enum), `historyMod.Window`, `HistoryFetch.parse` and its string grammar
  (`"24h"`, `points = "all"`).
- The generic fetch params: `FetchTemplate.params: Map[String, Ref]` and `SlotFetch`'s string map.
  The history window was their only variable reader.
- Already gone in 2e628b66: `retention`, `History.widest`, `Series.span`/`oldest`.

### Known costs

- **A mean flattens spikes.** A one-minute 2 kW kettle shows as about 400 W in a 5-minute bucket.
  HA's more-info chart draws `min`/`max` as a band for this. Not part of this change.
- **A bucket may roll just before HA writes its newest statistic**, which leaves that bucket out
  for one more period.

## For later: `history/stream`

HA's WS `history/stream` subscribes to an entity's rows: it answers the history over a window,
then pushes each new row as it is written. HA's more-info uses it for sensors without statistics.
A raw chart that must be live would use it instead of a small bucket, as a pushed provider whose
version moves on each row. It is not part of this change.

## Tests

- **`HistorySuite`:** the window-parse tests become decode tests: a bad `kind`, a period not
  shorter than the span, and a `Chosen` key the table lacks. The rest (sharing, buckets, failures,
  identities) move to the typed read unchanged in what they assert.
- **New:**
  - each read asks its source;
  - `fallback` off draws nothing for an entity with no statistics, and on reads raw;
  - a statistics read with every point keeps every bucket;
  - the derived bucket gives the presets' buckets;
  - a viewer's key the table lacks is refused.
- **Pkl:** `progress.test.pkl` asserts today's params map, and is rewritten to the typed read. The
  wire snapshots are regenerated with `sbt dashboardSnapshotsUpdate`, and the diff read.

## Docs

- **ADR 0031:** a fetch is a typed shape per provider, not provider plus string params.
- **ADR 0033:** a fetch reads a variable by selecting its read by key.
- **`docs/terminology.md`:** **Window** (no longer a closed set), **Bucket**, and **Read** if the
  word is kept.
- **`docs/writing-dashboards.md` and `docs/writing-components.md`:** `.over("7d")` becomes
  `.over(h.raw(7.d))` or a preset.
- **The architecture doc:** the `history_during_period · statistics_during_period` edge stays
  true. A fetch's shape is ADR 0031's.

## Checks

- Re-run the popup timings above.
- Run `sbt fh-datastar-view/testFull`, or the `--exclude-tags=Slow` variant where no browser is
  available.
- Run CI-mode compile and scalafix.
