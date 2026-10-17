# Plan: automations that reconcile (#584)

The roadmap for #584, from #583 to rules that third-party authors can publish. **Not started.**
Work begins after #574, #575, #585 and #586 are merged. Each milestone is its own branch and PR,
and this file is kept current as decisions move.

## The ambition

The house is in the state the Pkl says it should be in. A rule says what lights, switches and
covers should look like given what is happening. fh makes it so after every change and after every
restart, and steps back when a person takes over. Behaviour like "these lights follow the sun", "a
relative light group" or "what a Hue dimmer does" is a Pkl package anyone can publish and reuse.

React is the model:
- the house's state is the DOM;
- a rule is a component's render;
- an event handler is a reducer;
- fh is the reconciler.

## Decided (in chat, 2026-10-17)

1. **fh runs the engine itself.** Rules are not compiled into HA automations. `Conditions.scala`
   stays the one interpreter of a condition, so a rule and a dashboard cannot disagree about one.
2. **Every running fh instance is a device in HA, registered through MQTT discovery.**
   - Its entities belong to that device: declared values, computed values, overrides and status.
   - An instance only ever creates, changes or deletes entities on its own device. That replaces
     #584's label-based ownership, under which two instances would delete each other's helpers.
   - HA has no API that creates a device on its own. The alternatives were rejected:
     - HA's mobile-app registration takes sensors only, and HA cannot send it a command.
     - A Python custom integration is one more thing to ship and keep in step with HA.
     - HA's storage helpers (`input_boolean/create`, …) cannot belong to a device, go through an
       undocumented API, and re-apply `initial` at every HA restart.
3. **An instance's identity:**
   - Running as an HA app (formerly add-on), the instance is the app.
   - A dev server generates an id once and keeps it in the workspace's `.fh/`.
   - The device identifier derives from it, so a dev server is a device of its own.
4. **Which instance runs rules is chosen on its device in HA.** A new dev instance starts with
   rules off, so starting a dev server never fights the app over the real lights. The control
   itself is Q3.
5. **Declared values live at the top level and in dashboards, merged at the top.** The same key
   declared twice with different settings is a build error.
6. **An entity that already has the id we would use, but is not on our device, is adopted, not
   taken over.**
   Dashboards and rules may read and call it, fh never changes or deletes it, and a warning is
   logged.
7. **Removal waits.** A declaration that disappears marks its entity orphaned, with the date.
   - It is deleted after X days, an X set in site.pkl.
   - Declaring it again within X days cancels the delete and keeps the value.
   - Pending deletes show as a sensor on the device and in the log.
8. **The engine's storage is SQLite**: under `/data` in the app and `.fh/` in dev. It holds
   overrides, orphan dates and the declared values.
9. **The Scala codegen is dropped**: `home`, `home-codegen`, `fh-codegen-plugin` and `fh-api`.
10. **Modules split by job** (M1).

## Commands back from HA

Not every entity needs one. A value only fh changes, like a computed "night" or the instance's
status, is a sensor. A command back is needed where something other than fh changes a value:

- HA's UI and app, voice assistants (`google_assistant` is set up here), and HA's own automations
  switching a declared value such as "guests";
- a virtual entity people operate like the real thing: a relative light group is a light;
- the switch that turns an instance's rules on or off (decision 4), and the override switches (M5).

fh's own dashboards do not need the round trip, since a tap on a declared value can go straight
to the engine. MQTT carries commands back for every entity kind above, which is part of why it
was picked.

## Model

- **Inputs:**
  - HA states;
  - the values on fh's own device;
  - the clock and the sun, from HA's configured location;
  - events. A button press is an event, not a state.
- **A rule** maps inputs to desired states for its targets: "hall light: on, 40 %".
  - It is pure, declared in Pkl, and evaluated at runtime with the predicates and CEL that
    dashboards already use (#583's tally included).
  - Its triggers are the entities its condition and its values read (ADR 0030's rule again), plus
    the clock when it reads time.
- **An event handler** changes only fh's own values, like a reducer. A dimmer press steps a
  "scene" value, and a rule turns that value into light states. Every effect on the house
  therefore goes through a rule and the reconciler.
- **The reconciler** compares desired with actual states and makes the calls that close the gap.
  At start it recomputes every rule and applies the result, which is what surviving a restart
  means.
- **One owner per target.** Two rules setting the same attribute of the same entity is a build
  error, not a fight at runtime. Priorities instead are Q4.
- **Overrides.**
  - Every call fh makes returns HA's `context`. A change with a context fh did not make came from
    a person or from something else.
  - That target is then overridden: stored, and shown as a switch on the device.
  - The rule leaves the target alone until the override clears: when the rule's desired value next
    changes, after a timeout, or when someone flips the switch.
  - Adaptive Lighting's "manual control" works this way.

## Reference behaviours

Each of these already runs in this HA through a third-party integration. A milestone is done when
fh can replace it.

| Behaviour | Today | Milestone |
|---|---|---|
| Light follows the time of day and the sun | `adaptive_lighting` | M6 |
| A Hue dimmer's buttons drive a set of lights | ZHA, RWL021/RWL022 | M7 |
| A relative light group | `relative_light_group` | M8 |

## Milestones

**M0: a call over a set.** #583, PR #586. It is the condition shape that rules reuse.

**M1: restructure, with no change in behaviour.**
- `fh-ha`: the wire format and the live mirror of HA. That is today's `ha-api` and `fh-domain`,
  plus `HaFeed`, `StateStore` and `ServiceCalls` from `fh-datastar-view`.
- `fh-pkl`: building Pkl. The dump, the packages, the site, and the vocabulary both sides share
  (`hass`, `query`, predicates).
- `fh-dashboard`: today's `fh-datastar-view` minus the parts above. Datastar is an implementation
  detail and leaves the name.
- `fh-automation`: the engine, the device and the storage. Empty until M2.
- `fh-app`: the running process. The wiring, HTTP, auth and the editor.
- Delete `home`, `home-codegen`, `fh-codegen-plugin`, `fh-api` and the codegen sections of
  CLAUDE.md. `benchmarks` follows `fh-dashboard`.
- Rename the `@fh-dashboard/` Pkl package once, here, since doing so breaks every workspace's
  imports (Q6).
- Update the architecture doc's "where each box lives", and the CLAUDE.md files.

Done when the suite passes, changed only by package names.

**M2: the instance as a device.**
- An MQTT connection. Credentials come from the Supervisor in the app and from `.env` in dev.
- Discovery with `availability`, so the device's entities are unavailable while fh is down.
- The instance's identity (decision 3).
- The device shows fh's version, links to fh's editor, and has a status sensor.

Done when the app and a dev server appear as two devices, and stopping either one marks its
entities unavailable.

**M3: declared values.**
- First kinds: toggle, choice, number and text.
- Declared at the top level and per dashboard (decision 5), with typed handles in Pkl:
  `q.entity(v.guests).stateIs("on")`, `c.select(v.mode)`. Options are known at build.
- Entities on fh's device stay out of the dump. Under `watch_registry` that also stops a loop:
  sync, registry change, dump refresh, rebuild, sync.
- Adoption (decision 6), and orphans deleted after X days (decision 7).

Done when a declared toggle keeps its value across an HA restart and an fh restart, and removing
it deletes it only after X days.

**M4: engine v1, rules over state.**
- Rules over HA states and declared values.
- Reconcile on every change and at start.
- Tracking of fh's own writes through `context`.
- One owner per target.
- Rules run only where they are switched on (decision 4).
- The SQLite store.
- The reconcile model's ADR.

Done when "while guests and evening, the hall light is on" turns the light back on after fh
restarts in the middle of the evening.

**M5: overrides,** as described in the Model. Done when turning the hall light off by hand keeps
it off until the condition next changes.

**M6: the clock and the sun.**
- Time and the sun's position become inputs.
- Time windows become computed values, such as #584's "night".
- The engine wakes on the clock, not only on changes from HA.

Done when Adaptive Lighting's behaviour for one room is an fh rule.

**M7: events.** Button presses and other HA events feed reducers. Done when a Hue dimmer's
on/off/dim/scene behaviour is a set of fh rules.

**M8: virtual entities.** Entities on fh's device whose commands the engine carries out: a light
that fans out to its members and keeps their relative brightness. Done when it replaces one
`relative_light_group`.

**M9: packages for other authors.**
- Reusable Pkl modules that produce rules and values.
- Per device model ("every RWL022 does this") or per behaviour ("these lights follow the sun").
- Published the way the authoring library is today.

Done when M6–M8's behaviours are packages a workspace imports.

## Open questions (recommendation first)

1. **The MQTT client.** `net.sigusr::fs2-mqtt` 1.0.1 is native to fs2 and cats-effect, but its
   last release was January 2024 and it speaks only MQTT 3.1.1. HiveMQ's Java client is maintained
   and speaks MQTT 5. Spike fs2-mqtt in M2, and fall back to HiveMQ behind the same interface.
2. **SQLite access.** Use doobie (`1.0.0-RC12`) over `sqlite-jdbc` (`3.53.4.0`) with one writer
   connection. Check that sqlite-jdbc's bundled native library loads on the app's Alpine (musl)
   aarch64 image.
3. **How rules are switched per instance.** A "Run rules" switch on each device. HA's own "disable
   device" would also take the device's values away, so a dev server's dashboards would lose
   their toggles.
4. **One owner per target, or priorities?** One owner.
5. **Dev instances.** They get their own copies of the declared values, with entity ids carrying
   a prefix the build knows (`switch.fh_dev_guests`). Dashboards on a dev server show the dev
   device's toggles.
6. **The name that replaces `@fh-dashboard/`.** `@fh/`.
7. **Where a declared value's truth lives.** In fh's store, published retained over MQTT so HA
   shows it after its own restart. The store wins when the two disagree.
8. **The app's identity.** Confirm in M2 what the Supervisor exposes (the slug, the container's
   hostname), and that it is stable across updates.
9. **Rights.**
   - The MQTT user decides what fh may publish.
   - The engine's calls go out under the machine token (ADR 0023), since no person is behind them.
   - Check that the app's Supervisor token can read the device registry.
10. **What outlives this plan.** Rewrite #584 as the roadmap issue, with one issue per milestone.

## Facts from the spike (live HA 2026.10.1, 2026-10-17)

- The entity and device registries record `created_at`, `modified_at` and `labels`.
- This HA has `mqtt` set up. It runs `adaptive_lighting`, `relative_light_group`, and ZHA with Hue
  RWL021/RWL022 dimmers.
- Storage helpers, for the record of why decision 2 rejected them:
  - `input_boolean/create` derives the id from the name and appends `_2` on a collision.
  - `update` replaces the whole config.
  - A label is a second call, so create and label together are not atomic.

## ADRs and docs this touches

- New ADR: automations reconcile (the Model section), in M4.
- ADR 0013 (dump): entities on fh's device are left out (M3).
- ADR 0015 (library structure): the package rename (M1).
- ADR 0021 (one entrypoint): declared values in the site (M3).
- ADR 0023 (access): who an engine call is attributed to (M4).
- ADR 0030 (subscribe to what we read): rules subscribe the same way (M4).
- `docs/architecture-rendering-pipeline.md`: section 7 (M1), and a sibling doc for the engine.
- `docs/terminology.md`: rule, reconcile, override, declared value and instance device, each when
  it lands.
- The CLAUDE.md files: modules and build commands (M1).
