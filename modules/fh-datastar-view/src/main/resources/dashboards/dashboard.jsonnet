// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) into the `dashboard.json` artifact `{ templates, layout }`.
// The layout is a RECURSIVE tree of rows/columns and component leaves; leaves
// reference shared templates by name (see components.libsonnet).
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';

local id(eo) = std.strReplace(eo.entity_id, '.', '_');

// Named, non-hidden entities in a given domain, capped for the demo.
local pick(domain, limit) =
  local all = [
    dump.entities[k]
    for k in std.objectFields(dump.entities)
    if k != '*'
       && dump.entities[k].domain == domain
       && dump.entities[k].friendly_name != null
  ];
  all[0:std.min(limit, std.length(all))];

local lights = pick('light', 4);
local sensors = pick('sensor', 6);

{
  templates: c.templates,
  layout: c.column([
    c.sectionTitle('title', 'Dashboard'),

    // A row of light toggles, then a row of brightness sliders.
    c.row([
      c.button(id(eo), eo.friendly_name, 'homeassistant', 'toggle', eo.entity_id)
      for eo in lights
    ]),
    c.row([
      c.brightnessSlider('bri_' + id(eo), eo.friendly_name, eo.entity_id)
      for eo in lights
    ]),

    // A grid of sensor readings.
    c.sectionTitle('sensors_title', 'Sensors'),
    c.row([
      c.stateCard('s_' + id(eo), eo.friendly_name, eo.entity_id)
      for eo in sensors
    ]),

    // DYNAMIC group: every `device_class: battery` sensor whose state (the
    // battery %) is under 20, evaluated live. Membership tracks the threshold
    // as batteries drain.
    c.sectionTitle('low_batt_title', 'Low battery'),
    c.dynamic(
      'low_batt',
      c.lowBattery(20),
      [c.dynStateCard(c.always)]
    ),
  ]),
}
