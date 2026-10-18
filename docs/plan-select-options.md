# Plan: `c.select` over any set of options an entity has (#582)

## Why

`c.select` takes a node variable, a `select` entity or a light's effects. For the two
entity kinds, `components/select.pkl` asks "is it an `Effects` or a `SelectEntity`?" in five
places (`targetName`, `offered`, `entityChosen`, `entityCall`, the dropdown's label) and casts
in each. #582 as filed would add a branch to each of them for every climate and fan option.

A design that needs a cast per target is the last resort. The library already has the answer
for sliders: `hass.Axis` carries what a slider drives, so the slider holds no per-domain branch
(ADR 0013). This does the same for a choice.

## The shape

`hass.Options`, a capability group beside `Axis`:

| field | meaning | effects | select | climate HVAC | climate preset | fan preset |
|---|---|---|---|---|---|---|
| `owner` | the entity | light | the select | climate | climate | fan |
| `list` | what it can be set to | `effect_list` | `options` | `hvac_modes` | `preset_modes` | `preset_modes` |
| `attr` | where the current value is; null = the state | `effect` | null | null | `preset_mode` | `preset_mode` |
| `service` | what a choice posts | `light/turn_on` | `select/select_option` | `climate/set_hvac_mode` | `climate/set_preset_mode` | `fan/set_preset_mode` |
| `key` | the value's data key | `effect` | `option` | `hvac_mode` | `preset_mode` | `preset_mode` |

- `Effects` extends it, so `c.select(l.effects!!)` is unchanged.
- A `select` entity is itself the choice, so `c.select(sel)` stays: `SelectEntity.choices`
  is its group, derived from `options`, and `c.select` resolves the entity to it once, at the
  door.
- From there `select.pkl` reads fields only: `targetName`, `offered`, `entityChosen`,
  `entityCall` and the dropdown's label each become one expression, with no branch per kind.
- **The wire does not move** for effects and selects. The snapshots prove the refactor.

## Climate and fan (ADR 0013's recipe)

`hvac_modes` and `preset_modes` are already carried (`RegistryDump.CapabilityAttributes`).

- `hass.ClimateEntity` with `hvacModes: HvacModes?` and `presets: ClimatePresets?`;
  `hass.FanEntity` with `presets: FanPresets?`. Each group is null where the entity reports
  no list, so `c.select(x.presets)` on an entity without presets is an editor error.
- `PklDump`: `schemaGroups` branches, `SchemaModelled` entries, `entityType` rows.
- Selectors `hass.climates(scope)` / `hass.fans(scope)`, out of `generic`'s complement in the
  same edit; namespaces `dump.climates` / `dump.fans` on `internal/dump-base.pkl` and their
  `DomainNamespaces` rows.
- No feature bitmask is vendored yet: nothing here reads `ClimateEntityFeature`, and the
  groups are decided by the lists HA reports. That stays the next step for climate.
- Fan's slider row (`domainAxes`) is unchanged; `fan` becomes a typed domain beside it.

The live instance has four climates with `hvac_modes` (`heat`/`auto`/`off`,
`heat_cool`/`off`) and no presets, and three fans with no presets. Presets are tested against
fake dumps only.

## Tests

- Facts: `c.select` over each kind posts the group's service and key and lights by its
  `attr` or state; a dropdown's label reads the same place.
- `PklDumpCapabilitySuite`: each group complete, absent, and the unmodelled attribute still
  landing on the per-entity class.
- The wire snapshots stay byte-identical across the refactor step.

## Docs touched

ADR 0013 (coverage table: climate, fan; its stale `sliderSpec` is `domainAxes`), ADR 0033 if
it names `c.select`'s targets, `docs/writing-dashboards.md` (a select over a climate's modes),
the module CLAUDE.md's `c.select` line.

## Out of scope

- `input_select` (#574) becomes one more `Options` once that PR lands; it is not merged into this.
- `fan_modes` and `swing_modes`: the same group shape, added when wanted.
