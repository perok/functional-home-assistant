// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) into the `dashboard.json` artifact `{ cards, layout }`.
// The layout is a RECURSIVE tree of rows/columns and component leaves; leaves
// reference shared cards by name (see components.libsonnet).
//
// The "design system" is exactly that shared card library (`c.cards`):
// re-skin the whole dashboard by editing/replacing those Mustache templates (or
// importing a different library) — the layout and the backend are unaffected.
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';
local theme = import 'theme.libsonnet';
local entities = std.objectValues(dump.entities);


// "static dynamic" for room entities and things like that. Things that are not going to live changing when viewing the dashboard.
// Every now and so often the system could recreate the dump and combine the setup to have a dashboard.json with the latest changes.
// "dynamic" for live chaning things while viewing the dashboard. For.ex all with battery below X


// Live reload: ServerApp watches these source files and hot-swaps the renderer
// on change (the browser repaints over SSE) — see ServerApp.watchSources.

// Decision: multiple top-level views are separate dashboard files, not in-page
// tabs. The runtime already evaluates a chosen jsonnet entry (ServerApp's
// `dashboard.jsonnet`, overridable), so a "view" = its own entry file; this
// keeps each view independently addressable and avoids loading/streaming every
// view at once. (An in-page `tabs` container is possible later, but it needs
// per-child labels — richer child metadata than today's `{html}` children.)
{
  cards: c.cards,
  // All presentation (design tokens + stylesheets + CSS) lives in the theme,
  // so the app isn't tied to a CSS framework. See theme.libsonnet.
  theme: theme,
  // The root of the view is itself a card (here a column container).
  card: c.column([
    // TODO config parameters for card, vs properties of valuest to show
    c.sectionTitle('Dashboard'),

    // A single child may be passed without an array (normalized in the backend).
    // Round the state to 1 decimal and append the entity's own unit (JSONata in
    // the backend; $state is the value, $attr its attributes — same-entity only).
    c.row(c.entityCard(
      dump.entities.sensor_ams_1a4e_p,
    )),

    c.sectionTitle('Kitchen'),
    // A row of light toggles, then a row of brightness sliders.
    c.row([
      c.toggle(eo)
      for eo in entities if eo.domain == 'light' && eo.area_id == dump.areas.kjokken.area_id
    ]),
    c.row([
      c.brightnessSlider(eo)
      for eo in entities if eo.domain == 'light' && eo.area_id == dump.areas.kjokken.area_id
    ]),
    c.sectionTitle('Living room'),
    // Living-room lights as tap-to-toggle entity cards, with a secondary line
    // showing the brightness attribute.
    c.row([
      c.entityCard(eo, secondary='brightness', tap=c.toggleTap)
      for eo in entities if eo.domain == 'light' && eo.area_id == dump.areas.living_room.area_id
    ]),

    // A grid of sensor readings, rounded to one decimal with the unit appended.
    // (A custom transform replaces the default, so it appends the unit itself.)
    c.sectionTitle('Sensors'),
    c.row([
      c.entityCard(
        eo,
        // Custom sample
        transform='$round($number($state), 1) & " " & $attr.unit_of_measurement',
      )
      for eo in entities if eo.domain == 'sensor'
    ]),

    // DYNAMIC group: every `device_class: battery` sensor whose state (the
    // battery %) is under 20, evaluated live. Membership tracks the threshold
    // as batteries drain.
    c.sectionTitle('Low battery'),
    c.dynamic(
      c.lowBattery(20),
      [c.dynEntityCard(c.always)]
    ),
  ]),
}
