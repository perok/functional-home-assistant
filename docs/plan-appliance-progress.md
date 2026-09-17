# Plan — a bar for an appliance that reports no programme length

The washing machine card shows the countdown and no bar. The dishwasher's shows both.
That is the card doing what it says it does, not a rendering fault — and the fix is a
decision about where a **denominator** may come from, which is why this is a plan and not
a patch.

## The symptom, and why it is not a bug in the card

`ProgressCard.hasBar` is `total != null && total.durationSeconds != null`, so a card built
without `.total(…)` renders the countdown alone. The washer is built without one because
there is nothing to point it at. `lib/components/progress.pkl` already says so in its own
module doc ("**The bar is optional because the data is**"), so nothing here is surprising;
what has changed is that the absence is no longer acceptable.

## What the instance actually reports

Read off the live instance (a read-only `GET /api/states`), washer mid-cycle:

| entity | state | unit | what it is |
|---|---:|---|---|
| `sensor.electrolux_washing_machine_timetoend_2` | `3.0` | min | the countdown — the card's subject |
| `sensor.electrolux_washing_machine_appliancestate_2` | `Running` | — | the status line |
| `sensor.electrolux_washing_machine_cyclephase_2` | `Spin` | — | phase; declares no `options` |
| `sensor.electrolux_washing_machine_totalwashingtime_2` | `139860.0` | min | lifetime odometer |
| `sensor.electrolux_washing_machine_appliancetotalworkingtime_2` | `139860.0` | min | lifetime odometer |
| `number.electrolux_washing_machine_starttime_2` | `0` | min | *delay* start, not length |

There is no per-cycle duration under any name. The two five-figure `min` sensors are
odometers — they are the same number, and they do not reset. By contrast the dishwasher
(Home Connect) publishes `sensor.dishwasher_current_program_duration` = `169` beside
`sensor.dishwasher_current_program_remaining_time` = `42`, which is the entire reason one
card has a bar and the other does not.

So: the missing total is not a wiring mistake in the workspace, and no amount of looking
harder at the integration will produce one.

## The one honest denominator

The countdown's own value at the top of the cycle. Within a cycle it only decreases, so an
increase *is* the cycle boundary:

```
peak' = if (reading > previousReading) reading else peak
```

`max` is the obvious spelling and it is wrong: a 3 h cottons cycle followed by a 14 min
rapid wash leaves the peak at 180, and the short cycle opens two-thirds full. Reset-on-
increase is total, needs no knowledge of which `appliancestate` word means running — the
card deliberately has none — and handles the one mid-cycle increase that really happens:
Electrolux revises `timetoend` upward for an unbalanced load, and the bar stepping back is
the honest rendering of that.

## Why it cannot go where values currently come from

The rule is a fold over time, and every existing route from an entity to a slot is
explicitly not:

- `Transform.Simple` membership is "a static lookup and TOTAL", decided at build time over
  one `EntityState` (ADR 0028). A peak is neither.
- A CEL transform reads one entity's *current* state; lookups are same-entity, same-tick
  (ADR 0027).
- `EntityState` carries the current value. Its one time-derived field, `contentVersion`, is
  a stamp saying *something moved*, not an accumulation.

Adding a peak therefore adds a kind of value the runtime does not have yet. That is the
decision being asked for, and it is the reason this is not simply implemented.

## Options

### A. The peak lives in the runtime, read as a synthetic attribute

`StateStore` holds `Ref[Map[String, Peak]]` for an opted-in set of entities, applies the
rule above on ingest, and writes the result onto the `EntityState` it stores as an
attribute under a reserved name. The card opts in (`.totalFromCountdown()`) and reads it
with the ordinary `Simple.Attr` — so signals, `contentVersion`, render inputs and the
diff all keep working untouched, because from the slot's side nothing new is happening.

The opt-in set is derived from the dashboard, next to `watchedEntities` /
`referencedEntities` (ADR 0030) — the precedent for "a set the dashboard declares about
its own entities" already exists and is already plumbed to the runtime.

- **Right after a reload**, which is the case the card's doc calls out: correct. The server
  is long-lived and has watched the whole cycle.
- **Every viewer agrees**, because the peak is server truth like everything else.
- **Cost**: a derived, time-dependent value in a store documented as current-value-only,
  and a reserved attribute name that is not in the dump — so an author cannot see it and
  the card has to be the only thing that names it.
- **Loses on a server restart mid-cycle**: the peak seeds at whatever is left, and the bar
  reads near-empty until the next cycle. Recoverable later from HA's recorder; there is no
  history client today (`ha-api` has no history endpoint at all, REST or WS).

### B. The peak lives in the browser, in `localStorage`

No server change: the card already computes its fill in client-side JS from two signals, so
the same expression could keep a stored peak. Survives a reload, which is the objection the
card's doc raises against a session-scoped mark.

Rejected unless A is refused: it is per-device, so two tabs can show different bars for the
same machine; it puts the cycle-boundary rule in a template string where nothing tests it;
and a first visit from a new phone mid-cycle still shows an empty bar.

### C. The author declares a literal total

`.totalMinutes(180)`. Fifteen lines, no new concepts, and a lie: every programme is a
different length, and the card's doc already rejects inventing a denominator.

### D. Home Assistant computes it

A template sensor latching the per-cycle maximum, and the card gets `.total(…)` with no
code change here at all. It is arguably where it belongs — HA owns appliance state and has
the recorder — but it is configuration outside this repo, so it fixes the washer and leaves
the next appliance to be fixed by hand again.

## Recommendation

**A**, with **D** noted as the zero-code escape hatch if the answer should live in HA
instead. A is the only option that is right on the first paint after a reload for every
viewer, and the attribute seam keeps the whole value path downstream of it unchanged.

## Open, for the maintainer

1. A or D — does the peak belong to this runtime or to Home Assistant?
2. If A: is a reserved, undumped attribute name acceptable as the seam, or should the peak
   be a first-class slot source instead (bigger, but visible in the type)?
3. What should the bar do while the peak is only seeded — server just restarted, or the
   very first cycle after this ships? Empty bar, or no bar until the peak has seen an
   increase?
