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
local d = c.dynamic;  // the dynamic-group DSL (query predicates + group/when/case)
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
  // Surfaces (popups): lazily mounted subtrees, opened by a component's
  // openPopup(<id>) action and rendered/streamed only while open. `group` makes
  // members mutually exclusive (the basis for tabs); absent = stackable.
  surfaces: {
    detail: {
      content: c.column([
        c.sectionTitle('Power detail'),
        c.entityCard(dump.entities.sensor_ams_1a4e_p),
        c.button(action=c.closePopup('detail'), label='Close'),
      ]),
      group: 'modal',
    },
  },

  card: c.column([
    // TODO config parameters for card, vs properties of valuest to show
    c.sectionTitle('Dashboard'),

    // Cross-dashboard navigation: in-place swap to /d/energy (URL updates too).
    c.row([
      c.button(action=c.navigate('energy'), label='Energy »'),
      c.button(action=c.navigate('tabs'), label='Tabs test »'),
    ]),

    // Tapping the first card opens the registered 'detail' popup; the second
    // opens an INLINE popup (the backend hoists its content into a surface and
    // its close control is supplied by the popup wrapper).
    c.row([
      c.entityCard(dump.entities.sensor_ams_1a4e_p, tap=c.openPopup('detail')),
      c.entityCard(
        dump.entities.sensor_ams_1a4e_p,
        label='Inline popup',
        tap=c.openPopup(c.column([
          c.sectionTitle('Inline detail'),
          c.entityCard(dump.entities.sensor_ams_1a4e_p),
        ])),
      ),
    ]),

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
        // Custom sample: a live value expression (same wrapper as a live label)
        value=c.expr('$round($number($state), 1) & " " & $attr.unit_of_measurement'),
      )
      for eo in entities if eo.domain == 'sensor'
    ]),

    // DYNAMIC group with PER-DOMAIN dispatch. `d.group(query, card)` renders one
    // card per live entity matching `query`; here the card is a `d.when`
    // selector, so the FIRST matching branch picks it. Membership AND the chosen
    // card track live state. Branches reuse the SAME leaf builders as static
    // cards, with `d.matched` as the entity (no separate dyn* builders). The
    // whole DSL is namespaced under `c.dynamic` (aliased `d` above).
    //
    // Each branch also shows a LIVE label via `c.expr(...)` (a JSONata expr over
    // the matched entity, in the SAME `label` arg that takes a plain string): a
    // light's brightness slider is labelled with its live brightness %, and the
    // catch-all card appends each entity's live state — labels are slots now, so
    // they repaint with the entity (ADR 0004).
    c.sectionTitle('Active now'),
    d.group(d.whenState('on'), d.when([
      d.case(
        d.whenDomain('light'),
        c.brightnessSlider(
          d.matched,
          // name · 45%  (guarded: an "on" light may briefly have no brightness)
          label=c.expr('$attr.friendly_name & ($attr.brightness ? " · " & $round($number($attr.brightness) / 2.55) & "%" : "")'),
        ),
      ),
      d.case(d.whenDomain('switch'), c.toggle(d.matched)),
    ], fallback=c.entityCard(
      d.matched,
      tap=c.toggleTap,
      label=c.expr('$attr.friendly_name & " (" & $state & ")"'),
    ))),

    // DYNAMIC group with a SINGLE card and a NUMERIC membership query: every
    // `device_class: battery` sensor under 20%, tracked live as batteries drain.
    // No dispatch needed (one card for every match), but the card is richer than
    // a bare default: a live label carrying the percentage and a secondary line
    // naming the device class.
    c.sectionTitle('Low battery'),
    d.group(
      d.lowBattery(20),
      c.entityCard(
        d.matched,
        label=c.expr('$attr.friendly_name & " — " & $state & "%"'),
        secondary='device_class',
      ),
    ),
  ]),
}
