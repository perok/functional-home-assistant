// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) and emits the `dashboard.json` artifact:
//   { templates, registry, layout }
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';

// Helper: named, non-hidden entities in a given domain, capped for the demo.
local pick(domain, limit) =
  local all = [
    dump.entities[k]
    for k in std.objectFields(dump.entities)
    if k != '*'
       && dump.entities[k].domain == domain
       && dump.entities[k].friendly_name != null
  ];
  all[0:std.min(limit, std.length(all))];

local id(eo) = std.strReplace(eo.entity_id, '.', '_');

// Lights and switches become interactive toggles; sensors become read-only cards.
local toggles = [
  c.toggle(id(eo), eo.friendly_name, eo.entity_id)
  for eo in pick('light', 8) + pick('switch', 8)
];
local sensors = [
  c.sensorCard(id(eo), eo.friendly_name, eo.entity_id)
  for eo in pick('sensor', 12)
];
local comps = toggles + sensors;

{
  templates: { [comp.id]: comp.template for comp in comps },
  registry: { [comp.id]: { entities: comp.entities, slots: comp.slots } for comp in comps },
  layout:
    '<main class="container"><h1>Dashboard</h1><div class="grid">'
    + std.join('', ['{{{' + comp.id + '}}}' for comp in comps])
    + '</div></main>',
}
