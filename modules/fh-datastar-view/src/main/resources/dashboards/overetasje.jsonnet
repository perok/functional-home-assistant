// A second dashboard, served at /d/energy (slug = filename). Demonstrates
// multi-dashboard serving and in-place cross-dashboard navigation back to the
// default. Shares the same component library + theme.
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';
local theme = import 'theme.libsonnet';
local entities = std.objectValues(dump.entities);


// TODO need a null component?
local customArea(area_id) =
  std.get({
    [dump.floors.overetasje.areas.bibliotek.area_id]: c.column(),
  }, area_id, std.row());

// TODO some paramter to a function with the current room etc to add custom stuff
local areaView(dump, floor_id) = c.column([
    local lightInArea = [
      c.slider(eo)
      for eo in entities
      if eo.domain == 'light' && eo.area_id == area.area_id
    ];

  c.column(
    if std.length(lightInArea) > 0 then
      [
        c.sectionTitle(area.area_name),
        c.column(lightInArea),
      ] else null
  )
  for area in std.objectValues(dump.floors[floor_id].areas)
]);

{
  cards: c.cards,
  theme: theme,
  card: c.column([
    c.row([
      c.button(action=c.navigate('dashboard'), label='« Home'),
      c.sectionTitle('Overetasje'),
    ]),
    areaView(dump, 'overetasje'),
  ]),
}
