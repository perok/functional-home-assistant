# Plan — a device's progress (#541, E3)

Step E3 of #541. E1 has landed (#596): every entity in the dump carries
`platform`, `translation_key` and `original_name`. This PR is stacked on #629,
which gives the workspace a `components.pkl` with `defaults` (ADR 0015).

The goal: `c.progress(dump.devices.washer)` shows how far along a device's job
is. The author never has to know which sensor plays which part, and can teach
the library about their own integrations in one place: `defaults.progress` in
their `components.pkl`.

The work, in order:

1. **E3**: the rules, the heuristic and `c.progress(device)`. Built: sections
   1 and 2 below, with their docs and tests.
2. **E3b**: a total from history, for a device that reports only time left.
   Built: section 3 below.

Later, on #541: E2 (a client clock, start/end bars), E4 (vacuum, climate,
media), E5 (several jobs per device), and running/idle.

## 1. The rules: `defaults.progress`

One section per role. Each element is a rule: words, a `new { … }` that
narrows, or one entity.

```pkl
// components.pkl
amends "@fh-dashboard/components.pkl"

defaults {
  progress {
    countdown {
      "finish in"                                           // words, any integration
      new { platform = "miele"; key = "program_remaining" }  // every field given must hold
      dump.entities.washer_time_left                        // exactly this entity
    }
    ignore { dump.entities.car_service_inspection_days }
  }
}
```

- The roles are `countdown`, `total`, `percent`, `phase`, `status` and
  `ignore`. A misspelt role fails at build time.
- **Words** match when every word of the rule is among the entity's
  `translation_key` and `original_name` words; `_` and `-` count as spaces.
- **`new { … }`** takes `platform`, `key` (an exact `translation_key` or
  `original_name`), `device_class` and `unit`. Each section's `default` is that
  class, which is what lets a bare `new { … }` sit beside words and entities
  (spiked: without it Pkl cannot tell what to build).
- **The most specific rule wins**, both for which role an entity plays and
  among an entity's rivals for one role: an entity, then a `new { … }`, then
  words. At one level, `ignore` beats every other role.
- **A rule only narrows.** Each role keeps its shape, and an entity rule that
  names an entity of the wrong shape is a build error:

  | role | must be |
  |---|---|
  | `countdown`, `total` | a sensor with a fixed-length time unit |
  | `percent` | a `%` sensor with no device class |
  | `phase`, `status` | a sensor with no unit |

- Your sections add to the shipped ones, which are words only. Assigning a
  section (`countdown = new { … }`) replaces them.

  ```pkl
  countdown { "remaining" "left" "to end" }
  total     { "duration" "length" }
  percent   { "progress" }
  phase     { "phase" "stage" }
  status    { "status" "state" }
  ignore    { "total" "last" "lifetime" }
  ```

- In code, the same for every integration:
  - a running total (`state_class` `total` or `total_increasing`) plays no
    role;
  - a countdown about charging (named charge or charging) takes the device's
    battery level as its bar, a battery not named target or limit; with
    several countdowns and a battery, the charging one wins;
  - where two entities fit one role: the one sharing the most words with the
    countdown ("current program duration" over "auto-dose duration"), else
    the plainer name ("Cycle phase" over "Cycle sub phase"), else the one not
    marked diagnostic, else neither.
- `entity_category` is a tie-break, never a filter: the Electrolux's "Time to
  end" and every Roborock entity are diagnostic.

## 2. The progress and the card

```pkl
class Progress {
  device: hass.Device
  left: hass.SensorEntity?      // the countdown
  percent: hass.SensorEntity?   // a percentage bar: the device's own, or the battery
  total: hass.SensorEntity?     // a bar of `left` out of this; never with `percent`
  status: hass.SensorEntity?    // what it is doing: the phase, else the status
}
```

- `c.roles.progress(d): Progress?` is null unless it finds a countdown or a
  bar. It is on `c`, not on the device, because the dump cannot see the
  workspace, and in the `c.roles` namespace because it is data, not a card
  (E4 adds `c.roles.vacuum(d)` there). Filtering:
  `dump.devices.all.filter((d) -> c.roles.progress(d) != null)`.
- `c.progress(x: hass.Device|hass.SensorEntity)`, like `c.select` and
  `c.entitySlider`, which already take several kinds of input.
  - A sensor is today's card: `c.progress(sensor)`, `.total` and `.status`
    keep their behaviour and tests.
  - A device: the card carries `device` and `defaults` (ADR 0015's pattern)
    and works its parts out with `progressOf`, as `c.roles.progress` does. One with no progress fails the
    build, naming the device and its sensors and pointing at `.left(…)`.
  - Overrides: `.left(e)`, `.percent(e)`, `.total(e)` (which also drops a
    found `percent`, so the bar is the one asked for) and `.status(e)`.
- `c.roles.progress` picks the one bar: a `percent`; else `left` out of `total`,
  when both units have a fixed length; else the battery, for charging; else
  `left` out of its peak (E3b). The card shows what it is given.
- The tile's entity is `left`, else the percent sensor, whose reading then
  shows. The second line is `status`. The label is the device's name.

The heuristic run against this house's live registry (218 devices,
2026-10-24):

| device | bar | countdown | text |
|---|---|---|---|
| ConnectLife dishwasher | left of `current_program_duration` | remaining time | program phase |
| Bambu P1S | `print_progress` % | remaining time | current stage |
| Roborock S8 | `cleaning_progress` % | – | status |
| VW ID.4 | battery level | charging time left | charging state |
| Electrolux washer | E3b | time to end | cycle phase |
| OnePlus phone | E3b | remaining charge time | battery state |

No other device matched. The VW's climatisation countdown is a second job
(E5), and loses because only the charging one has a bar.

## 3. E3b: a total from history

The Electrolux reports only time left. Its last wash started at 101 minutes
and counted down to 0, so the total is the highest value since the countdown
last read 0. HA's recorder already holds it. Builds on #632, which names a
provider read a **fetch** (`docs/terminology.md`).

**A fetch as a CEL input.** An expression value (ADR 0034) today is a literal, a
tally or a condition. It gains a fourth kind, a fetch, read by name in CEL:

```pkl
// the bar's `of`: a live CEL slot on the countdown, with its own input
new Slot {
  entityId = left.entity_id
  transform = peakSinceZero                     // CEL, below
  values { ["past"] = c.fetch.history(left) }  // `.over("7d")` for another window
}
```

- **`c.fetch`** is the namespace of provider reads, apart from `q.`'s candidate
  queries: `c.fetch.history(e).over(w)`, a window of `1h`, `24h` (the default),
  `7d` or `30d`, or a node variable. The default covers the longest jobs here
  (a print, a car's charge); a shorter one breaks "since it last read 0".
- **Two scopes.** A node's `expressionValues` are shared by its slots, as
  today. A slot's own `values` are new: a slot brings its inputs, so a function
  can hand out a finished slot. A name in both is a build error. Binding is per
  slot already: a slot is connected only to the names its CEL reads.
- **Its CEL type is the provider's**: history's is `list(list(double))`, the
  `[epochMillis, value]` points, so a misuse fails the build. The value is the
  answer the history provider caches per 5-minute period, with every reading
  (`points = "all"`): thinned to a chart's ~300, a day of minute rows kept 100
  where a wash started at 101.
- **Read before CEL runs, in both paths.** A value fetch counts among the
  node's fetches, so a render's pre-read (`FetchSnapshot`) and its render key
  carry it unchanged. The signal-update path already pre-reads the fetches of
  the nodes it updates (`readsForPull(touchedIds, …)`); `signalFrame` is now
  handed them, and `signalsFor` passes them to the CEL.
- **What wakes it** is the slot's own entity, the countdown, as any live slot.
  A fetch wakes nothing, and nothing watches its cache: the next countdown
  change reads the next period's answer.
- A fetch value may not be read by a `once` slot, and its parameters are
  bounded like a fetch slot's (ADR 0023: what it reads joins
  `Dashboard.fetchedEntities`).

**The bar.** `of` is the countdown's highest value since it last read 0, or
its current value if higher, which covers a job whose first reading the
recorder has not written yet:

```cel
cel.bind(zeros, past.transformList(i, p, p[1] <= 0.0, p[0]),
  cel.bind(since, size(zeros) == 0 ? 0.0 : math.greatest(zeros),
    math.greatest(past.transformList(i, p, p[0] > since, p[1]) + [num(state)])))
```

(`transformList` filters and maps in one: this CEL has no `filter`/`map`
macros.)

It is a signal like `value`, so the fill still runs in the browser and the bar
never redraws. `c.roles.progress` marks it (`sinceStart`) after a percent, a
total and the battery: the washer and the phone's charge get one. The sensor
form, `c.progress(sensor)`, keeps no bar without `.total`.

- Measured against this house with the server's exact call: a countdown's
  history takes 15–30 ms for 24 h or 7 d (one row a minute while running). An
  idle countdown does not change, so it fetches only when a page draws it.
- Not built: an elapsed-time bar. A pause keeps counting as elapsed, and
  telling paused from running takes each integration's own words. A user can
  write one as CEL over a status's fetch.
- Not built: pushing a fetch whose cache period ends with no entity change.
  Designed when something needs it.

## Docs

- ADR 0013 records why device capabilities come from rules in `defaults`
  rather than being narrowed onto the dump's device classes.
- ADR 0015's defaults gain the `progress` group as the second example.
- E3b: ADR 0034 (a fetch as an expression value, and slot-scoped values), ADR
  0031 (a fetch is also a CEL input), the architecture doc (the signal path's
  CEL reads fetches) and terminology (expression value).
- `docs/writing-dashboards.md` covers `c.progress(device)`, `c.roles.progress`,
  the rules and the overrides.
- `docs/terminology.md` gets "role".

## Tests

- `components.test.pkl`, one fact per rule:
  - each kind of rule (words, `new { … }`, entity), and that the most specific
    wins;
  - a rule cannot loosen a role's shape;
  - running totals, the charging pair, each tie-break, and null for a device
    with nothing;
  - the bar order, the overrides, and the build error;
  - a rule set in a workspace `components.pkl` reaches `c.progress(device)`.
- The registry snapshot of the six devices above, as a fixture, reproduces
  the table.
- The existing `c.progress` tests stay unchanged.
