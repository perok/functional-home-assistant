// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) into the `dashboard.json` artifact `{ templates, layout }`.
// The layout is a RECURSIVE tree of rows/columns and component leaves; leaves
// reference shared templates by name (see components.libsonnet).
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';

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
    c.sectionTitle('Dashboard'),

    // A row of light toggles, then a row of brightness sliders.
    c.row([
      c.button(eo.friendly_name, 'homeassistant', 'toggle', eo.entity_id)
      for eo in lights
    ]),
    c.row([
      c.brightnessSlider(eo.friendly_name, eo.entity_id)
      for eo in lights
    ]),

    // A grid of sensor readings.
    c.sectionTitle('Sensors'),
    c.row([
      c.stateCard(eo.friendly_name, eo.entity_id)
      for eo in sensors
    ]),

    // DYNAMIC group: every `device_class: battery` sensor whose state (the
    // battery %) is under 20, evaluated live. Membership tracks the threshold
    // as batteries drain.
    c.sectionTitle('Low battery'),
    c.dynamic(
      c.lowBattery(20),
      [c.dynStateCard(c.always)]
    ),
  ]),
}
