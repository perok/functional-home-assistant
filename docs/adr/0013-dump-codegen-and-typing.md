# ADR 0013 — How the entity dump is generated and typed

- **Status:** Accepted — `RegistryDump` + `PklDump` are the only dump path
- **Date:** 2026-08-06
- **Scope:** `modules/fh-datastar-view` (build phase), `modules/ha-api`

See also ADR [0006](0006-pkl-authoring-track.md) (Pkl as the authoring language)
and [0010](0010-live-pkl-schema-endpoint.md) (the dump as the `@fh-home`
package). This ADR owns **how the dump is generated and what its types mean**;
0010 owns how it is packaged and delivered.

## Context

The dump is the reason a dashboard can say `dump.entities.light_kitchen` instead
of `"light.kitchen"`. It is generated from the live house on every build and
seeded as the `@fh-home` package, so every entity, area, floor and device is a
named, typed, dot-completing Pkl value and a typo is an eval error.

Two properties make it worth designing rather than just dumping JSON:

- **The types must say what an entity can DO**, not just what it is called. A
  light with no colour temperature and a light with one are different things to
  author against, and the difference is knowable at generation time.
- **The dump is content-addressed.** It is packaged as
  `fh-home@1.0.0-g<hash>` and `DumpRefresh` re-seeds it and re-evaluates every
  dashboard whenever that hash moves. What the dump carries is therefore a
  correctness question about rebuild frequency, not a matter of taste.

## Decision

Three stages, each owning one thing:

| Stage | File | Owns |
|---|---|---|
| fetch + join | `RegistryDump` | WHERE the data comes from, and WHAT is carried |
| render | `PklDump` | HOW it is typed, and generation-time validation |
| schema | `lib/hass.pkl` (+ `lib/hass-light.pkl`) | what the types MEAN — the rules |

The division that matters: **the generator emits data, the schema draws
conclusions.** `PklDump` writes `colourModes` and `supported_features`;
`hass.pkl` derives `supportsColour`/`supportsBrightness`/`supportsFlash`/
`supportsTransition` from them against the vendored constants. So "what counts
as a colour mode" lives in one place instead of being duplicated in Scala and
Pkl and drifting.

### Stage 1 — where the data comes from

| Data | Source |
|---|---|
| every entity + every attribute | `subscribe_entities`, first frame (`EntitiesEvent.Full`) |
| `hidden_by`, `entity_category`, `device_id`, `area_id` | `config/entity_registry/list` |
| device name / area / manufacturer / model | `config/device_registry/list` |
| areas (with `floor_id`) | `config/area_registry/list` |
| floors (with `level`) | `config/floor_registry/list` |

**The state snapshot is the spine, not the registry.** The registry lists every
entity that ever existed — 2296 against 1069 with state on the dev instance, the
difference being disabled ones, which no dashboard can render. A handful go the
other way (`sun.sun` and friends have state but no registry row), so the join is
a LEFT join *from states*, with registry fields defaulted when absent.

`RegistryDump.build` is pure — snapshot and registries in, dump JSON out — so
`RegistryDumpSuite` exercises the join with no HA anywhere near it.

#### Why not the Jinja template

The original implementation rendered one big template through `/api/template`
(a port of `script.sh`). It was replaced, not extended, because the ceiling is
hard:

- **`/api/template` truncates output at 262144 characters**, and the template
  already rendered **228689** — 87% of the budget. Entity attributes alone are
  260 KB across 1069 entities, so "carry more attributes" did not fit; even
  adding `device_id` (~43 KB) overflowed. The cap is not configurable.
- **`entity_category` is not reachable from a template at all.**
  `state_attr(e, 'entity_category')` is `None` — it lives in the entity registry
  only, and it classifies **552 of 1063** entities as `config`/`diagnostic`,
  making it the sharpest noise filter available.
- **`devices()` is undefined** on this HA version, so whole-device grouping meant
  one Jinja call per entity.

The registries have no cap, no Jinja, and `ha-api` already had the entity/device
commands (the codegen track consumes them); the area and floor registry commands
were added here. Before the swap, both paths were run against one live instance
and diffed: **1069 entities each, zero mismatches** across `entity_id`,
`domain`, `friendly_name`, `area_id`, `floor_id`, `id_hidden` — and the registry
path additionally produced 201 devices, 12 group entities and the
`entity_category` classification. The template path and its comparison tool were
then deleted; there is one dump.

### Stage 1b — what is carried, and what deliberately is not

Attributes are filtered to `RegistryDump.CapabilityAttributes`, widenable per
home through `FH_DUMP_ATTRIBUTES` (comma-separated, additive).

The binding reason is **not** staleness and not file size — it is the content
version. A volatile attribute in the dump re-hashes the package on every change,
turning a dimmed light into a full rebuild of every dashboard. That also rules
out carrying a build-time snapshot of live values at all: it was measured at
only +7% file size and rejected anyway, because size was never the constraint.
Live values are read runtime-side as JSONata over the SSE stream (phase
discipline).

The set was checked empirically rather than guessed: watching
`subscribe_entities` delta frames for 180s, **23 of 24 candidates never appeared
in a single change**. The one that did — `entity_picture`, 4 changes across 4
entities — is excluded, because camera and media_player picture URLs carry a
rotating `access_token`. **Re-run that check before widening the set.** It is
the only way to know, and a wrong entry is silent: the dashboards keep working,
they just rebuild forever. (`color_mode` is the clearest exclusion — the
template path baked it onto every light; it is a live value.)

### Stage 2 — how it is typed

#### Shared classes carry identity; per-entity classes carry capability

`hass.Entity` and its domain subclasses declare **identity and registry facts
only** — what every entity has, even if null: `entity_id`, `domain`,
`friendly_name`, `area_id`, `floor_id`, `id_hidden`, `device_id`,
`entity_category`, `members`.

Capability attributes are declared on a class generated **per entity**, and only
on the entities that report them:

```pkl
class E_light_hue_bibliotek extends hass.LightEntity {
  icon: String = "mdi:bulb"
}

class E_light_plug extends hass.LightEntity {}
```

(That class is also where a modelled domain's capability groups are narrowed —
see below.)

The point is what is NOT there: reading a capability an entity lacks is

```
Cannot find property `min_color_temp_kelvin` in object of type `dump#E_light_plug`.
```

rather than a `null` an author has to remember to check. **The dump answers
"does this entity have X" by whether X is there.** Two consequences, both
spike-verified on pkl-core 0.32.1: the declared types are not nullable (the
entity reports the capability, so the value exists), and precision costs nothing
generically — `E_light_plug` is still assignable to `List<hass.LightEntity>` and
`List<hass.Entity>`, so area member lists and card factories taking a domain
type are unaffected. What generic code loses is access to capabilities, which is
correct: a `hass.LightEntity` in the abstract genuinely has none.

This is why the domain classes are `open`, and why the generated module is ~24%
larger (944 KB against 762 KB for a shared-nullable-field shape).

#### A capability whose values co-occur is ONE nullable group

Once a domain is modelled, its capabilities move off the per-entity class and
onto the domain class as **groups** — one nullable object per concept, not
several independent nullable fields:

```pkl
class ColourTemp { min_kelvin: Int; max_kelvin: Int }

open class LightEntity extends Entity {
  hidden colourModes: Listing<ColorMode> = new Listing {}
  hidden supported_features: Int = 0
  hidden colourTemp: ColourTemp? = null
  hidden effects: Effects? = null
}
```

Flat nullable fields were rejected earlier; grouping removes the reason:

- **The group is the predicate.** `l.colourTemp != null` needs no parallel
  `supportsColourTemp` flag that could disagree with the value beside it.
- **One guard covers every value in the concept.** `min_kelvin` without
  `max_kelvin` is unrepresentable, rather than two nullables that can drift.
- **An unguarded read is an ERROR, not a null.** Values are reached *through*
  the group, so a missed guard gives `Cannot find property 'min_kelvin' in
  object of type 'Null'` at build time — where a flat `min_color_temp_kelvin:
  Int?` would have yielded `null`, rendered as a blank slot, and shipped.
- `?.` and `??` work, so `l.colourTemp?.min_kelvin ?? default` is available when
  a default is genuinely wanted.

A capability with no associated values stays a derived Boolean — a group with no
fields would carry nothing. Together the two halves give the pattern the dump
exists for: ask generically, then use the value the predicate just proved is
there.

```pkl
allLights.filter((l) -> l.supportsColourTemp).map((l) -> l.colourTemp.min_kelvin)
```

##### One name, narrowed per entity

The generated entity class **re-declares the group non-null** — the domain class
types it `ColourTemp?`, the entity that has one types it `ColourTemp`:

```pkl
class E_light_hue_bibliotek extends hass.LightEntity {
  hidden colourTemp: hass.ColourTemp = new { owner = e_light_a; min_kelvin = 2000; max_kelvin = 6535 }
}
```

So a dashboard naming a specific entity passes the group straight to something
that demands a present one — `c.slider(dump.entities.light_a.colourTemp)`,
no `!!` and no guard — while the same value reached through a
`List<hass.LightEntity>` still meets `ColourTemp?` and still has to be guarded.
Both were verified on pkl-core 0.32.1: the narrowed read resolves, and
`x: ColourTemp = plug.colourTemp` on an entity without one fails with "Expected
value of type `ColourTemp`, but got `null`".

A parallel `hasColourTemp` nullable + a non-null `colourTemp` twin would do the
same job and was rejected: it is two names for one fact, `has*` reads as a
Boolean while holding data, and the two could be emitted inconsistently.
Narrowing needs no second name and cannot disagree with itself.

##### A group carries its owner, so one argument is the whole request

Each group holds `owner`, the entity whose capability it is. A card therefore
takes **the group alone** and still knows its subject:

```pkl
c.slider(l)               // the domain's default axis — a light's brightness
c.slider(l.colourTemp)    // colour temperature, bounds from the light itself
c.effectPills(l.effects)
```

`Slider` accepts `SlideAxis = hass.Entity|hass.ColourTemp` and pattern-matches
with `is` to derive its entity, key, bounds and tracked attribute. Three
properties fall out, and each was a defect in an earlier shape:

- **The entity is named once.** There is no second parameter, so
  `slider(a, b.colourTemp)` — two entities disagreeing — is unrepresentable
  rather than merely discouraged.
- **The wrong choice is visible BEFORE evaluation.** `c.slider(l.colourTemp)`
  on a light without a range is a `ColourTemp?` where `ColourTemp` is required:
  pkl-lsp reports "Nullability mismatch" on the line, no evaluation involved.
- **Capabilities are discovered on the entity.** Typing `l.` lists
  `colourTemp`, `effects` — completion verified against pkl-lsp 0.8.0 — so a
  new capability appears with no new API name to learn.

The owner is a back-reference from the entity's own class to its own const,
which is fine for the same reason `members` edges are: Pkl resolves module-level
consts lazily and order-independently.

`lightControls(l)` remains the "give me what this light supports" shortcut — a
`when` per capability, deciding which cards exist at all, which no single-node
mechanism can do.

##### Shapes considered

The API above is the fourth attempt; the earlier three are recorded because each
failed for a reason that is not obvious until tried.

| Shape | Why not |
|---|---|
| `(c.slider(l)) \|> c.withColourTemp(l.colourTemp)` — a `Mixin<Slider>` | `l` appears twice and the two can disagree. Discovery requires already knowing the mixin exists, and the list grows per capability. Static checking was fine — this is where that requirement was learned. |
| `c.slider(l).colourTemp()` — a builder method | Reads best and names `l` once, but the capability is hidden in the method body, so nothing is checkable: the method is offered by completion on a *cover* slider, and failure is a runtime `throw`. |
| `c.light(l).colourTemp()` — an entity-first builder per domain | Same loss of static checking, and every domain builder must re-export the whole card catalogue that makes sense for it (`brightness`, `toggle`, `effects`, …), so the surface grows by domain × card kind. |
| `(c.slider) { entity = l; on = entity.colourTemp }` — late binding | Works at eval, but pkl-lsp resolves the self-reference to the DECLARED property type, so it flags the **valid** line as well as the invalid one. False positives are worse than silence: they train authors to ignore the squiggles. |

The through-line: **a capability passed as a VALUE through a signature is
checkable; a capability named by a method, selector or self-reference is not.**
The editor is the only place an author finds out before evaluating, so the API
is shaped around what static analysis can see. (Reads are still unchecked —
`plug.colourTemp.min_kelvin` gets no diagnostic — which is a further reason for
capabilities to cross API boundaries as arguments rather than be dotted into.)

#### The HA domain model is vendored, not guessed

Predicates are derived from HA's own light model, copied into
`lib/hass-light.pkl` (author-facing) and `HaLight.scala` (generator-facing): the
`ColorMode` string enum and the `LightEntityFeature` bits (`EFFECT=4`,
`FLASH=8`, `TRANSITION=32`).

**Vendoring these is safe.** An earlier draft of this ADR claimed the numeric
feature flags "drift between HA releases" — that was wrong. HA's
`*EntityFeature` IntFlags are **append-only**: a new feature takes a new bit, a
removed one leaves its bit vacant, and existing values are never renumbered,
because they are persisted in entity state attributes and read by the frontend.
The vacant `1` and `2` in `LightEntityFeature` are exactly that — the removed
`SUPPORT_BRIGHTNESS` and `SUPPORT_COLOR_TEMP`, dropped when colour modes
replaced them.

Checked rather than assumed, against the live instance's 48 lights: the `EFFECT`
bit agreed with `effect_list` presence **48/48**, and every observed
`supported_features` value (0, 4, 40, 44) decoded with **no unaccounted bits**.
Re-run that check when syncing to a newer HA.

Two copies exist because the two sides need the constants at different times —
codegen derives predicates, an author names a mode or tests a bit in their own
expressions. `HaLightSuite` reads the values back out of the Pkl source and
asserts they match the Scala ones, so the pair cannot drift silently.

Vendoring also lets the dump type its colour modes as `Listing<hass.ColorMode>`
rather than `Listing<String>`, so an author's `"colour_temp"` is an eval error
instead of a comparison that silently never matches.

#### Group members are references, not id strings

Two HA mechanisms name an entity's members — `attributes.entity_id` (the Light
Group helper) and `attributes.group_entities` (Zigbee/ZHA groups). They nest.
Both become one `members` edge, emitted as references to the other entities'
`e_*` consts:

```pkl
const hidden e_light_relative_stue: E_light_relative_stue = new {
  entity_id = "light.relative_stue"
  members = List(e_light_spisebordlys, e_light_skyconnect_sittegruppe)
}
```

Pkl resolves module-level `const`s lazily and order-independently, so forward
references work and a cycle would not hang (both spike-verified). That is what
makes `e.members[0].members` walk a group nested inside a group — verified
against the live instance, reaching 11 bulbs two levels below
`light.relative_stue`. A member id absent from the dump is dropped rather than
emitted dangling: a missing `e_*` would be an eval error in every dashboard.

#### Types the generator will not invent

`PklDump.pklTyped` infers each property's Pkl type from its JSON value and skips
anything it cannot state faithfully — objects, mixed arrays, explicit nulls — so
an author never meets a field whose type is a guess. An explicit null in
particular is indistinguishable from not having the attribute.

Generated identifiers are backticked only when Pkl's own lexer says the plain
form is illegal (`org.pkl.parser.Lexer.maybeQuoteIdentifier`), so the keyword set
cannot drift from the evaluator, and string values are escaped `\` first so a
`\(` in a device name cannot become interpolation.

### Stage 2b — completeness is checked at GENERATION time

**Pkl cannot check this and should not be asked to.** A required property with
no value is lazy, so a half-filled group evaluates fine until something reads
the missing field, and the error then blames the class definition rather than
the entity that caused it.

`PklDump.warnings` holds the whole picture at generation time and reports what
Pkl cannot: a light reporting one kelvin bound without the other, or claiming
the `color_temp` mode with no range at all. The group is **omitted rather than
half-emitted**, and both callers (`DashboardBuild.prepareDumps`,
`ServerApp.refreshOnce`) print the warnings. Reported, never fatal — one odd
integration must not stop the whole house's dump from building.

This is the general rule for the pipeline: **anything checkable about the whole
house belongs in `PklDump.warnings`**, and the type it would have produced is
withheld rather than emitted broken.

## Domain coverage

Three levels, all working today; the difference is only how much a dashboard
knows:

- **Modelled** — a domain class with capability GROUPS and derived predicates,
  its HA constants vendored.
- **Typed** — a domain class, so a value can be typed `List<XEntity>` and a card
  factory can demand the domain; capabilities still arrive on the per-entity
  class.
- **Generic** — `GenericEntity` + the per-entity class. Every attribute in the
  carried set still lands, typed, on the entity that reports it. **This is the
  fallback that keeps every unmodelled domain working**, which is what makes the
  modelling incremental: a domain gains groups when someone models it, and
  nothing else moves.

| Domain | Level | HA spec |
|---|---|---|
| `light` | **Modelled** — `colourTemp`, `effects` groups; colour/brightness/flash/transition predicates; `ColorMode` + `LightEntityFeature` vendored | [light](https://developers.home-assistant.io/docs/core/entity/light) |
| `sensor` | Typed | [sensor](https://developers.home-assistant.io/docs/core/entity/sensor) |
| `switch` | Typed | [switch](https://developers.home-assistant.io/docs/core/entity/switch) |
| `number` | Typed | [number](https://developers.home-assistant.io/docs/core/entity/number) |
| `select` | Typed | [select](https://developers.home-assistant.io/docs/core/entity/select) |
| `cover`, `fan` | Generic — but both have a `sliderSpec` row, so `c.slider` works on them | [cover](https://developers.home-assistant.io/docs/core/entity/cover), [fan](https://developers.home-assistant.io/docs/core/entity/fan) |
| `climate`, `media_player`, `vacuum`, `lawn_mower`, `water_heater`, `humidifier`, `alarm_control_panel`, `lock`, `valve`, `remote`, `siren`, `camera`, `weather` | Generic — **bitmask domains**, the next candidates: each has an `*EntityFeature` IntFlag to vendor exactly as `light`'s was | [climate](https://developers.home-assistant.io/docs/core/entity/climate), [media_player](https://developers.home-assistant.io/docs/core/entity/media-player), [vacuum](https://developers.home-assistant.io/docs/core/entity/vacuum), [lawn_mower](https://developers.home-assistant.io/docs/core/entity/lawn-mower), [water_heater](https://developers.home-assistant.io/docs/core/entity/water-heater), [humidifier](https://developers.home-assistant.io/docs/core/entity/humidifier), [alarm_control_panel](https://developers.home-assistant.io/docs/core/entity/alarm-control-panel), [lock](https://developers.home-assistant.io/docs/core/entity/lock), [valve](https://developers.home-assistant.io/docs/core/entity/valve), [remote](https://developers.home-assistant.io/docs/core/entity/remote), [siren](https://developers.home-assistant.io/docs/core/entity/siren), [camera](https://developers.home-assistant.io/docs/core/entity/camera), [weather](https://developers.home-assistant.io/docs/core/entity/weather) |
| `binary_sensor`, `button`, `text`, `date`, `datetime`, `time`, `event`, `scene`, `todo`, `calendar`, `update`, `image`, `notify`, `device_tracker`, `geo_location`, `air_quality` | Generic — no feature bitmask; `device_class` (already carried) is most of what they have | [binary_sensor](https://developers.home-assistant.io/docs/core/entity/binary-sensor), [button](https://developers.home-assistant.io/docs/core/entity/button), [text](https://developers.home-assistant.io/docs/core/entity/text), [date](https://developers.home-assistant.io/docs/core/entity/date), [datetime](https://developers.home-assistant.io/docs/core/entity/datetime), [time](https://developers.home-assistant.io/docs/core/entity/time), [event](https://developers.home-assistant.io/docs/core/entity/event), [scene](https://developers.home-assistant.io/docs/core/entity/scene), [todo](https://developers.home-assistant.io/docs/core/entity/todo), [calendar](https://developers.home-assistant.io/docs/core/entity/calendar), [update](https://developers.home-assistant.io/docs/core/entity/update), [image](https://developers.home-assistant.io/docs/core/entity/image), [notify](https://developers.home-assistant.io/docs/core/entity/notify), [device_tracker](https://developers.home-assistant.io/docs/core/entity/device-tracker), [geo_location](https://developers.home-assistant.io/docs/core/entity/geo-location), [air_quality](https://developers.home-assistant.io/docs/core/entity/air-quality) |
| `conversation`, `stt`, `tts`, `wake_word`, `assist_satellite`, `ai_task`, `infrared`, `radio_frequency` | Generic — voice/AI plumbing, no dashboard use yet | [entity index](https://developers.home-assistant.io/docs/core/entity) |

Helper domains an instance also exposes (`input_number`, `input_select`,
`input_boolean`, `automation`, `script`, `person`, `zone`, `sun`, `group`) have
no entity spec page; they are Generic, and their capability attributes
(`min`/`max`/`step`/`options`) are already in the carried set.

### Adding a domain

The recipe, in the order the pieces depend on each other:

1. **Read the domain's spec page** (table above) and find its `*EntityFeature`
   IntFlag and its capability attributes.
2. **Carry the attributes**: add them to `RegistryDump.CapabilityAttributes` —
   *after* checking them against live `subscribe_entities` deltas, per the rule
   above. Skip any that are live values.
3. **Vendor the constants** in `lib/hass-<domain>.pkl` + `Ha<Domain>.scala`, and
   extend `HaLightSuite`'s comparison to the new pair so they cannot drift.
   (Import with an `as` alias — Pkl binds an import to its FILE name, and
   `hass-media-player` is not an identifier.)
4. **Model the schema** in `hass.pkl`: an `open class <Domain>Entity extends
   Entity` with the co-occurring values as nullable GROUP classes and the
   yes/no capabilities as predicates DERIVED from the raw emitted data. Do not
   bake conclusions into the generator.
5. **Emit the data** in `PklDump`: a `schemaFields` branch for the always-present
   values, a `schemaGroups` branch for the narrowed group declarations, and the
   attribute names the domain now owns listed in `SchemaModelled` so
   `capabilityDecls` stops declaring them on the per-entity class too.
6. **Validate in `PklDump.warnings`**: what would make a group half-populated?
   Report it and omit the group.
7. **Dispatch**: add the domain to `PklDump.entityType`.
8. **Test**: a `PklDumpCapabilitySuite` case per group (complete, half,
   claimed-but-absent, unmodelled-attribute-still-falls-through) and a
   `PklBuildSuite` probe that evaluates the guarded access for real.

Nothing else moves. Every other domain keeps its per-entity class untouched.

## Consequences

- `hass.pkl` carries `device_id`, `entity_category`, `members`, a `Device` class
  and `Floor.level`; its domain classes are `open` and hold no capability fields
  of their own beyond a modelled domain's groups.
- The test fake (`FakeHomeAssistant`) answers the four registry commands with
  EMPTY lists. That is faithful, not a stub: a fixture declares entities and
  attributes, never registry rows, and the join runs from the state snapshot, so
  every fixture entity still reaches the dump — just with no area/floor/device.
- `Slider` gained a `valueAttr` override so a second control on the same entity
  (colour temperature) tracks the value it writes rather than the domain's
  default position attribute.

## Alternatives rejected

- **Capabilities as Pkl mixins.** Does not work, verified on pkl-core 0.32.1.
  Pkl has **no multiple inheritance** (`class C extends A, B` is a parse error),
  so capabilities cannot be separate types a class combines — and the capability
  lattice is not linear (a light may support `color_temp` only, or `rgb` only,
  or both), so a single-inheritance chain cannot express it either. A Pkl
  `Mixin<T>` is a **function `(T) -> T`**, an amendment applied to a value: it
  cannot introduce a property the class does not already declare (applying one
  that sets an undeclared `min_kelvin` fails with "Cannot find property
  `min_kelvin` in object of type `Light`"). So a mixin can set what exists but
  cannot carry a capability, and it creates no type to dispatch on.

  The Pkl maintainers' own mixin recipe (apple/pkl discussion #332) was
  reproduced and confirms this rather than contradicting it: its `Config` class
  declares **every** property up front and the mixins only set values — the
  "multiple inheritance" is of composed VALUES, not of types. Applied to
  entities, a light that receives no colour-temp mixin still exposes
  `min_color_temp_kelvin = null`, which is the nullable-shared-field shape this
  ADR moved away from. The trade is real (mixins would drop ~1069 generated
  classes and the 24% size growth) and is rejected because absence-typing is the
  property we wanted. Where the recipe DOES fit is one layer up, composing
  CARDS, where every slot is declared on the card class and the question is
  which amendments to apply. A mixin was in fact the first shape tried for
  capability cards and was replaced — see "Shapes considered" above for why the
  capability travels as an argument instead.
- **Chunk the template into several `/api/template` calls** and merge in Scala.
  Smaller change, but it keeps Jinja, keeps a cap to manage as the house grows,
  and still cannot reach `entity_category`.
- **Read `/api/states` over REST** for the attributes. Uncapped and it works,
  but `subscribe_entities` returns the same data as `Map[String, Json]` over the
  connection the app already holds — one mechanism instead of two.
- **A build-time snapshot of live values.** Measured at +7% file size and
  rejected on the content-hash rule above, not on size.
