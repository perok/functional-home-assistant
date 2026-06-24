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
// `data-signals`. A click is a whole Datastar expression carried by the
// `onclick` slot (a JSONata expression evaluated in the backend per entity):
//   - service call: @post('/sse/action/<domain>/<service>/<entity>')
//   - popup:        @post('/sse/surface/open|close/<id>')
//   - navigate:     @post('/sse/navigate/<slug>'); history.pushState(...)
// The slider's value-carrying change handler is assembled in its own template
// (it needs the per-instance signal `$val_{{id}}`):
//   @post('/sse/action/<domain>/<service>/<entity>/<key>/' + $signal)
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
      params: [],
      slots: [],
    },
    fhcol: {
      template: '<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>',
      params: [],
      slots: [],
    },

    sectionTitle: {
      template: '<h2 class="section">{{label}}</h2>',
      params: ['label'],
      slots: [],
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
    // The tap `action` is a single "<domain>/<service>" path (e.g.
    // "homeassistant/toggle"), spliced raw into the action route; it is an
    // optional slot the builder fills (a default resolved from the entity's
    // domain, or an override), so it needs no `params`/`slots` entry.
    entityCard: {
      template: |||
        <article class="card entity{{#tappable}} tappable{{/tappable}}"{{#tappable}}
          data-on:click="{{{onclick}}}"{{/tappable}}>
          <header>{{label}}</header>
          <span class="state">{{value}}</span>{{#secondary}}
          <span class="secondary">{{secondary}}</span>{{/secondary}}
        </article>
      |||,
      params: ['label'],
      slots: ['value'],
    },

    // Generic click button (service call, popup open/close, navigate, tab…). No
    // `id`: it has no entities, so it never re-renders or gets patched. `onclick`
    // is a computed slot carrying the WHOLE Datastar click expression (a
    // `@post(...)`, optionally followed by a `history.pushState(...)` for
    // navigation, or a signal-set for a tab), so the template stays
    // action-agnostic. `active` is an OPTIONAL Datastar boolean expression: when
    // present the button becomes a tab (the `tab` class + a `data-class` that
    // highlights it while the expression is true) — that is the whole difference
    // between a button and a tab.
    button: {
      template: |||
        <button class="card{{#active}} tab{{/active}}" data-on:click="{{{onclick}}}"{{#active}}
          data-class="{active: {{{active}}}}"{{/active}}>{{label}}</button>
      |||,
      params: ['label'],
      slots: ['onclick'],
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
            data-on:change="@post('/sse/action/{{{action}}}/{{{entity_id}}}/{{key}}/' + $val_{{id}})" />
        </article>
      |||,
      params: ['label', 'min', 'max', 'key'],
      slots: ['state', 'value', 'action'],
    },

    // Tabs container: a bar of tab buttons + a single inline panel showing one
    // tab at a time. The panel's content IS a surface (one per tab, sharing an
    // exclusivity group + this `mount`), so switching reuses the popup
    // open/close machinery — it just patches `inner` into the mount instead of
    // appending an overlay. `panel` is the first tab's HTML, baked in by the
    // backend so the initial paint shows it with no round-trip; `initial` seeds
    // the active-tab signal `{{sig}}` (client-side highlight). The author never
    // writes any of these — `c.tabs(...)` emits an inline marker the build-phase
    // hoist expands (see DashboardBuild.hoistInlineSurfaces).
    tabs: {
      template: |||
        <div class="tabs" data-signals="{ {{sig}}: '{{initial}}' }">
          <div class="tabbar">{{#children}}{{{html}}}{{/children}}</div>
          <div class="tab-panel" id="{{mount}}">{{{panel}}}</div>
        </div>
      |||,
      params: ['sig', 'initial', 'mount'],
      slots: [],
    },
  },

  // ---- layout container builders (templated components with children) ----
  row(children):: { kind: 'component', card: 'fhrow', children: children },
  column(children):: { kind: 'component', card: 'fhcol', children: children },

  // Tabs: a tab bar over a single inline panel, one tab visible at a time.
  //   c.tabs([{ label: 'Lights', content: c.column([...]) }, ...])
  //
  // This is PURE composition: each tab is a normal `button` whose `content`
  // becomes an inline surface (all sharing one exclusivity group + one inline
  // mount, so opening one swaps the panel). The bar button opens its panel AND
  // sets a per-group signal to the active surface id; the same signal drives the
  // button's `active` highlight and (seeded to the first id) the baked default
  // panel. Every surface id is written as 'NODE_<i>' — the build-phase hoist
  // mints the real id namespace and splices it in (and lifts the surfaces to the
  // top-level registry); no tabs/popup logic lives in the backend.
  tabs(tabs):: {
    local mount = 'panel_' + NODE,  // the shared inline panel container id
    local sig = 'tab_' + NODE,      // per-group active-tab signal (holds an id)
    local sid(i) = NODE + '_' + i,  // local key i -> future surface id

    kind: 'component',
    card: 'tabs',
    entities: [],
    slots: {},
    params: {
      sig: sig,
      mount: mount,
      initial: sid(0),  // default panel: baked inline + seeds the signal
    },
    inlineSurfaces: {
      [std.toString(i)]: {
        content: tabs[i].content,
        group: mount,
        mount: mount,
      }
      for i in std.range(0, std.length(tabs) - 1)
    },
    children: [
      // `self` here is this tabs object; `$` is the library root (where the
      // `button` builder lives).
      $.button(
        label=tabs[i].label,
        action={ onclick: constOnclick(
          '@post(\'/sse/surface/open/' + sid(i) + '\'); $' + sig + ' = \'' + sid(i) + '\''
        ) },
        active='$' + sig + ' == \'' + sid(i) + '\'',
      )
      for i in std.range(0, std.length(tabs) - 1)
    ],
  },

  // ---- component leaf builders ----
  // Leaf builders take a dump entity object `eo` (a reference into the dump —
  // see `pick`/`at` in dashboard.jsonnet), NOT a hand-typed name + id. `label`
  // defaults to the entity's `friendly_name` and can be overridden per call.
  // NOTE: ids are NOT authored here — the backend derives a stable,
  // location-based id while recursing the layout tree and injects it as `{{id}}`.
  local nameOf(eo, label) = if label != null then label else eo.friendly_name,

  // The displayed value of an entity card, as a JSONata expression (there is no
  // bare `$` — the value is whatever the expression returns). A custom transform
  // wins; otherwise show the state (or the chosen attribute) and append the
  // entity's unit_of_measurement when it has one. This is why the entity card
  // needs no separate unit slot.
  local valueTransform(attribute, transform) =
    if transform != null then
      transform
    else
      local base = if attribute != null then '$attr.' + attribute else '$state';
      base + ' & ($attr.unit_of_measurement ? " " & $attr.unit_of_measurement : "")',

  // The default service route (domain/service) for a tap, resolved per entity in
  // the backend ($domain is the entity-id prefix). Scenes/scripts turn_on,
  // buttons press, everything else homeassistant.toggle. Override with
  // serviceTap(...).
  // https://www.home-assistant.io/docs/scripts/perform-actions/#homeassistant-actions
  local defaultRoute =
    '($a := $lookup({"scene": "scene/turn_on", "script": "script/turn_on", ' +
    '"button": "button/press", "input_button": "input_button/press"}, $domain); ' +
    '$a ? $a : "homeassistant/toggle")',

  // Wrap a JSONata route expression into the full Datastar click expression
  //   @post('/sse/action/<domain>/<service>/<entity_id>')
  // The result is itself a JSONata expression (string concatenation with `&`);
  // single quotes are literal inside the JSONata double-quoted segments.
  local serviceOnclick(route) =
    '"@post(\'/sse/action/" & (' + route + ') & "/" & $entity_id & "\')"',

  // A CONSTANT click expression (no live value), as a JSONata string literal.
  // `js` must use only single quotes so it is safe inside JSONata double quotes.
  local constOnclick(js) = '"' + js + '"',

  // The placeholder a node uses to refer to its own backend-minted id namespace;
  // the build-phase hoist (DashboardBuild.hoistInlineSurfaces / NodeIdToken)
  // splices the real id in. This is how a builder composes a trigger that
  // references the surface id it cannot mint: write '<NODE>_<localKey>' and pair
  // it with an `inlineSurfaces: { <localKey>: ... }` marker.
  local NODE = '@@NODE@@',

  // The default tap descriptor (domain-resolved service call).
  local defaultTap = { onclick: serviceOnclick(defaultRoute) },

  // Add the `onclick` slot for a tap descriptor (service / popup / navigate /
  // tab). The descriptor always carries the full click expression; for an inline
  // popup it references the future surface id via NODE (see openPopup), which the
  // hoist splices in — jsonnet, not the backend, composes the onclick.
  local tapSlot(tap, eo) =
    if tap != null && std.objectHas(tap, 'onclick') then
      { onclick: { entity: eo.entity_id, transform: tap.onclick } }
    else {},

  // Attach the inline-surfaces marker (a node-level map the backend hoists into
  // the `surfaces` registry, keying each by '<node-id>_<localKey>'). The trigger
  // already references those ids via NODE, so the hoist only splices + lifts.
  local tapInline(tap) =
    if tap != null && std.objectHas(tap, 'inlineSurfaces') then
      { inlineSurfaces: tap.inlineSurfaces } else {},

  // Tap descriptors: pass as `tap=`/`action=` to give a card a click action.
  //   toggleTap        -> service call resolved from the entity's domain (default).
  //   serviceTap(a)    -> explicit "<domain>/<service>" override, e.g. "lock/lock".
  //   openPopup(id)    -> open the surface registered under `id`.
  //   openPopup(node)  -> inline popup content; the backend hoists it to a surface.
  //   closePopup(id)   -> close that surface (drop a close button inside it).
  //   navigate(slug)   -> in-place swap to dashboard `slug` (+ URL via pushState).
  toggleTap:: defaultTap,
  serviceTap(action):: { onclick: serviceOnclick('"' + action + '"') },
  openPopup(target, group=null, mount=null)::
    if std.isString(target) then
      { onclick: constOnclick('@post(\'/sse/surface/open/' + target + '\')') }
    else
      {
        // Reference the future surface id (NODE_self) the hoist will mint, and
        // pair it with the inline content under the same local key.
        onclick: constOnclick('@post(\'/sse/surface/open/' + NODE + '_self\')'),
        // 'self' is a jsonnet keyword, so quote the local key.
        inlineSurfaces: { 'self': {
          content: target,
          [if group != null then 'group']: group,
          [if mount != null then 'mount']: mount,
        } },
      },
  closePopup(id):: { onclick: constOnclick('@post(\'/sse/surface/close/' + id + '\')') },
  navigate(slug):: { onclick: constOnclick(
    '@post(\'/sse/navigate/' + slug + '\'); history.pushState(null,\'\',\'/d/' + slug + '\')'
  ) },

  // HA-like entity card.
  //   attribute  null -> the entity's state, else a named attribute to show.
  //   transform  null -> the value with its unit auto-appended, else a JSONata
  //              expression over $state/$attr evaluated in the backend per live
  //              value (you own the output, unit included), e.g.
  //              '$round($number($state), 1) & " kW"'.
  //   secondary  null -> no second line, else an attribute shown under the value.
  //   tap        null -> read-only, else { domain, service } to call on click.
  entityCard(eo, label=null, attribute=null, transform=null, secondary=null, tap=null):: {
    kind: 'component',
    card: 'entityCard',
    params: {
      label: nameOf(eo, label),
      entity_id: eo.entity_id,
      [if tap != null then 'tappable']: '1',
    },
    entities: [eo.entity_id],
    slots: {
      value: {
        entity: eo.entity_id,
        transform: valueTransform(attribute, transform),
        bypassUnavailable: true,
      },
      [if secondary != null then 'secondary']:
        { entity: eo.entity_id, transform: '$attr.' + secondary, default: '', bypassUnavailable: true },
    } + tapSlot(tap, eo),
  } + tapInline(tap),

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

  // Generic click button. `eo` is the entity (or null for an action with no
  // entity — a popup open/close or a navigate); `action` is a tap descriptor
  // (toggleTap / serviceTap / openPopup / navigate), null defaulting to a
  // domain-resolved service call (which needs an entity). `entity_id` is only set
  // when there's an entity; the onclick slot reads it via `$entity_id` (unused by
  // a constant popup/navigate expression, so `''` is fine when absent).
  //   active  null -> a plain button; else a Datastar boolean expression that
  //           makes it a TAB (the `tab` class + a `data-class` highlight while
  //           the expression holds). Used by `c.tabs`.
  button(eo=null, action=null, label=null, active=null):: (
    local tap = if action == null then defaultTap else action;
    local entity = if eo != null then eo.entity_id else '';
    {
      kind: 'component',
      card: 'button',
      params: {
        label: nameOf(eo, label),
        [if eo != null then 'entity_id']: eo.entity_id,
        [if active != null then 'active']: active,
      },
      entities: [],
      slots: tapSlot(tap, { entity_id: entity }),
    } + tapInline(tap)
  ),

  // `action` is the slider's "<domain>/<service>" (e.g. "light/turn_on"); `key`
  // the service_data key the value rides on (e.g. "brightness").
  slider(eo, action, key, attr, min, max, label=null, transform=null):: {
    kind: 'component',
    card: 'slider',
    params: {
      label: nameOf(eo, label),
      min: '' + min,
      max: '' + max,
      entity_id: eo.entity_id,
      key: key,
    },
    entities: [eo.entity_id],
    slots: {
      state: { entity: eo.entity_id, bypassUnavailable: true },
      value: {
        entity: eo.entity_id,
        transform: if transform != null then transform else '$attr.' + attr,
        default: '0',
      },
      action: { entity: eo.entity_id, transform: '"' + action + '"' },
    },
  },

  // Toggle button preset (lights, switches, fans…) — the action defaults from
  // the entity's domain, so callers needn't spell out the service.
  toggle(eo, label=null):: self.button(eo, label=label),

  // Brightness slider preset.
  brightnessSlider(eo, label=null)::
    self.slider(eo, 'light/turn_on', 'brightness', 'brightness', 1, 255, label=label),

  // ---- dynamic groups (the whole DSL, namespaced under `dynamic`) ----
  // A dynamic group renders one card PER live entity matching a `query`,
  // optionally dispatching the card per entity. The matched entity's
  // id/entity_id/label are auto-injected and EVERY slot is rebound to it (see
  // Renderer.renderCase) — so a case is built with the SAME leaf builders as a
  // static card, just passing `dynamic.matched` as the entity. That is why there
  // are no `dyn*` builders: `c.entityCard`/`c.button`/`c.slider` (+ presets)
  // serve both static and dynamic use.
  //
  // Usage (alias `local d = c.dynamic;`):
  //   d.group(d.whenState('on'), d.when([
  //     d.case(d.whenDomain('light'), c.brightnessSlider(d.matched)),
  //   ], fallback=c.entityCard(d.matched, tap=c.toggleTap)))
  //   d.group(d.lowBattery(20), c.entityCard(d.matched))   // single card
  //
  // `self` inside the nested object-literal bodies (when/group) rebinds to that
  // object, so they reach siblings via `$.dynamic.*` (the library root).
  dynamic:: {
    // ---- query predicates (the Predicate AST) ----
    cmp(property, op, value):: { kind: 'cmp', property: property, op: op, value: value },
    and(items):: { kind: 'and', items: items },
    or(items):: { kind: 'or', items: items },
    pnot(item):: { kind: 'not', item: item },
    // Matches every entity (domain is never literally this sentinel).
    always:: self.cmp('domain', 'ne', '__never__'),
    whenDomain(d):: self.cmp('domain', 'eq', d),
    whenState(s):: self.cmp('state', 'eq', s),
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

    // The placeholder "current entity" for a case. Any leaf builder takes it like
    // a real dump entity; '$self' is rebound to each match at render time.
    // (`friendly_name` is unused — the renderer injects the matched label.)
    matched:: { entity_id: '$self', friendly_name: '' },

    // One branch: a predicate + the card (built against `matched`) to render when
    // it matches. Keeps the card + its slots; drops the per-entity params the
    // renderer injects (entity_id/label) and the unused entities/children.
    case(when, node):: {
      when: when,
      card: node.card,
      params: {
        [k]: node.params[k]
        for k in std.objectFields(node.params)
        if k != 'entity_id' && k != 'label'
      },
      slots: node.slots,
    },

    // Per-entity card DISPATCH: the FIRST branch whose predicate matches selects
    // the card; `fallback` is the card for entities matching no branch (omitted ⇒
    // they render nothing). Pass the result as the single card of `group`.
    // (`fallback`, not `else` — `else` is a jsonnet keyword.)
    when(branches, fallback=null):: {
      cases: branches +
             (if fallback != null then [$.dynamic.case($.dynamic.always, fallback)] else []),
    },

    // A dynamic group: render ONE `card` for every live entity matching `query`.
    // `card` is a single leaf (built against `matched`) or a `when(...)` selector
    // for per-entity dispatch. Either lowers to the runtime `Dynamic(query,
    // cases)` model.
    group(query, card):: {
      kind: 'dynamic',
      query: query,
      cases:
        if std.objectHas(card, 'cases') then card.cases
        else [$.dynamic.case($.dynamic.always, card)],
    },
  },
}
