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
4. **Rules are a dry run unless the instance is started with a flag that says otherwise.**
   - A dry run logs what the reconciler would call, and calls nothing.
   - The flag is an app option in the app, and a command-line flag on a dev server.
   - Two instances acting on the same lights is then a choice somebody made explicitly.
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
9. **The Scala codegen modules are ignored for now**: `home`, `home-codegen`, `fh-codegen-plugin`
   and `fh-api`. This work neither moves nor deletes them.
10. **Modules split by job** (M1).
11. **Competing rules are settled by priority**, as BACnet's priority array settles them (see
    Model and Prior art). A package's "follow the sun" and your own "movie mode" can therefore
    both target the living room, and the higher priority wins while its condition is true.

## Commands back from HA

Not every entity needs one. A value only fh changes, like a computed "night" or the instance's
status, is a sensor. A command back is needed where something other than fh changes a value:

- HA's UI and app, voice assistants (`google_assistant` is set up here), and HA's own automations
  switching a declared value such as "guests";
- a virtual entity people operate like the real thing: a relative light group is a light;
- the override switches (M5).

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
- **Priorities.** Every target has a priority array: one slot per priority level.
  - While its condition is true, a rule writes its desired value into its slot. When the
    condition goes false, it relinquishes the slot (empties it).
  - The target's desired value is the one in the highest-priority slot that isn't empty.
  - When every slot is empty, fh leaves the target alone (BACnet's "relinquish default"; Q3).
  - Two rules at the same priority on the same target is a build error. In BACnet the last write
    wins there, which is exactly the fight this design avoids.
  - The arrays are kept in the store, so they survive a restart.
- **Overrides are a priority level** (BACnet's "manual operator").
  - Every call fh makes returns HA's `context`. A change with a context fh did not make came from
    a person or from something else.
  - Such a change writes the value it set into the manual slot. Rules above that level still win
    (a smoke alarm turning lights on); rules below it wait.
  - The manual slot is relinquished when the rule it beat next changes its desired value, after a
    timeout, or when someone flips the override switch on the device.
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
- `fh-ha`: the live mirror of HA, which is `HaFeed`, `StateStore` and `ServiceCalls` from
  `fh-datastar-view`. It sits on `ha-api` and `fh-domain` (the wire format), which keep their
  names because the codegen modules depend on them (decision 9).
- `fh-pkl`: building Pkl. The dump, the packages, the site, and the vocabulary both sides share
  (`hass`, `query`, predicates).
- `fh-dashboard`: today's `fh-datastar-view` minus the parts above. Datastar is an implementation
  detail and leaves the name.
- `fh-automation`: the engine, the device and the storage. Empty until M2.
- `fh-app`: the running process. The wiring, HTTP, auth and the editor.
- `benchmarks` follows `fh-dashboard`.
- Rename the `@fh-dashboard/` Pkl package once, here, since doing so breaks every workspace's
  imports (Q5).
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
- Priorities, with overrides as the manual level arriving in M5.
- A dry run unless started with the flag (decision 4).
- The SQLite store.
- The reconcile model's ADR.

Done when "while guests and evening, the hall light is on" turns the light back on after fh
restarts in the middle of the evening.

**M5: overrides,** the manual priority level described in the Model. Done when turning the hall light off by hand keeps
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

1. **The MQTT client.** All three wrap into the same fs2 interface, and our traffic (a few
   messages a minute) makes their speed irrelevant. What separates them is maintenance and what
   they add to the jar and the Pi's memory.
   - `net.sigusr::fs2-mqtt` 1.0.1 runs on our own fs2 sockets and IO runtime, so it needs no wrapper
     and adds no threads. Its last release was January 2024, and it speaks only MQTT 3.1.1, which is
     a frozen protocol and enough for HA's discovery.
   - `com.hivemq:hivemq-mqtt-client` 1.4.1 (October 2026) is maintained and speaks MQTT 5. Its
     async API returns `CompletableFuture`s and reactive-streams publishers, which convert to fs2
     directly. It brings Netty, RxJava and JCTools, with their own threads.
   - Eclipse Paho (`org.eclipse.paho.client.mqttv3` / `mqttv5.client` 1.2.5) is small and has no
     dependencies, and its async client is callback-based. Its last release was July 2020, with
     about 400 open issues, and reconnecting is where those bugs are.

   Spike fs2-mqtt in M2, and fall back to HiveMQ behind the same interface. Paho is last, for its
   maintenance.
2. **SQLite access.** Use `com.armanbilge::porcupine` (0.0.2, July 2026), a Skunk-style library on
   cats-effect 3.7 and fs2 3.13. On the JVM it delegates to `sqlite-jdbc`; pin that driver
   ourselves to a current version (`3.53.4.0`), since porcupine brings in `3.46.1.3`. It is at 0.0.x,
   so doobie over the same driver is the fallback. Bundling:
   - `sqlite-jdbc` carries SQLite's native library for every platform inside its jar, and extracts
     the matching one at start. Nothing needs installing.
   - The app's image is Debian (`hassio-addons/debian-base`, glibc), and its jlink already
     includes `java.sql`.
   - The fat jar is built once for both arches, so it keeps the Linux x86_64 and aarch64 natives.
     An assembly rule can drop the others (Windows, Mac, FreeBSD, Android, musl) to save size.
3. **What one priority array covers, and what an empty one means.**
   - Per attribute: a "follow the sun" package then owns `color_temp` at a low priority while
     movie mode owns `brightness` above it. That is what Kubernetes' field ownership does.
     Attributes are coupled, though: a brightness implies "on", and "off" drops every other
     attribute. Recommendation: one array for on/off plus one per attribute, with "off" winning
     only from a slot at least as high as the attribute slots it clears.
   - Empty: fh does nothing, rather than applying a default value. A rule that wants a fallback
     declares it at the lowest priority.
4. **Dev instances.** They get their own copies of the declared values, with entity ids carrying
   a prefix the build knows (`switch.fh_dev_guests`). Dashboards on a dev server show the dev
   device's toggles.
5. **The name that replaces `@fh-dashboard/`.** `@fh/`.
6. **Where a declared value's truth lives.** In fh's store, published retained over MQTT so HA
   shows it after its own restart. The store wins when the two disagree.
7. **The app's identity.** Confirm in M2 what the Supervisor exposes (the slug, the container's
   hostname), and that it is stable across updates.
8. **Rights.**
   - The MQTT user decides what fh may publish.
   - The engine's calls go out under the machine token (ADR 0023), since no person is behind them.
   - Check that the app's Supervisor token can read the device registry.
9. **Tracking.** #584 is the roadmap epic. Each milestone gets its own issue when it starts.

## Prior art, and the words to borrow

These problems are old. Each source below has names worth reusing, so this project doesn't coin
its own. The words go into `docs/terminology.md` when they land.

- **BACnet** (ASHRAE 135), building automation. Writable points have a 16-level **priority
  array**; a writer **relinquishes** its slot; when all are empty the point falls back to its
  **relinquish default**. Level 8 is **manual operator** and level 1 is manual life safety. Some
  objects also have a **minimum on/off time**, an anti-flapping guard worth keeping for heating
  and fans. This is the closest match: our priorities, overrides and empty-array case are all in
  it. In this repo the word "relinquish" is already a session method (`Session.relinquish`), so the
  terminology entry has to keep the two apart.
- **Kubernetes controllers.** **Desired state** versus **observed state**, a **reconcile loop**,
  **drift**, **level-triggered** (act on what is, not on what changed, which is why a restart
  just works), and in server-side apply, **field managers** owning individual fields. That last
  one is the per-attribute question in Q3.
- **React.** **Reconciliation**, and **controlled versus uncontrolled** components, which is the
  override question in another form.
- **Production rule systems** (OPS5, CLIPS, Drools). The rules whose conditions are true at once
  form the **conflict set**, and **conflict resolution** picks among them, Drools mainly by
  **salience** (priority). Same problem, but they pick which rule fires, not which value a target
  gets.
- **Subsumption architecture** (Brooks, 1986), robotics. Behaviours in layers, where a higher
  layer **suppresses** a lower one's output. It's the same arbitration as a priority array, and
  where "behaviour" as a word for a reusable rule set (M9) comes from.
- **Feature interaction** (telephony, from the 1990s). Features correct on their own that
  misbehave together, such as call forwarding and call screening. It's the name for what M9
  packages will do to each other.
- **Smart-home rule conflicts.** HomeGuard (Chi et al.) calls it **cross-app interference** and
  detects it when an app is installed, which is our build error. The 2023 survey by Huang et al.
  gives a taxonomy of **direct** conflicts (two rules on one device) and **indirect** ones (via
  the environment: a heater rule against a window rule). Priorities settle direct conflicts only.
- **CSS cascade layers.** The authoring side of the same idea: `@layer` order beats specificity,
  so a package's styles sit in a lower layer than the page's. M9's packages could declare their
  priority band the same way.

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
- `docs/terminology.md`: rule, reconcile, desired state, priority array, relinquish, override,
  declared value and instance device, each when it lands.
- The CLAUDE.md files: modules and build commands (M1).
