// Dashboard components — pure composition (build phase only).
//
// Two parts:
//   1. `cards`: a SHARED library of cards keyed by name, each a Mustache
//      `template` declaring the `inputs` it references (validated at build
//      time). A card is defined ONCE and reused by every instance.
//   2. builder functions returning layout-tree nodes (`row`/`column`/component
//      leaves / `dynamic` groups). The node references a card by name and is
//      where static values (label, entity, service…) and dynamic slot
//      references are supplied — NOT the card template.
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
  // ---- shared card library: name -> { template, inputs } ----
  // (a node references a card by name; each card has a Mustache `template`.)
  cards: {
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

    // HA-like entity card: friendly name + a value (state or a chosen
    // attribute), optionally with a unit, a secondary info line, and a tap
    // action. Optional pieces are mustache sections that render only when their
    // value is non-empty (the compiler treats "" as falsey); only `label`/`value`
    // are required `inputs` — the rest are supplied per instance when used.
    // Templates carry no `id`: the backend wraps any entity-bound component in
    // the id'd element Datastar morphs (see Renderer). Templates are pure
    // content.
    // NOTE (multiline templates): each HTML *attribute value* must stay on one
    // physical line — line breaks between tags/attributes are harmless
    // whitespace, but a newline inside an attribute would break it.
    entityCard: {
      template: |||
        <article class="card entity{{#tappable}} tappable{{/tappable}}"{{#tappable}}
          data-on:click="@post('/sse/action/{{domain}}/{{service}}/{{{entity}}}')"{{/tappable}}>
          <header>{{label}}</header>
          <span class="state">{{value}}{{#unit}} {{unit}}{{/unit}}</span>{{#secondary}}
          <span class="secondary">{{secondary}}</span>{{/secondary}}
        </article>
      |||,
      inputs: ['label', 'value'],
    },

    // Generic service-call button (toggle, scene activate, lock…). No `id`: it
    // has no entities/slots, so it never re-renders or gets patched.
    button: {
      template: |||
        <button class="card"
          data-on:click="@post('/sse/action/{{domain}}/{{service}}/{{{entity}}}')">{{label}}</button>
      |||,
      inputs: ['label', 'domain', 'service', 'entity'],
    },

    // Value slider (brightness, target temperature…). The backend wraps it in
    // the id'd morph target; the template uses `{{id}}` only to derive a unique
    // per-instance signal name (`val_{{id}}`), working for both static and
    // dynamic instances without a separate `sig` input.
    slider: {
      template: |||
        <article class="card">
          <header><strong>{{label}}</strong>: <span>{{state}}</span></header>
          <input type="range" min="{{min}}" max="{{max}}"
            data-signals="{ val_{{id}}: {{value}} }"
            data-bind="val_{{id}}"
            data-on:change="@post('/sse/action/{{domain}}/{{service}}/{{{entity}}}/{{key}}/' + $val_{{id}})" />
        </article>
      |||,
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
  row(children):: { kind: 'component', card: 'fhrow', children: children },
  column(children):: { kind: 'component', card: 'fhcol', children: children },

  // ---- component leaf builders ----
  // Leaf builders take a dump entity object `eo` (a reference into the dump —
  // see `pick`/`at` in dashboard.jsonnet), NOT a hand-typed name + id. `label`
  // defaults to the entity's `friendly_name` and can be overridden per call.
  // NOTE: ids are NOT authored here — the backend derives a stable,
  // location-based id while recursing the layout tree and injects it as `{{id}}`.
  local nameOf(eo, label) = if label != null then label else eo.friendly_name,

  // Tap presets: pass as `tap=` to make a card call a service on click.
  toggleTap:: { domain: 'homeassistant', service: 'toggle' },

  // HA-like entity card.
  //   attribute  null -> the entity's state, else a named attribute to show.
  //   transform  null -> raw value, else a JSONata expression evaluated in the
  //              backend per live value ($ = the value), e.g.
  //              '$round($number($), 1) & " kW"'.
  //   secondary  null -> no second line, else an attribute shown under the value.
  //   tap        null -> read-only, else { domain, service } to call on click.
  // The unit is offered automatically and shows only when the entity has one.
  entityCard(eo, label=null, attribute=null, transform=null, secondary=null, tap=null):: {
    kind: 'component',
    card: 'entityCard',
    params: {
      label: nameOf(eo, label),
      entity: eo.entity_id,
      [if tap != null then 'domain']: tap.domain,
      [if tap != null then 'service']: tap.service,
      [if tap != null then 'tappable']: '1',
    },
    entities: [eo.entity_id],
    slots: {
      value: {
        entity: eo.entity_id,
        [if attribute != null then 'attribute']: attribute,
        [if transform != null then 'transform']: transform,
      },
      unit: { entity: eo.entity_id, attribute: 'unit_of_measurement', default: '' },
      [if secondary != null then 'secondary']:
        { entity: eo.entity_id, attribute: secondary, default: '' },
    },
  },

  // Back-compat alias: the old read-only state card is just an entity card.
  stateCard(eo, label=null):: self.entityCard(eo, label=label),

  // Static title text (not bound to an entity).
  sectionTitle(label):: {
    kind: 'component',
    card: 'sectionTitle',
    params: { label: label },
    entities: [],
    slots: {},
  },

  button(eo, domain, service, label=null):: {
    kind: 'component',
    card: 'button',
    params: {
      label: nameOf(eo, label),
      domain: domain,
      service: service,
      entity: eo.entity_id,
    },
    entities: [],
    slots: {},
  },

  slider(eo, domain, service, key, attr, min, max, label=null, transform=null):: {
    kind: 'component',
    card: 'slider',
    params: {
      label: nameOf(eo, label),
      min: '' + min,
      max: '' + max,
      domain: domain,
      service: service,
      entity: eo.entity_id,
      key: key,
    },
    entities: [eo.entity_id],
    slots: {
      state: { entity: eo.entity_id },
      value: {
        entity: eo.entity_id,
        attribute: attr,
        default: '0',
        [if transform != null then 'transform']: transform,
      },
    },
  },

  // Toggle button preset (lights, switches, fans…) via homeassistant.toggle —
  // the assumed action for a togglable entity, so callers needn't spell out the
  // domain/service.
  toggle(eo, label=null):: self.button(eo, 'homeassistant', 'toggle', label=label),

  // Brightness slider preset.
  brightnessSlider(eo, label=null)::
    self.slider(eo, 'light', 'turn_on', 'brightness', 'brightness', 1, 255, label=label),

  // ---- dynamic group ----
  // A case: entities matching the group query render with the FIRST case whose
  // `when` matches. `id`/`entity`/`label` are auto-injected per matched entity;
  // dynamic slots use a placeholder entity (rebound at render). For a dynamic
  // slider, the value attribute lives in `slots`, the static config in `params`.
  case(when, card, params={}, slots={}):: {
    when: when,
    card: card,
    params: params,
    slots: slots,
  },

  // Convenience cases for the built-in cards inside a dynamic group. Slots use
  // the '$self' placeholder entity; the renderer rebinds it to each matched
  // entity (and auto-injects id/entity/label) — see Renderer.renderCase.
  dynEntityCard(when, attribute=null, transform=null, secondary=null, tap=null):: self.case(
    when,
    'entityCard',
    {
      [if tap != null then 'domain']: tap.domain,
      [if tap != null then 'service']: tap.service,
      [if tap != null then 'tappable']: '1',
    },
    {
      value: {
        entity: '$self',
        [if attribute != null then 'attribute']: attribute,
        [if transform != null then 'transform']: transform,
      },
      unit: { entity: '$self', attribute: 'unit_of_measurement', default: '' },
      [if secondary != null then 'secondary']:
        { entity: '$self', attribute: secondary, default: '' },
    },
  ),

  // Back-compat alias.
  dynStateCard(when):: self.dynEntityCard(when),
  dynButton(when, domain, service):: self.case(when, 'button', { domain: domain, service: service }),
  dynSlider(when, domain, service, key, attr, min, max):: self.case(
    when,
    'slider',
    { min: '' + min, max: '' + max, domain: domain, service: service, key: key },
    { state: { entity: '$self' }, value: { entity: '$self', attribute: attr, default: '0' } },
  ),

  dynamic(query, cases):: { kind: 'dynamic', query: query, cases: cases },
}
