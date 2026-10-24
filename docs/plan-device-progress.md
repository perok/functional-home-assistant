# Plan — a device's progress (#541, E3)

Step E3 of #541. E1 has landed (#596): every entity in the dump carries
`platform`, `translation_key` and `original_name`. E2 (a client clock, a
`between` bar, an `until` countdown) and E4 (vacuum, climate, media) are later
PRs.

The goal: `c.deviceProgress(dump.devices.washer)` shows how far along a device's
job is, and the author never has to know which sensor plays which part.

## 1. The roles table lives in the generator

- `fh.view.build.DeviceRoles` holds rows of `(platform, key) -> roles`. The key
  is the entity's `translation_key`. For an integration that sets none, the key
  is its `original_name`, and the row says so.
- It is one table for every device capability, not one per capability. Each
  role names its capability (`progress.remaining`), and a row can give one
  entity roles in several, so E4 adds rows and a capability class, not a second
  table. It is best effort: an integration with no rows is still usable through
  its entities.
- It is Scala, not `hass/roles.pkl`. Only the generator reads it, and it reads
  it before any Pkl runs, so a Pkl copy would be a second list kept in step by a
  test (the `HassVocabulary` situation) with no reader. An author wiring an
  integration the table does not know uses the dump's facts, not the table.
- Rows come from the #329 table's devices that have a job:
  - ConnectLife DW 60.2;
  - Bambu Lab P1S;
  - Electrolux PerfectCare 800, by `original_name`;
  - VW ID.4's `charging_time_left`.

  The Roborock gets no rows: its time-left sensors are consumables, not a job.
  Each row is checked against the live registry and the integration's source,
  and the PR lists both.
- The roles in E3 are `remaining`, `total`, `percent`, `status` and `phase`.
  `elapsed`, `start` and `end` arrive with E2, the first thing that reads them.

## 2. The generator writes `progress` onto the device

- `hass.Device` gains `hidden progress: Progress? = null`, and `hass.Progress`
  holds one `hass.SensorEntity? = null` per role.
- For each device, `PklDump` matches its entities against the table. Where any
  role matches, the device's class re-declares the property narrowed:
  `hidden progress: hass.Progress = new { remaining = e_…; status = e_… }`.
  This is the narrowing `colourTemp` already uses (ADR 0013, "One name,
  narrowed per entity"), so completion offers `progress` only on a device that
  has it.
- Only a `sensor` can fill a role. A row that matches another domain is
  skipped, and `PklDump.warnings` names it.
- Two entities for one role on one device: the role is dropped, and the
  warning names both. That is also what a device with several jobs looks like;
  one `progress` per job is E5 on #541.
- There is no `owner` (Q2). The card takes the device, and a hand-written card
  reading `d.progress` already holds `d`.
- These are static registry facts, so the content version does not churn
  (ADR 0013).

## 3. One card, two ways in

- `ProgressCard` gains `percent` and `phase`, and its tile's entity is derived:
  `remaining`, else `percent`, else `status`, else `phase`.
  `c.progress(remaining)` keeps its signature and its behaviour.
- `c.deviceProgress(d: hass.Device): ProgressCard` fills the role fields from
  `d.progress`. Each can be overridden in the chain: `.remaining(e)`,
  `.total(e)`, `.percent(e)`, `.status(e)`, `.phase(e)`. Both functions build
  the same card, so `c.progress` does not need a `hass.Progress` overload, which
  was the issue's last open question.
- **The bar** is the first of these that holds:
  1. `percent`;
  2. left + total, through `c.bar.remaining` (both units must have a fixed
     length);
  3. no bar.

  Start + end waits for E2's `between`. Elapsed + left waits for a device in
  the table that has it; none in this house does.
- **The countdown** is from `remaining`. End − now waits for E2.
- **The reading**, where there is no countdown, is the percent, else the
  status.
- **The second line** shows the phase, else the status.
- **The label** is the device's name.
- **A device with nothing at all**, no role and no override, fails the build.
  The error names the device and its sensors, and points at `.remaining(…)`.
  There is no `device_class` guess for a device the table does not know: its
  author gives the entities, through the overrides or `c.progress(e)`.
- `d.progress` stays public, for hand-written cards and for filtering:
  `dump.devices.all.filter((d) -> d.progress != null).map(c.deviceProgress)`.

Nothing here touches the live path: every choice is made at build time.

## Docs

- ADR 0013 gets a section on device capabilities: the roles come from a table
  in the generator and are narrowed onto the device's class. It also records why
  this card takes the device while a capability card takes its group: choosing
  the bar and the countdown is build-time logic either way, and a device with
  nothing at all is rare.
- `docs/writing-dashboards.md` covers `c.deviceProgress`, its overrides, and
  what to do for a device the table does not know.
- `docs/terminology.md` gets "role", if the word survives review.

## Tests

- `RegistryDumpSuite` / `PklDump`:
  - a device in the table gets a narrowed `progress`;
  - a device not in it gets none;
  - a role filled twice is dropped, with a warning;
  - a match on a non-sensor is skipped.
- `components.test.pkl`:
  - the bar order: percent beats left + total, and left + total without
    fixed-length units gives no bar;
  - the tile entity's fallback;
  - the overrides;
  - the build error for an empty device.
- The existing `c.progress` tests stay unchanged.

## Questions

2. **No `owner` on the new `hass.Progress`?** Recommend yes. The entity
   groups (`colourTemp`, `effects`, …) keep theirs. Add one here when a card
   takes `d.progress` on its own.
