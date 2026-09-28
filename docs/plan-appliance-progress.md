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

## It is not a bug in the integration either

Worth settling before designing around it. The integration is
[`albaintor/homeassistant_electrolux_status`](https://github.com/albaintor/homeassistant_electrolux_status)
2.3.5 on `pyelectroluxocp` 0.1.3, and it builds one entity per appliance capability — the
entity `unique_id`s carry the OCP property name verbatim (`…-timeToEnd-root-…`), so the
entity list *is* the property list.

Read off `GET /api/diagnostics/config_entry/<entry>`:

- The appliance advertises **91 capability keys**. The only duration-shaped ones are
  `timeToEnd`, `applianceTotalWorkingTime`, `totalWashingTime`, `totalCycleCounter` and
  `totalWashCyclesCount`. There is no `runningTime`, no programme duration, and no cycle
  start timestamp. (52 of the 91 are `applianceCareAndMaintenance*` slots.)
- Its **38 reported properties** say the same.
- The **24 programmes** under `userSelections/programUID` carry only their selectable
  options — spin speed, temperature, steam, prewash, time-manager level. No per-programme
  duration.
- The entity registry has **39 washer entities, none disabled and none hidden**, so nothing
  is being withheld by default either.

The two five-figure `min` sensors are lifetime odometers: raw
`applianceTotalWorkingTime: 8391600` and `totalWashingTime: 8391600` seconds, both surfaced
as `139860.0` min. They are the same number and they do not reset.

By contrast the dishwasher (Home Connect) publishes
`sensor.dishwasher_current_program_duration` = `169` beside
`sensor.dishwasher_current_program_remaining_time` = `42`, which is the entire reason one
card has a bar. Nothing to file upstream; the appliance simply does not report it.

## The countdown is also the estimate

The useful finding, and it changes the shape of the fix. `timeToEnd` is not only a
countdown — while the machine is idle it holds the **selected programme's estimated
length**. Two probes minutes apart:

| when | `applianceState` | `cyclePhase` | `timeToEnd` |
|---|---|---|---|
| mid-cycle | `Running` | `Spin` | `3.0` min |
| after the door opened | `Off` | `Unavailable` | `187.0` min (raw `11220` s) |

187 minutes is the selected Cotton/Cottons programme at 60 °C — an estimate for a cycle
that has not started, not a leftover from the one that just finished. So the denominator
**does** exist in the data; it is the same sensor, read at the right moment.

### The rule is a latch at cycle start, not a running maximum

An earlier draft of this plan proposed `peak' = if (reading > previousReading) reading else
peak`, on the argument that a countdown only decreases within a cycle so any increase is a
new cycle. The idle estimate falsifies it: selecting a shorter programme is a **decrease**
while idle — 187 → 14 for the rapid wash — so the peak would stay at 187 and a 14-minute
cycle would open its bar 92 % full. A decrease while idle and a decrease while running are
indistinguishable by value alone.

So the denominator has to be latched on the transition **into** running, which means
something has to know which word that is. The card deliberately does not ("this card does
not know which of a given appliance's states means *running*, and the states are the
integration's own words in the integration's own capitalisation"), so whoever supplies the
denominator has to supply that too. That is the real question in every option below.

The one refinement worth keeping: raise the latched value if `timeToEnd` later exceeds it.
Electrolux revises the estimate upward for an unbalanced load, and a bar stepping back is
the honest rendering of that.

## Why it cannot go where values currently come from

A latch is a fold over time, and every existing route from an entity to a slot is
explicitly not one:

- `Transform.Simple` membership is "a static lookup and TOTAL", decided at build time over
  one `EntityState` (ADR 0028).
- A CEL transform reads one entity's *current* state; lookups are same-entity, same-tick
  (ADR 0027). A latch also needs a *second* entity — the countdown's value gated on the
  status entity — which same-entity lookups forbid outright.
- `EntityState` carries the current value. Its one time-derived field, `contentVersion`, is
  a stamp saying *something moved*, not an accumulation.

## Options

### D. Home Assistant computes it (recommended)

A trigger-based template sensor latches the estimate at cycle start and republishes it as
an ordinary duration sensor. Nothing in this repo changes except the dashboard source.

```yaml
# configuration.yaml
template:
  - triggers:
      - trigger: state
        entity_id: sensor.electrolux_washing_machine_appliancestate_2
        to: "Running"
        id: start
      - trigger: state
        entity_id: sensor.electrolux_washing_machine_timetoend_2
        id: tick
    sensor:
      - name: "Washing machine programme duration"
        unique_id: washing_machine_programme_duration
        device_class: duration
        unit_of_measurement: min
        state: >
          {% set left = states('sensor.electrolux_washing_machine_timetoend_2') | float(0) %}
          {% set held = this.state | float(0) %}
          {% if trigger.id == 'start' %}{{ left }}
          {% elif is_state('sensor.electrolux_washing_machine_appliancestate_2', 'Running')
                  and left > held %}{{ left }}
          {% else %}{{ held }}{% endif %}
```

`start` captures the estimate the moment the machine begins; `tick` only ever raises it,
and only while running, which is the unbalanced-load case. `this.state` is the sensor's own
previous value — safe here because a trigger-based template entity registers no listeners
of its own, so the self-reference cannot loop.

Then, after a dump refresh (the editor's **↻ refresh dump**):

```pkl
c.progress(dump.entities.sensor_electrolux_washing_machine_timetoend_2)
  .total(dump.entities.sensor_washing_machine_programme_duration)
  .status(dump.entities.sensor_electrolux_washing_machine_appliancestate_2)
  .label("Vaskemaskin")
```

`device_class: duration` + `unit_of_measurement: min` resolves `durationSeconds` to 60
(`hass/sensor.pkl`'s `DURATION_SECONDS`), which is what turns `hasBar` on.

- **Survives a restart.** HA's own docs: "The state, including attributes, of trigger-based
  sensors and binary sensors is restored when Home Assistant is restarted." That is the
  weakness option A cannot fix without a history client we do not have.
- **The running vocabulary lives with the appliance**, next to the machine that speaks it,
  rather than being pushed into a card that is meant to work for any appliance.
- **Cost**: per-appliance YAML outside this repo, so the next appliance is configured by
  hand again; and a `.total(…)` that quietly points at a derived sensor rather than a real
  one, which nothing in the dashboard makes visible.

### A. The latch lives in the runtime, read as a synthetic attribute

`StateStore` holds `Ref[Map[String, Latch]]` for an opted-in set of entities, applies the
rule on ingest, and writes the result onto the stored `EntityState` as an attribute under a
reserved name. The card opts in and reads it with the ordinary `Simple.Attr` — so signals,
`contentVersion`, render inputs and the diff all keep working untouched, because from the
slot's side nothing new is happening. The opt-in set is derived from the dashboard next to
`watchedEntities` / `referencedEntities` (ADR 0030), where the precedent already exists.

- **Every appliance is configured the same way**, in the dashboard, which is the argument
  for doing it here at all.
- **Cost**: a time-dependent value in a store documented as current-value-only; a reserved
  attribute name that is not in the dump, so an author cannot see it; and the author must
  still name the running state and the status entity on the card
  (`.totalFromCountdown(status = …, running = "Running")`), which is the same knowledge
  option D puts in YAML, just relocated.
- **Loses the latch on a server restart mid-cycle**: it re-seeds at whatever is left and
  the bar reads near-empty until the next cycle. Recoverable from HA's recorder in
  principle — `ha-api` has no history endpoint at all today, REST or WS.

### B. The latch lives in the browser, in `localStorage`

No server change: the card already computes its fill in client-side JS from two signals.
Rejected unless both A and D are refused — it is per-device, so two tabs can disagree about
the same machine; the rule ends up in a template string that nothing tests; and a first
visit from a new phone mid-cycle still shows an empty bar.

### C. The author declares a literal total

`.totalMinutes(180)`. Fifteen lines, no new concepts, and a lie: the 24 programmes range
from a 14-minute rapid wash to over three hours.

### E. A bar from the cycle phase

Worth recording as considered and rejected. The appliance *does* declare its phase
vocabulary — `PREWASH`, `WASH`, `RINSE`, `SPIN`, `DRAIN`, `STEAM`, `DRY`, `ANTICREASE`,
`UNAVAILABLE` — so a discrete N-step bar is possible without any duration at all. But the
phases present and their order depend on the programme (prewash and steam are options,
`DRY` only on a washer-dryer), and they are wildly unequal in length, so the bar would move
in lurches and be wrong about how far along the cycle is. A phase is better rendered as
what it is: text, which `.status(…)` already does.

## Recommendation

**D**, with **A** as the answer if the latch should be this runtime's job rather than Home
Assistant's. D wins on the one case that matters most — it is right on the first paint
after a reload *and* after a restart — and it puts the "which word means running" knowledge
next to the appliance instead of into a card that is supposed to be appliance-agnostic. A
is the better answer only if several appliances end up needing this, at which point the
per-appliance YAML is the thing being paid twice.

## Open, for the maintainer

1. D or A — does the latch belong to Home Assistant or to this runtime?
2. If D: does `.total(…)` pointing at a derived sensor deserve to be visible in the card's
   API (`.estimatedTotal(…)`, say), or is a comment in the dashboard source enough?
3. If A: is a reserved, undumped attribute name acceptable as the seam, or should the latch
   be a first-class slot source instead — bigger, but visible in the type?
