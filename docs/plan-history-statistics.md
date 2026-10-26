# Plan: a chart of a day or longer reads HA's statistics, not every raw row

Agreed in chat on 2026-10-26, after measuring why the more-info history popup opens slowly.
**In progress.** One PR, `fh/view/history` only.

## Why

A popup waits for its fetches before it renders (architecture doc: every input is resolved
before the walk), so a slow history read is a slow popup. Today `History.fetch` asks
`history_during_period` for every window, and also asks `statistics_during_period`, in parallel,
until the learned `retention` covers the window. Then it keeps whichever covers more time
(`History.widest`) and thins the result to about 300 points with LTTB.

Measured against the live house over WS, for `sensor.ams_1a4e_p`, a power meter that reports
every few seconds:

| Read | Time | Rows | Size |
|---|---|---|---|
| Raw, 24 h | 439 ms | 23,691 | 847 KB |
| 5-minute statistics, 24 h | 43 ms | 287 | 27 KB |
| Hourly statistics, 7 d | 77 ms | 167 | |

Through a local `dashboardServe`:

| Popup open | Time |
|---|---|
| First open after start (engine warm-up, plus both reads while `retention` is unknown) | 1.85 s |
| Later cold opens | 100–480 ms |
| Warm opens (cache hit) | ~30 ms |

On the Pi, decoding, LTTB and drawing are several times slower. The raw read throws away 98% of
what it fetched, since the chart draws 300 points.

Statistics came in with #375 only to reach past purged raw rows. Nothing records a reason
against reading them first.

## The design

### Which source a window reads

| Window | Source | Points drawn |
|---|---|---|
| `1h` | raw | LTTB to 300 (5-minute statistics would give 12 points) |
| `24h` | 5-minute statistics, then a raw tail | ~288 |
| `7d` | hourly statistics, then a raw tail | ~168 |
| `30d` | hourly statistics, then a raw tail | 720, LTTB to 300 |
| any window with `points = "all"` | raw | every reading |

- **The raw tail.** A statistics bucket is written after its period ends (the newest 5-minute
  bucket lagged 1–5 minutes, measured). So after the statistics read, the same fetch reads raw rows
  from the last bucket's end to now, and appends them, so the line reaches now. The two reads run
  in sequence, because the tail starts where the statistics end. That is two round trips (43 ms
  plus a few minutes of rows) instead of one 439 ms read. Reading both in parallel with a guessed
  tail length was rejected: it needs a margin to tune, and a recorder backlog longer than the
  margin leaves a gap.
- **No statistics means raw.** An entity without a `state_class` has no statistics (the washer's
  countdown answered 0 rows). An empty statistics answer falls back to the raw read of the whole
  window, which is what such an entity gets today.
- **`points = "all"` stays raw.** It is a computation's input (the progress bar's peak), and a
  5-minute mean is not a reading. So a `c.fetch.history(e).over("30d")` holds only the days HA's
  recorder still keeps (`purge_keep_days`, 10 by default), where today the statistics fill the
  rest. Accepted, and said in `fetch.pkl`'s doc.
- **The value is the bucket's `mean`**, else a total sensor's `state`, as `Series.fromStatistics`
  does today, plotted at the bucket's end.

### What goes

- The `retention` `Ref`, `History.widest`, and the parallel race between the two reads. When
  statistics come first, nothing needs to know how far raw rows reach.
- **Retention** in `docs/terminology.md`, which describes only that mechanism.

### What stays

- `Window`, its cache buckets, and `SeriesKey`. The 24 h bucket is 5 minutes, the same as its
  statistics period.
- `Downsample` (LTTB), which now thins only `1h`, a raw fallback, and `30d`.
- `SeriesSource`, which already has both reads.

### Known cost: a mean flattens spikes

A 5-minute mean hides a short peak. A one-minute 2 kW kettle shows as about 400 W. Raw-plus-LTTB
keeps the peak today. Statistics also carry `min` and `max` per bucket, so a band around the mean
is possible later. It is not part of this change.

## Tests

`HistorySuite` asserts the mechanism this plan removes:

- "an unproven window asks both; a window retention covers asks raw";
- "retention is the greatest age seen, not the latest one";
- "an empty answer proves nothing";
- "the wider answer wins, which is how a purged window still draws";
- "history wins a tie, because its resolution is finer".

They are replaced (agreed), and so is `SeriesSuite`'s test of `Series.span`/`oldest`, which only
the retention mechanism used.

New tests:

- each window reads the source its row in the table names;
- the tail starts at the last bucket's end, and its rows follow the buckets in time order;
- empty statistics fall back to raw;
- `points = "all"` never reads statistics.

## Checks

- Re-run the WS measurement and the popup timings above, before and after.
- Run `sbt fh-datastar-view/testFull`, or the `--exclude-tags=Slow` variant where no browser is
  available.
- ADR check: no ADR owns the provider's source choice. ADR 0031 owns the fetch/draw split, which
  doesn't move, and ADR 0032 mentions LTTB only as a cap on what is drawn. The architecture doc's
  `history_during_period · statistics_during_period` edge stays true. Doc changes are
  `docs/terminology.md` (Retention removed), the scaladoc on `History` and `Window`, and
  `fetch.pkl`'s doc.

