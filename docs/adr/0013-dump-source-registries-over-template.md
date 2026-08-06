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

### What gets carried, and what deliberately does not

Attributes are filtered to a **capability set** (`RegistryDump.CapabilityAttributes`
— `supported_color_modes`, `effect_list`, `options`, `min`/`max`/`step`,
`device_class`, …). The cut is **phase discipline, not size**: the dump is
build-time, so any attribute that moves while the server runs (`brightness`,
`color_temp_kelvin`, `rgb_color`, `update_percentage`) would be baked stale.
Those stay runtime-side as JSONata over the SSE stream, which is where they
already were. `color_mode` is the clearest example: the template path baked it
onto every light, and the registry path drops it.

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
