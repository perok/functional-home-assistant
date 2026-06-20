// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) and emits the `dashboard.json` artifact:
//   { templates, registry, layout }
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

local id(eo) = std.strReplace(eo.entity_id, '.', '_');

// A titled section: heading + one card per entity. Empty domains are skipped.
local section(title, domain, limit, builder) =
  local items = pick(domain, limit);
  if std.length(items) == 0 then []
  else [c.sectionTitle('section_' + domain, title)] + [builder(eo) for eo in items];

local comps =
  section('Lights', 'light', 8, function(eo) c.lightCard(id(eo), eo.friendly_name, eo.entity_id))
  + section('Switches', 'switch', 8, function(eo) c.toggle(id(eo), eo.friendly_name, eo.entity_id))
  + section('Covers', 'cover', 6, function(eo) c.coverCard(id(eo), eo.friendly_name, eo.entity_id))
  + section('Locks', 'lock', 6, function(eo) c.lockCard(id(eo), eo.friendly_name, eo.entity_id))
  + section('Climate', 'climate', 6, function(eo) c.climateCard(id(eo), eo.friendly_name, eo.entity_id))
  + section('Media', 'media_player', 6, function(eo) c.mediaPlayerCard(id(eo), eo.friendly_name, eo.entity_id))
  + section('Scenes', 'scene', 8, function(eo) c.buttonCard(id(eo), eo.friendly_name, 'scene', 'turn_on', eo.entity_id))
  + section('Sensors', 'sensor', 12, function(eo) c.sensorCard(id(eo), eo.friendly_name, eo.entity_id));

{
  templates: { [comp.id]: comp.template for comp in comps },
  registry: { [comp.id]: { entities: comp.entities, slots: comp.slots } for comp in comps },
  layout:
    '<main class="container"><h1>Dashboard</h1>'
    + std.join('', ['{{{' + comp.id + '}}}' for comp in comps])
    + '</main>',
}
