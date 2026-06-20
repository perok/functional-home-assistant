// Example dashboard definition.
//
// Composes components against the live entity dump (generated as `dump.libsonnet`
// by the build phase) into the `dashboard.json` artifact `{ templates, layout }`.
// The layout is a RECURSIVE tree of rows/columns and component leaves; leaves
// reference shared templates by name (see components.libsonnet).
//
// The "design system" is exactly that shared card-template library (`c.templates`):
// re-skin the whole dashboard by editing/replacing those Mustache templates (or
// importing a different library) — the layout and the backend are unaffected.
local c = import 'components.libsonnet';
local dump = import 'dump.libsonnet';

// Named, non-hidden entities in a given domain, capped for the demo.
local pick(domain, limit) =
  local all = [
    dump.entities[k]
    for k in std.objectFields(dump.entities)
    if dump.entities[k].domain == domain
       && dump.entities[k].friendly_name != null
  ];
  all[0:std.min(limit, std.length(all))];

local lights = pick('light', 4);
local sensors = pick('sensor', 6);


// "static dynamic" for room entities and things like that. Things that are not going to live changing when viewing the dashboard.
// Every now and so often the system could recreate the dump and combine the setup to have a dashboard.json with the latest changes.
// "dynamic" for live chaning things while viewing the dashboard. For.ex all with battery below X


// Decision: multiple top-level views are separate dashboard files, not in-page
// tabs. The runtime already evaluates a chosen jsonnet entry (ServerApp's
// `dashboard.jsonnet`, overridable), so a "view" = its own entry file; this
// keeps each view independently addressable and avoids loading/streaming every
// view at once. (An in-page `tabs` container is possible later, but it needs
// per-child labels — richer child metadata than today's `{html}` children.)
{
  templates: c.templates,
  // TODO rename card and templates as cards
  layout: c.column([
    c.sectionTitle('Dashboard'),

    // A row of light toggles, then a row of brightness sliders.
    c.row([
    // TODO toggle should be a default assumed action for light entities in button, hyprscript? https://hyperscript.org/
    // TODO friendly_name should not use the static value, but be a reference for it, and it should be a default to use that can be overriden here

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
