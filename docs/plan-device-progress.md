# Plan — a device's progress (#541, E3)

Step E3 of #541. E1 has landed (#596): every entity in the dump carries
`platform`, `translation_key` and `original_name`.

The goal: `c.progress(dump.devices.washer)` shows how far along a device's job
is. The author never has to know which sensor plays which part, and can teach
the library about their own integrations in one place.

The work, in order:

1. **`site.c`**: the components, configured once in `site.pkl`. Proved first,
   then the starter site, demos and docs move to it at once.
2. **E3**: the rules, the heuristic and `c.progress(device)`.
3. **E3b**: a total from history, for a device that reports only time left.

Later, on #541: E2 (a client clock, start/end bars), E4 (vacuum, climate,
media), E5 (several jobs per device), and running/idle.

## 1. `site.c`: the components, configured in `site.pkl`

Configuration that every card must see cannot live in the dump or in the
library, because neither can import the workspace. So the components become a
value that `site.pkl` amends:

```pkl
// @fh-dashboard/site.pkl
hidden c: components = import("components.pkl")

// site.pkl
amends "@fh-dashboard/site.pkl"

c { devices { progress { countdown { "finish in" } } } }
dashboards {
  ["home"] { card = c.progress(dump.devices.washer) }   // c is in scope
  ["kitchen"] = import("kitchen.pkl")
}

// kitchen.pkl
amends "@fh-dashboard/entry.pkl"
import "site.pkl"
local c = site.c
```

- `components.pkl` gains `devices: DeviceRules`, and its functions pass it to
  the modules that read it (`progressMod.progress(x, devices)`). Pkl carries no
  configuration into another module by itself.
- Spiked on pkl-core 0.32.1:
  - a function of the amended `c` sees the amended `devices`;
  - `kitchen.pkl` importing the `site.pkl` that imports it evaluates.
- **Still to prove**, before any E3 code:
  - pkl-lsp completion and diagnostics through `site.c.` and a `local c`;
  - the server's import graph (`Analyzer.importGraph`), the file watcher and
    the editor all live with the cycle;
  - `fh push --slug` and the laptop flow.
- A dashboard that imports `@fh-dashboard/components.pkl` directly keeps
  working, with the library's own rules. The starter site, the demo
  dashboards and `writing-dashboards.md` move to `site.c` in the same change.

## 2. The rules

One section per role. Each line is a rule: words, a `new { … }` that narrows,
or one entity.

```pkl
c {
  devices {
    progress {
      countdown {
        "finish in"                                           // words, any integration
        new { platform = "miele"; key = "program_remaining" }  // every field given must hold
        dump.entities.washer_time_left                        // exactly this entity
      }
      ignore { dump.entities.car_service_inspection_days }
    }
  }
}
```

- The roles are `countdown`, `total`, `percent`, `phase`, `status` and
  `ignore`. A misspelt role fails at build time.
- **Words** match when all of them appear in the entity's `translation_key` or
  `original_name`; `_` counts as a space.
- **`new { … }`** takes `platform`, `key` (an exact `translation_key` or
  `original_name`), `device_class` and `unit`.
- **The most specific rule wins** on an entity: an entity, then a `new { … }`,
  then words.
- **A rule only narrows.** Each role keeps its shape:

  | role | must be |
  |---|---|
  | `countdown`, `total` | a sensor with a fixed-length time unit, not a running total |
  | `percent` | a `%` sensor with no device class |
  | `phase`, `status` | a sensor with no unit |

- Your sections add to the shipped ones, which are words only:

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
  - a countdown about charging takes the device's battery level as its bar;
  - where two entities fit one role: the one sharing the most words with the
    countdown ("current program duration" over "auto-dose duration"), else
    the plainer name ("Cycle phase" over "Cycle sub phase"), else the one not
    marked diagnostic, else neither.
- `entity_category` is a tie-break, never a filter: the Electrolux's "Time to
  end" and every Roborock entity are diagnostic.

## 3. The progress and the card

```pkl
class Progress {
  hidden device: Device? = null    // the label, and the build error
  left: SensorEntity? = null       // the countdown
  bar: Bar? = null                 // Percent | LeftOfTotal | LeftOfPeak (E3b)
  text: SensorEntity? = null       // the phase, else the status
}
```

- `c.progressOf(d): Progress?` is null unless it finds a countdown or a bar.
  It is on `c`, not on the device, because the dump cannot see `site.pkl`.
  Filtering: `dump.devices.all.filter((d) -> c.progressOf(d) != null)`.
- `c.progress(x: hass.Device|hass.SensorEntity|hass.Progress)`, like
  `c.select` and `c.entitySlider`, which already take several kinds of input.
  - A sensor is a `Progress` with only `left`. `c.progress(sensor)`, `.total`
    and `.status` keep today's behaviour and tests.
  - A device with no progress fails the build. The error names the device and
    its sensors, and points at `.left(…)`.
  - `.left(e)`, `.total(e)`, `.percent(e)` and `.text(e)` override one part.
- `progressOf` picks the bar, so the card does not walk an order of preference.
  The bar is the first of: a `percent`; `left` out of `total`, when both units
  have a fixed length; the battery, for charging; `left` out of its peak (E3b).
- The countdown is `left`. The second line is `text`. The label is the
  device's name, else the sensor's.

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

## 4. E3b: a total from history

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

- ADR 0021 (`site.pkl`) and ADR 0015 (the facade) gain `site.c`.
- ADR 0013 records why device capabilities come from rules in `c` rather than
  being narrowed onto the dump's device classes.
- ADR 0027 (CEL) gains the stage over history, in E3b.
- `docs/writing-dashboards.md` covers `site.c`, `c.progress(device)`, the
  rules and the overrides.
- `docs/terminology.md` gets "role".

## Tests

- `site.c`: a dashboard in its own file sees a rule set in `site.pkl`; a
  dashboard importing the library directly sees the shipped rules.
- `components.test.pkl`, one fact per rule:
  - each kind of rule (words, `new { … }`, entity), and that the most specific
    wins;
  - a rule cannot loosen a role's shape;
  - running totals, the charging pair, each tie-break, and null for a device
    with nothing;
  - the bar order, the overrides, and the build error.
- The registry snapshot of the six devices above, as a fixture, reproduces
  the table.
- The existing `c.progress` tests stay unchanged.

## Questions

11. **Is `site.c` its own PR, ahead of this one?** Recommend yes: it changes
    how every dashboard imports the library, and it is worth reviewing on its
    own.
