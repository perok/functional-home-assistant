// A third dashboard, served at /d/tabs (slug = filename). Demonstrates the
// `tabs` sugar: one bar over a single inline panel, one tab visible at a time.
//
// `c.tabs([...])` is pure composition — the build-phase hoist lifts each tab's
// `content` into the surfaces registry (sharing one exclusivity group + one
// inline mount), generates the bar buttons that open them, bakes the first tab
// inline for an instant first paint, and tracks the active tab in a signal for
// the highlight. Switching reuses the same surface open/close machinery as
// popups; the panel just patches in place instead of stacking an overlay.
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';
local theme = import 'theme.libsonnet';
local entities = std.objectValues(dump.entities);

{
  cards: c.cards,
  theme: theme,
  card: c.column([
    c.sectionTitle('Tabs'),
    c.row([
      c.button(action=c.navigate('dashboard'), label='« Home'),
    ]),
    c.tabs([
      {
        label: 'Lights',
        content: c.row([
          c.entityCard(eo, tap=c.toggleTap)
          for eo in entities
          if eo.domain == 'light'
        ]),
      },
      {
        label: 'Sensors',
        content: c.row([
          c.entityCard(
            eo,
            transform='$round($number($state), 1) & " " & $attr.unit_of_measurement',
          )
          for eo in entities
          if eo.domain == 'sensor'
        ]),
      },
    ]),
  ]),
}
