// Dashboard components — pure composition (build phase only).
//
// Each component function returns a record { id, template, entities, slots }
// where `template` is a Mustache string (the server injects runtime values into
// `{{ }}` slots) and `entities`/`slots` declare its runtime dependencies.
//
// Datastar v1 attribute syntax uses COLONS: `data-on:click`, `data-bind`,
// `data-signals`. Interactions call the server's action routes:
//   - no data:   @post('/sse/action/<domain>/<service>/<entity>')
//   - one value: @post('/sse/action/<domain>/<service>/<entity>/<key>/' + $signal)
// The resulting HA state change flows back over the persistent SSE stream.
//
// PHASE DISCIPLINE: do NOT inject live entity values here. Static, author-known
// values (labels, ids) are inlined at build time; everything dynamic is a `{{slot}}`.
{
  local actionUrl(domain, service, entity) =
    "@post('/sse/action/" + domain + '/' + service + '/' + entity + "')",

  local valueActionUrl(domain, service, entity, key, signal) =
    "@post('/sse/action/" + domain + '/' + service + '/' + entity + '/' + key + "/' + $" + signal + ')',

  // Static section heading (no entity dependency).
  sectionTitle(id, text):: {
    id: id,
    template: '<h2 class="section">' + text + '</h2>',
    entities: [],
    slots: {},
  },

  // Read-only: friendly name + current state.
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

  // Sensor value + unit of measurement.
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

  // Numeric sensor as a progress gauge (expects a 0-100 value).
  gaugeCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header>' + label + ': {{state}} {{unit}}</header>'
      + '<progress value="{{state}}" max="100"></progress>'
      + '</article>',
    entities: [entity],
    slots: {
      state: { entity: entity, default: '0' },
      unit: { entity: entity, attribute: 'unit_of_measurement' },
    },
  },

  // Generic service-call button (scenes, scripts, button.press, ...).
  buttonCard(id, label, domain, service, entity):: {
    id: id,
    template:
      '<button class="card" id="' + id + '" data-on:click="' + actionUrl(domain, service, entity) + '">'
      + label
      + '</button>',
    entities: [],
    slots: {},
  },

  // Toggle (lights / switches / fans) via homeassistant.toggle.
  toggle(id, label, entity):: {
    id: id,
    template:
      '<button class="card" id="' + id + '" data-on:click="' + actionUrl('homeassistant', 'toggle', entity) + '">'
      + '<strong>' + label + '</strong>: <span>{{state}}</span>'
      + '</button>',
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  // Light: toggle (click the name) + brightness slider.
  lightCard(id, label, entity):: {
    local sig = 'bri_' + id,
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header><button class="ghost" data-on:click="' + actionUrl('homeassistant', 'toggle', entity) + '">'
      + '<strong>' + label + '</strong></button>: <span>{{state}}</span></header>'
      + '<input type="range" min="1" max="255" '
      + 'data-signals="{' + sig + ': {{brightness}}}" '
      + 'data-bind="' + sig + '" '
      + 'data-on:change="' + valueActionUrl('light', 'turn_on', entity, 'brightness', sig) + '" />'
      + '</article>',
    entities: [entity],
    slots: {
      state: { entity: entity },
      brightness: { entity: entity, attribute: 'brightness', default: '0' },
    },
  },

  // Cover: open / stop / close.
  coverCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header><strong>' + label + '</strong>: <span>{{state}}</span></header>'
      + '<div role="group">'
      + '<button data-on:click="' + actionUrl('cover', 'open_cover', entity) + '">▲</button>'
      + '<button data-on:click="' + actionUrl('cover', 'stop_cover', entity) + '">■</button>'
      + '<button data-on:click="' + actionUrl('cover', 'close_cover', entity) + '">▼</button>'
      + '</div></article>',
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  // Lock: lock / unlock.
  lockCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header><strong>' + label + '</strong>: <span>{{state}}</span></header>'
      + '<div role="group">'
      + '<button data-on:click="' + actionUrl('lock', 'lock', entity) + '">Lock</button>'
      + '<button data-on:click="' + actionUrl('lock', 'unlock', entity) + '">Unlock</button>'
      + '</div></article>',
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  // Media player: previous / play-pause / next + current title.
  mediaPlayerCard(id, label, entity):: {
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header><strong>' + label + '</strong>: <span>{{state}}</span></header>'
      + '<small>{{title}}</small>'
      + '<div role="group">'
      + '<button data-on:click="' + actionUrl('media_player', 'media_previous_track', entity) + '">⏮</button>'
      + '<button data-on:click="' + actionUrl('media_player', 'media_play_pause', entity) + '">⏯</button>'
      + '<button data-on:click="' + actionUrl('media_player', 'media_next_track', entity) + '">⏭</button>'
      + '</div></article>',
    entities: [entity],
    slots: {
      state: { entity: entity },
      title: { entity: entity, attribute: 'media_title', default: '' },
    },
  },

  // Climate: current temperature + target temperature slider.
  climateCard(id, label, entity):: {
    local sig = 'temp_' + id,
    id: id,
    template:
      '<article class="card" id="' + id + '">'
      + '<header><strong>' + label + '</strong>: <span>{{state}}</span></header>'
      + '<small>now {{current}}° → target {{target}}°</small>'
      + '<input type="range" min="7" max="30" step="0.5" '
      + 'data-signals="{' + sig + ': {{target}}}" '
      + 'data-bind="' + sig + '" '
      + 'data-on:change="' + valueActionUrl('climate', 'set_temperature', entity, 'temperature', sig) + '" />'
      + '</article>',
    entities: [entity],
    slots: {
      state: { entity: entity },
      current: { entity: entity, attribute: 'current_temperature', default: '?' },
      target: { entity: entity, attribute: 'temperature', default: '20' },
    },
  },
}
