// Dashboard components — pure composition (build phase only).
//
// Two parts:
//   1. `templates`: a SHARED library of Mustache strings keyed by name, each
//      declaring the `inputs` it references (validated at build time). A
//      template is defined ONCE and reused by every instance.
//   2. builder functions returning layout-tree nodes (`row`/`column`/component
//      leaves / `dynamic` groups). The node is where static values (label, id,
//      entity, service…) and dynamic slot references are supplied — NOT the
//      template.
//
// Datastar v1 attribute syntax uses COLONS: `data-on:click`, `data-bind`,
// `data-signals`. Action routes:
//   - no data:   @post('/sse/action/<domain>/<service>/<entity>')
//   - one value: @post('/sse/action/<domain>/<service>/<entity>/<key>/' + $signal)
//
// PHASE DISCIPLINE: templates never embed live values. Escaped `{{slot}}` values
// are HTML-safe; raw author values (action URLs, entity ids) use `{{{...}}}`.
// Literal template text (the `@post('...')` scaffolding) is emitted verbatim.
{
  // ---- shared template library: name -> { template, inputs } ----
  templates: {
    // Containers: splice the backend-rendered children. Add new container
    // kinds (grids, titled sections, tabs…) by adding a template here + a
    // builder below — no Scala change needed.
    fhrow: {
      template: '<div class="fh-row">{{#children}}{{{html}}}{{/children}}</div>',
      inputs: [],
    },
    fhcol: {
      template: '<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>',
      inputs: [],
    },

    sectionTitle: {
      template: '<h2 class="section">{{label}}</h2>',
      inputs: ['label'],
    },

    // Read-only: friendly name + current state.
    stateCard: {
      template:
        '<article class="card" id="{{id}}">'
        + '<header>{{label}}</header>'
        + '<span class="state">{{state}}</span>'
        + '</article>',
      inputs: ['id', 'label', 'state'],
    },

    // Generic service-call button (toggle, scene activate, lock…).
    button: {
      template:
        '<button class="card" id="{{id}}" data-on:click="'
        + "@post('/sse/action/{{domain}}/{{service}}/{{{entity}}}')"
        + '">{{label}}</button>',
      inputs: ['id', 'label', 'domain', 'service', 'entity'],
    },

    // Value slider (brightness, target temperature…). The per-instance signal
    // name is derived from the (unique) id, so it works for both static and
    // dynamic instances without a separate `sig` input.
    slider: {
      template:
        '<article class="card" id="{{id}}">'
        + '<header><strong>{{label}}</strong>: <span>{{state}}</span></header>'
        + '<input type="range" min="{{min}}" max="{{max}}" '
        + 'data-signals="{ val_{{id}}: {{value}} }" '
        + 'data-bind="val_{{id}}" '
        + 'data-on:change="'
        + "@post('/sse/action/{{domain}}/{{service}}/{{{entity}}}/{{key}}/' + $val_{{id}})"
        + '" />'
        + '</article>',
      inputs: ['id', 'label', 'min', 'max', 'domain', 'service', 'entity', 'key', 'state', 'value'],
    },
  },

  // ---- query AST helpers (Predicate) ----
  cmp(property, op, value):: { kind: 'cmp', property: property, op: op, value: value },
  and(items):: { kind: 'and', items: items },
  or(items):: { kind: 'or', items: items },
  pnot(item):: { kind: 'not', item: item },
  // Matches every entity (domain is never literally this sentinel).
  always:: self.cmp('domain', 'ne', '__never__'),
  whenDomain(d):: self.cmp('domain', 'eq', d),
  whenDeviceClass(cls):: self.cmp('attr:device_class', 'eq', cls),
  // e.g. attrLessThan('battery_level', 20)
  attrLessThan(attr, value):: self.cmp('attr:' + attr, 'lt', value),
  stateLessThan(value):: self.cmp('state', 'lt', value),
  // Battery sensors expose the % as their STATE with device_class "battery"
  // (non-numeric states like "unavailable" simply don't match `state < x`).
  lowBattery(threshold):: self.and([
    self.whenDeviceClass('battery'),
    self.stateLessThan(threshold),
  ]),

  // ---- layout container builders (templated components with children) ----
  row(children):: { kind: 'component', template: 'fhrow', children: children },
  column(children):: { kind: 'component', template: 'fhcol', children: children },

  // ---- component leaf builders ----
  // NOTE: ids are NOT authored here — the backend derives a stable,
  // location-based id while recursing the layout tree and injects it as `{{id}}`.
  stateCard(label, entity):: {
    kind: 'component',
    template: 'stateCard',
    params: { label: label, entity: entity },
    entities: [entity],
    slots: { state: { entity: entity } },
  },

  sectionTitle(label):: {
    kind: 'component',
    template: 'sectionTitle',
    params: { label: label },
    entities: [],
    slots: {},
  },

  button(label, domain, service, entity):: {
    kind: 'component',
    template: 'button',
    params: { label: label, domain: domain, service: service, entity: entity },
    entities: [],
    slots: {},
  },

  slider(label, entity, domain, service, key, attr, min, max):: {
    kind: 'component',
    template: 'slider',
    params: {
      label: label,
      min: '' + min,
      max: '' + max,
      domain: domain,
      service: service,
      entity: entity,
      key: key,
    },
    entities: [entity],
    slots: {
      state: { entity: entity },
      value: { entity: entity, attribute: attr, default: '0' },
    },
  },

  // Brightness slider preset.
  brightnessSlider(label, entity)::
    self.slider(label, entity, 'light', 'turn_on', 'brightness', 'brightness', 1, 255),

  // ---- dynamic group ----
  // A case: entities matching the group query render with the FIRST case whose
  // `when` matches. `id`/`entity`/`label` are auto-injected per matched entity;
  // dynamic slots use a placeholder entity (rebound at render). For a dynamic
  // slider, the value attribute lives in `slots`, the static config in `params`.
  case(when, template, params={}, slots={}):: {
    when: when,
    template: template,
    params: params,
    slots: slots,
  },

  // Convenience cases for the built-in templates inside a dynamic group.
  dynStateCard(when):: self.case(when, 'stateCard', {}, { state: { entity: '$self' } }),
  dynButton(when, domain, service):: self.case(when, 'button', { domain: domain, service: service }),
  dynSlider(when, domain, service, key, attr, min, max):: self.case(
    when,
    'slider',
    { min: '' + min, max: '' + max, domain: domain, service: service, key: key },
    { state: { entity: '$self' }, value: { entity: '$self', attribute: attr, default: '0' } },
  ),

  dynamic(query, cases):: { kind: 'dynamic', query: query, cases: cases },
}
