// A second dashboard, served at /d/energy (slug = filename). Demonstrates
// multi-dashboard serving and in-place cross-dashboard navigation back to the
// default. Shares the same component library + theme.
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';
local theme = import 'theme.libsonnet';
local entities = std.objectValues(dump.entities);

{
  cards: c.cards,
  theme: theme,
  card: c.column([
    c.sectionTitle('Energy'),
    c.row([
      c.button(action=c.navigate('dashboard'), label='« Home'),
    ]),
    // Every power/energy sensor, rounded with its own unit appended.
    c.row([
      c.entityCard(eo
      //, value=c.expr('$round($number($state), 1)')
      )
      for eo in entities
      if eo.domain == 'sensor'
      && (std.objectHas(eo, 'attributes'))
    ]),
  ]),
}
