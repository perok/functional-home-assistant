# Plan: a progress card starts from its sensor (#541)

Agreed in chat on 2026-10-26 (Q38, Q39, Q41). The build cannot tell from static facts which of a
device's countdowns belong to something running and which are consumables: a car's charging and
climatisation countdowns are both primary minutes, a vacuum's four "… time left" are diagnostic
seconds, and the washer's own time to end is diagnostic minutes. So a card that starts from a
device guesses. The author picks the sensor instead, and the card finds the rest beside it.

## The API

```pkl
c.progress(dump.entities.washer_time_to_end)        // a countdown
c.progress(dump.entities.printer_print_progress)    // or a percentage
c.progress(dump.entities.car_charging_time_left).status(dump.entities.car_charging_state)
```

- **The sensor decides the reading and the bar by its shape:** a duration is a countdown, a `%`
  sensor a percentage. Anything else is a build error.
- **The rest comes from its device:** a percentage beside a countdown, a total, a phase else a
  status, each ranked against the sensor given (#638's `choose`; a tie chooses none). The device's
  name is the label. A sensor on no device gets nothing beside it.
- **The bar:** a percentage, else the countdown out of a total, else the countdown out of where it
  started, from history (Q39: a countdown alone gets this bar too).
- Every part can still be named by hand: `.percent`, `.total`, `.status`.

## Changes

- **Generator:** every entity on a device links back to it (`hidden device: Device?` on
  `hass.Entity`). Entities are `const` objects, so each device is a `const d_<slug>` that the
  `devices` namespace points at (spiked: the cycle resolves, equality terminates, nothing renders
  it). ADR 0013.
- **Gone:** `c.progress(device)`, `c.roles` and `roles.pkl`, `progressOf`, the `Progress` class,
  `defaults.progress.countdown`. The card's own hidden parts are what it found.
- **Words:** "job" goes from the code and docs; the card is about one sensor and the ones beside it.
- **Tests:** the device-form facts become sensor-form facts proving the same ranking; the two
  facts pinning "a countdown alone has no bar" flip (Q39); the generator test checks the link.
- **Docs:** ADR 0013, ADR 0015, terminology (**Role**), `writing-dashboards.md`, #541's body.
