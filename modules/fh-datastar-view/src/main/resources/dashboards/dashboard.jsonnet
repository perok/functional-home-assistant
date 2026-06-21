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

// A static reference to one named entity in the dump (vs the `pick` lists).
// The dump intentionally keys entities by a dotless id (`light.disco` ->
// `light_disco`) so an editor's LSP autocompletes `dump.entities.<id>`. Keying
// by the raw `entity_id` instead would drop that autocomplete, so we keep the
// dotless keys and let `at` map a real entity_id back to its key.
// (An entity_id is `domain.object_id` with exactly one dot, so replacing all
// dots equals replacing the first — and mirrors DataDump's `sanitize`.)
local at(id) = dump.entities[std.strReplace(id, '.', '_')];
// `[fn(entity)]` if `id` exists in this dump, else `[]` — keeps the example
// portable when a referenced entity is absent.
local ifPresent(id, fn) =
  if std.objectHas(dump.entities, std.strReplace(id, '.', '_')) then [fn(at(id))] else [];

//dump.entities.light_light_hue_e0e381_kjokken_light
//dump.areas.b60e3722ed314fe893d385684d6509f0

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

    // Static reference: a card for one specifically-named entity (rendered only
    // if present in this dump). Adjust the id to an entity on your instance.
    //c.row(ifPresent('sensor.ams_1a4e_p', function(eo) c.stateCard(eo))),
    // A single child may be passed without an array (normalized in the backend).
    c.row(c.stateCard(dump.entities.sensor_ams_1a4e_p)),

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

    // A grid of sensor readings.
    c.sectionTitle('Sensors'),
    c.row([
      c.stateCard(eo)
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
