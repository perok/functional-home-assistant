// Dashboard components — pure composition (build phase only).
//
// Each component function returns a record:
//   { id, template, entities, slots }
// where `template` is a Mustache string (the server injects runtime values into
// `{{ }}` slots) and `entities`/`slots` declare its runtime dependencies.
//
// PHASE DISCIPLINE: do NOT inject live entity values here. Static, author-known
// values (labels, ids) are inlined at build time; everything dynamic is a `{{slot}}`.
{
  // Read-only card: friendly name + current state.
  stateCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header>' + label + '</header>'
      + '<span class="state">{{state}}</span>'
      + '</article>',
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  // Interactive toggle: clicking calls homeassistant.toggle on the entity. The
  // resulting state change flows back over the SSE stream and re-renders this
  // button (it registers the entity below).
  toggle(id, label, entity):: {
    id: id,
    template:
      '<button class="card" id="' + id + '" '
      + "data-on-click=\"@post('/sse/action/homeassistant/toggle/" + entity + "')\">"
      + '<strong>' + label + '</strong>: <span>{{state}}</span>'
      + '</button>',
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  // Sensor card: value + unit of measurement.
  sensorCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header>' + label + '</header>'
      + '<span class="value">{{state}}</span> <span class="unit">{{unit}}</span>'
      + '</article>',
    entities: [entity],
    slots: {
      state: { entity: entity },
      unit: { entity: entity, attribute: 'unit_of_measurement' },
    },
  },
}
