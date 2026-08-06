# ADR 0013 — The dump comes from the WebSocket registries, not a Jinja template

- **Status:** Accepted — `RegistryDump` is the wired dump source. `DataDump`
  (the Jinja template) stays in tree only as `DumpCompareApp`'s reference point
- **Date:** 2026-08-06
- **Scope:** `modules/fh-datastar-view` (build phase), `modules/ha-api`

See also ADR [0006](0006-pkl-authoring-track.md) (Pkl as the authoring language)
and [0010](0010-live-pkl-schema-endpoint.md) (the dump as the `@fh-home` package).
This ADR owns where the dump's DATA comes from; 0010 owns what is done with it.

## Context

`DataDump` renders one big Jinja template through HA's `/api/template` and keys
the result into `{floors, areas, entities}`. It is a direct port of the original
`script.sh` and it works — but it had run out of room, and the ceiling is hard:

- **`/api/template` truncates output at 262144 characters.** The existing
  template already renders **228689** on the dev instance — 87% of the budget.
  All entity attributes across 1069 entities are 260 KB *on their own*, so
  "carry more attributes" was not a matter of taste; it did not fit. Adding just
  `device_id` (1069 × ~40 chars ≈ 43 KB) also overflows. The cap is not
  configurable.
- **`entity_category` is not reachable from a template at all.**
  `state_attr(e, 'entity_category')` is `None` — the value lives in the entity
  registry only. It classifies **552 of 1063** entities on the dev instance as
  `config`/`diagnostic`, which makes it the single sharpest noise filter
  available, and the template could not see it.
- **`devices()` is undefined** on this HA version; only the per-entity
  `device_id()` exists, so whole-device grouping meant one Jinja call per entity.

## Decision

Build the dump from the **WebSocket registries plus the `subscribe_entities`
snapshot**. It is the wired source; the template path stays only as the
comparison reference.

| Data | Source |
|---|---|
| every entity + every attribute | `subscribe_entities`, first frame (`EntitiesEvent.Full`) |
| `hidden_by`, `entity_category`, `device_id`, `area_id` | `config/entity_registry/list` |
| device name / area / manufacturer / model | `config/device_registry/list` |
| areas (with `floor_id`) | `config/area_registry/list` |
| floors (with `level`) | `config/floor_registry/list` |

None of these are capped, none involve Jinja, and `ha-api` already had the two
entity/device commands (the codegen track consumes them). The area and floor
registry commands were added here.

**The state snapshot is the spine, not the registry.** The registry lists every
entity that ever existed — 2296 against 1069 with state — the difference being
disabled ones, which no dashboard can render. A handful go the other way
(`sun.sun` and friends have state but no registry row), so the join is a LEFT
join *from states*, registry fields defaulted when absent.

### Capabilities are per-entity, not nullable fields

The shared schema classes (`hass.Entity`, `hass.LightEntity`, ...) declare
**identity and registry facts only** — the things every entity has, even if null:
`entity_id`, `domain`, `friendly_name`, `area_id`, `floor_id`, `id_hidden`,
`device_id`, `entity_category`, `members`.

Capability attributes are declared on a class the dump generates **per entity**,
and only on the entities that report them:

```pkl
class E_light_light_hue_06a306_bibliotek_light extends hass.LightEntity {
  effect_list: Listing<String> = new Listing { "off"; "colorloop" }
  min_color_temp_kelvin: Int = 2000
  max_color_temp_kelvin: Int = 6535
  supported_color_modes: Listing<String> = new Listing { "color_temp"; "xy" }
}

class E_light_plug extends hass.LightEntity {
  supported_color_modes: Listing<String> = new Listing { "onoff" }
}
```

The point is what is NOT there. `light.plug` has no `min_color_temp_kelvin`
property at all, so reading one is a Pkl error —

```
Cannot find property `min_color_temp_kelvin` in object of type `dump#E_light_plug`.
```

— rather than a `null` an author has to remember to check. A nullable field on
the shared class would have given every light access to a capability most of them
do not have, which is precisely the thing worth preventing: the dump answers
"does this entity have X" by **whether X is there**.

Two consequences, both spike-verified on pkl-core 0.32.1:

- The declared types are **not nullable** — the entity reports the capability, so
  the value exists.
- Precision costs nothing generically: `E_light_plug` is still assignable to
  `List<hass.LightEntity>` and `List<hass.Entity>`, so area member lists and card
  factories taking a domain type are unaffected. What generic code loses is
  access to the capabilities — which is correct, since a `hass.LightEntity` in
  the abstract genuinely has none.

This is why the domain classes are `open`, and why the generated module is ~24%
larger (944 KB against 762 KB for the shared-nullable-field shape).

#### Capability groups: the concept is the nullable thing

A capability whose values travel together is ONE nullable object on the domain
class, not several independent nullable fields:

```pkl
class ColourTemp { min_kelvin: Int; max_kelvin: Int }

open class LightEntity extends Entity {
  hidden colourModes: Listing<ColorMode> = new Listing {}
  hidden supported_features: Int = 0
  hidden colourTemp: ColourTemp? = null
  hidden effects: Effects? = null
}
```

This is what makes nullability safe, and it is worth being precise about why —
FLAT nullable fields were rejected earlier for a reason that grouping removes:

- **The group is the predicate.** `l.colourTemp != null` needs no parallel
  `supportsColourTemp` flag that could disagree with the value beside it.
- **One guard covers every value in the concept.** `min_kelvin` without
  `max_kelvin` is unrepresentable, rather than two nullables that can drift.
- **An unguarded read is an ERROR, not a null.** Values are reached *through* the
  group, so a missed guard gives `Cannot find property 'min_kelvin' in object of
  type 'Null'` at build time — where a flat `min_color_temp_kelvin: Int?` would
  have yielded `null`, rendered as a blank slot, and shipped.
- `?.` and `??` work, so `l.colourTemp?.min_kelvin ?? default` is available when
  a default is genuinely wanted.

A capability with no associated values stays a derived Boolean — a group with no
fields would carry nothing.

**The generator emits data; the schema draws conclusions.** `PklDump` writes
`colourModes`, `supported_features` and the groups; `hass.pkl` derives
`supportsColour`, `supportsBrightness`, `supportsFlash`, `supportsTransition`
from them, reading the vendored constants directly. So the rule for "what counts
as colour" lives in one place instead of being duplicated between Scala and Pkl.

**Completeness is a CODEGEN check, not a Pkl one.** Pkl cannot help here: a
required property with no value is lazy, so a half-filled group evaluates fine
until something reads the missing field, and the error then blames the class
definition rather than the entity. `PklDump.warnings` inspects the whole picture
at generation time and reports a light that reports one kelvin bound without the
other, or claims `color_temp` with no range at all. The group is omitted rather
than half-emitted, and the warning is printed by both dump paths. Reported, never
fatal — one odd integration must not stop the house's dump from building.

Groups exist for `light` today. Every other domain keeps falling through to the
per-entity class unchanged, which is what makes the modelling incremental: a
domain gains groups when someone models it, and nothing else moves.

#### Predicates are the other half

For a capability that IS just a yes/no (colour, brightness, flash, transition),
a predicate on the domain class says it — askable of any light, because "does not
support colour" is a true statement about every light. These are derived in the
schema from the emitted data, not baked in by the generator. Together the two halves give the pattern the dump exists for —
ask generically, then use the precise value the predicate just proved is there:

```pkl
allLights.filter((l) -> l.supportsColourTemp).map((l) -> l.min_color_temp_kelvin)
```

Verified on the live dump: 25 lights, 18 with colour, 2 tunable-white-only, 24
dimmable.

#### HA's domain model is vendored, not guessed

The predicates are derived from HA's own light model, copied into
`lib/hass-light.pkl` (author-facing) and `HaLight.scala` (generator-facing):
the `ColorMode` string enum and the `LightEntityFeature` bits
(`EFFECT=4`, `FLASH=8`, `TRANSITION=32`).

**Vendoring these is safe.** An earlier draft of this ADR claimed the numeric
feature flags "drift between HA releases" — that was wrong. HA's `*EntityFeature`
IntFlags are **append-only**: a new feature takes a new bit, a removed one leaves
its bit vacant, and existing values are never renumbered, because they are
persisted in entity state attributes and read by the frontend. The vacant `1` and
`2` in `LightEntityFeature` are exactly that — the removed `SUPPORT_BRIGHTNESS`
and `SUPPORT_COLOR_TEMP`, dropped when colour modes replaced them.

Checked rather than assumed, against the live instance's 48 lights: the `EFFECT`
bit agreed with `effect_list` presence **48/48**, and every observed
`supported_features` value (0, 4, 40, 44) decoded with **no unaccounted bits**.
Re-run that check when syncing to a newer HA.

Two copies exist because the two sides need the constants at different times —
codegen derives the predicates, an author names a mode or tests a bit in their
own expressions. `HaLightSuite` reads the values back out of the Pkl source and
asserts they match the Scala ones, so the pair cannot drift apart silently.

Vendoring also lets the dump type its colour modes as
`Listing<hass.ColorMode>` rather than `Listing<String>`, so an author's
`"colour_temp"` is an eval error instead of a comparison that silently never
matches.

Light is done; the other bitmask domains (media_player, climate, cover, fan)
follow the same pattern and are not yet copied.

#### Why not Pkl mixins

Capabilities as `Mixin`s was considered and does not work, for two reasons
verified on pkl-core 0.32.1:

- Pkl has **no multiple inheritance** — `class C extends A, B` is a parse error —
  so capabilities cannot be separate types a class combines. Nor is the
  capability lattice linear (a light may support `color_temp` only, or `rgb`
  only, or both), so a single-inheritance chain cannot express it either.
- A Pkl `Mixin<T>` is a **function `(T) -> T`**, an amendment applied to a value.
  It cannot introduce a property the class does not already declare: applying one
  that sets an undeclared `min_kelvin` fails with "Cannot find property
  `min_kelvin` in object of type `Light`".

So a mixin can set what already exists but cannot carry a capability, and it
creates no type to dispatch on.

The Pkl maintainers' own mixin recipe (apple/pkl discussion #332, "a mixin is a
way to have a multiple inheritance model in Pkl") was reproduced and confirms
this rather than contradicting it. Its `Config` class declares **every** property
up front and the mixins only set values; the "multiple inheritance" is of
composed VALUES, not of types. Applied to entities it fails our requirement on
the first test: a light that receives no colour-temp mixin still exposes
`min_color_temp_kelvin = null`, which is precisely the nullable-shared-field
shape this ADR moved away from.

That trade is real and not absurd — mixins would drop the ~1069 generated classes
and the 24% size growth with them. It is rejected because absence-typing is the
property we actually wanted: the dump should answer "does this entity have X" by
whether X is there.

Where the mixin recipe DOES fit here is one layer up, composing CARDS, where
every slot is declared on the card class and the question is which amendments to
apply:

```pkl
const function cardFor(l: LightEntity): Card =
  let (base = (new Card { title = l.entity_id }) |> withDimmer)
    if (l.supportsColour) withColour.apply(base) else base
```

The two compose cleanly — the entity keeps absence-typing, the predicate drives
the mixin, the card gets its controls. That is a natural next step for
`components.pkl` and is not yet built.

### What gets carried, and what deliberately does not

Attributes are filtered to a **capability set** (`RegistryDump.CapabilityAttributes`),
widenable per home through `FH_DUMP_ATTRIBUTES` (comma-separated, additive).

The binding reason is **not** staleness, and not size — it is the dump's
**content version**. The dump is a content-addressed package
(`fh-home@1.0.0-g<hash>`) and `DumpRefresh` re-seeds it and re-evaluates every
dashboard whenever that hash moves. A volatile attribute in the dump therefore
does not merely go stale: it re-hashes the package on every change, turning a
dimmed light into a full rebuild. That rules out carrying a build-time snapshot
of live values at all — the idea was considered and dropped for exactly this
reason, even though it measured at only +7% file size.

The set was checked empirically, not guessed: watching `subscribe_entities`
delta frames for 180s on the live instance, **23 of 24 candidates never appeared
in a single change**. The one that did — `entity_picture`, 4 changes across 4
entities — is excluded, because camera and media_player picture URLs carry a
rotating `access_token`. **Re-run that check before widening the set**; it is the
only way to know, and a wrong entry is silent (the dashboards still work, they
just rebuild constantly).

`color_mode` is the clearest of the obvious exclusions: the template path baked
it onto every light, and the registry path drops it.

Widening the dump is now a ONE-line change to `RegistryDump.CapabilityAttributes`
— per-entity classes mean a new attribute needs no `hass.pkl` edit and pollutes
no shared type. `PklDump` infers each property's Pkl type from its JSON value and
skips anything it cannot state faithfully (objects, mixed arrays, explicit
nulls), so an author never meets a field whose type is a guess.

### Group members are references, not id strings

Two HA mechanisms name an entity's members — `attributes.entity_id` (the Light
Group helper) and `attributes.group_entities` (Zigbee/ZHA groups). They nest. Both
become one `members` edge, emitted by `PklDump` as references to the other
entities' `e_*` consts:

```pkl
const hidden e_light_relative_stue: E_light_relative_stue = new {
  entity_id = "light.relative_stue"
  members = List(e_light_spisebordlys, e_light_skyconnect_..._sittegruppe)
}
```

Pkl resolves module-level `const`s lazily and order-independently, so forward
references work and a cycle would not hang (both spike-verified). That is what
makes `e.members[0].members` walk a group nested inside a group — verified
against the live instance, reaching 11 bulbs two levels below `light.relative_stue`.
A member id absent from the dump is dropped rather than emitted dangling: a
missing `e_*` would be an eval error in every dashboard.

## Consequences

- `DumpCompareApp` runs both paths against one live instance and diffs them. On
  the dev instance: **1069 entities each, zero mismatches** across `entity_id`,
  `domain`, `friendly_name`, `area_id`, `floor_id`, `id_hidden` — so the registry
  path reproduces the template path exactly, and adds 201 devices, 12 group
  entities, and the `entity_category` classification.
- `hass.pkl` grew `device_id`, `entity_category`, `members`, a `Device` class and
  `Floor.level`; its domain classes became `open` and SHED their capability
  fields (see above).
- `PklDump.render` still consumes either dump shape, so `DumpCompareApp` keeps
  working and the template path can be re-run for comparison at any time.
- The test fake (`FakeHomeAssistant`) now answers the four registry commands with
  EMPTY lists. That is faithful, not a stub: a fixture declares entities and
  attributes, never registry rows, and the join runs from the state snapshot, so
  every fixture entity still reaches the dump — just with no area/floor/device.

## Alternatives rejected

- **Chunk the template into several `/api/template` calls** and merge in Scala.
  Smaller change, but it keeps Jinja, keeps a cap to manage as the house grows,
  and still cannot reach `entity_category`.
- **Read `/api/states` over REST** for the attributes. Uncapped and it works, but
  `subscribe_entities` returns the same data as `Map[String, Json]` over the
  connection the app already holds — one mechanism instead of two.
