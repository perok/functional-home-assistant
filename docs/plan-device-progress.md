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
   Next.

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

- `c.progressOf(d): Progress?` is null unless it finds a countdown or a bar.
  It is on `c`, not on the device, because the dump cannot see the workspace.
  Filtering: `dump.devices.all.filter((d) -> c.progressOf(d) != null)`.
- `c.progress(x: hass.Device|hass.SensorEntity)`, like `c.select` and
  `c.entitySlider`, which already take several kinds of input.
  - A sensor is today's card: `c.progress(sensor)`, `.total` and `.status`
    keep their behaviour and tests.
  - A device: the card carries `device` and `defaults` (ADR 0015's pattern)
    and works its parts out with `progressOf`. One with no progress fails the
    build, naming the device and its sensors and pointing at `.left(…)`.
  - Overrides: `.left(e)`, `.percent(e)`, `.total(e)` (which also drops a
    found `percent`, so the bar is the one asked for) and `.status(e)`.
- `progressOf` picks the one bar: a `percent`; else `left` out of `total`,
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
last read 0. HA's recorder already holds it.

- A new stage over a query's answer: **CEL over the history points**, for the
  `history` provider that charts use. The CEL environment already loads the
  math, lists and bindings extensions. The shipped recipe:

  ```cel
  cel.bind(since, math.greatest([0] + points.filter(p, p[1] == 0.0).map(p, p[0])),
    math.greatest([0.0] + points.filter(p, p[0] > since).map(p, p[1])))
  ```

- The bar sits in `c.iff(left > 0)`. A page asks only for the queries of the
  branches it shows, so an idle device never fetches history.
- Measured against this house with the server's exact call: a countdown's
  history takes 15–30 ms for 24 h or 7 d (one row a minute while running). A
  dense power sensor takes 0.5–3.4 s, so the cost is rows, not calls. Each
  answer is cached for 5 minutes and shared by every viewer.
- HA writes history in batches, so a fetch at a job's first moment can miss
  the first reading. The bar then shows 0% until the 5-minute cache expires.
- **To check first:** whether a query slot can feed the bar's `of` signal.
  Query values are never pushed.
- Not built: an elapsed-time bar. A pause keeps counting as elapsed, and
  telling paused from running takes each integration's own words. A user can
  still write one as CEL over a status's history.

## Docs

- ADR 0013 records why device capabilities come from rules in `defaults`
  rather than being narrowed onto the dump's device classes.
- ADR 0015's defaults gain the `progress` group as the second example.
- ADR 0027 (CEL) gains the stage over history, in E3b.
- `docs/writing-dashboards.md` covers `c.progress(device)`, `c.progressOf`,
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
