// Dashboard components — pure composition (build phase only).
//
// Each component is defined in ONE place: a `_components` entry holding its
// Mustache `template`, the `slots` it declares (validated at build
// time), and its `build` function (the layout-tree node it emits). The
// backend-facing `cards` library is then DERIVED mechanically from those
// entries (see `cards` below), and the public builders are thin static fields
// delegating to each component's `build` — so a node references a card by name
// and supplies static values + slot references, never the template itself.
//
// Why a hidden `_components` + derived `cards` (rather than authoring `cards`
// directly): it co-locates each template with the builder that fills it (they
// must agree on input names), while keeping the builders as STATIC top-level
// fields — the only thing editors autocomplete. The derived `cards` map has
// dynamic keys (not autocompletable), but it is backend-facing only: authors
// call builders, never `c.cards.*`.
//
// Datastar v1 attribute syntax uses COLONS: `data-on:click`, `data-bind`,
// `data-signals`. A click is a whole Datastar expression carried by the
// `onclick` slot — a bare-string literal for a constant click (popup/navigate),
// or a `c.expr(...)` JSONata expression when it reads the entity per render:
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
  // A LIVE value expression for a display field that also accepts a plain string
  // literal (currently `label`). Wrap a JSONata expression over the entity
  // ($state/$attr/$domain/$entity_id) so the field tracks state and repaints
  //   c.entityCard(eo, label=c.expr('$attr.friendly_name & " " & $state'))
  // vs the literal form `c.entityCard(eo, label='Kitchen')`. The builder binds
  // the expression to the entity (and rebinds it per match for `d.matched`).
  expr(s):: { transform: s },

  // Like c.expr, but sources the value from ANOTHER entity (binds the slot to
  // `eo` rather than inheriting the card's `entity_id`). This is the multi-entity
  // card: a `label`/`value`/`secondary` reading a DIFFERENT entity than the one
  // the card is about. The card joins both entities' live-dependency sets, so it
  // re-renders when EITHER changes.
  //   c.entityCard(power, secondary=c.exprOf(voltage, '$state & " V"'))
  exprOf(eo, s):: { entityId: eo.entity_id, transform: s },

  // ---- slot/onclick helpers shared by the builders below ----
  // Leaf builders take a dump entity object `eo` (a reference into the dump —
  // see `pick`/`at` in dashboard.jsonnet), NOT a hand-typed name + id. `label`
  // defaults to the entity's `friendly_name` and can be overridden per call.
  // NOTE: ids are NOT authored here — the backend derives a stable,
  // location-based id while recursing the layout tree and injects it as `{{id}}`.

  // The card's `label` SLOT (not a param). ONE `label` argument, accepting
  // either form:
  //   - a STRING → a hardcoded value: a bare-string literal slot (no entity, no
  //     JSONata) — just the given text, used verbatim.
  //   - `c.expr('<jsonata>')` → a LIVE expression over the entity (e.g.
  //     '$attr.friendly_name & " " & $state'), entity-bound and repainting live.
  // When no label is given:
  //   - the dynamic match sentinel ($self) → the matched entity's live
  //     friendly_name (falling back to its id), rebound per match by the renderer.
  //   - a static dump entity → its friendly_name baked as a literal (no live
  //     lookup, no entity binding) — the cheap default for the common case.
  local labelSlot(eo, label) =
    if label != null && std.isObject(label) then
      // c.expr(...) -> { transform: <expr> }; it inherits the card's entity, so
      // it is live (and binds to each match in a dynamic case).
      // bypassUnavailable: false — a label must keep running its transform so an
      // unavailable entity still shows its name, not the literal "unavailable".
      { bypassUnavailable: false } + label
    else if label != null then
      label
    else if eo == null then
      ''
    else if eo.entity_id == '$self' then
      // Dynamic default: a LIVE friendly_name, inheriting the matched entity.
      { transform: '$attr.friendly_name ? $attr.friendly_name : $entity_id', bypassUnavailable: false }
    else
      (if std.objectHas(eo, 'friendly_name') && eo.friendly_name != '' then eo.friendly_name else eo.entity_id),

  // Auto-appended unit for a value-display: the entity's unit_of_measurement,
  // space-separated, when it has one. This is why the entity card needs no
  // separate unit slot.
  local UNIT = ' & ($attr.unit_of_measurement ? " " & $attr.unit_of_measurement : "")',

  // A `string | c.expr(...)` display field -> a JSONata transform:
  //   c.expr('...') -> its expression verbatim; a STRING -> that attribute.
  // The shared decode behind value/secondary (entity card) and the slider's
  // position. Unit auto-appending is layered on TOP by the entity card's value
  // (a display string); other fields use this raw (no unit).
  local exprOrAttr(field) =
    if std.isObject(field) then field.transform else '$attr.' + field,

  // Carry an explicit cross-entity binding through a display-field descriptor: a
  // `c.exprOf(eo, ...)` field names its own `entityId`, so the slot reads that
  // entity instead of inheriting the card's. A plain string / `c.expr(...)` (no
  // entityId) inherits as before. (labelSlot needs none of this — it merges the
  // whole descriptor object, so an `entityId` already rides along.)
  local entityOf(field) =
    if std.isObject(field) && std.objectHas(field, 'entityId') then
      { entityId: field.entityId }
    else {},

  // The card's `value` SLOT (the primary reading). Like `label`, ONE argument
  // accepting either form, plus a smart default:
  //   - null          -> the entity's state, unit auto-appended.
  //   - a STRING       -> that attribute, unit auto-appended (a shortcut).
  //   - c.expr('...')  -> your JSONata expression verbatim (you own the output,
  //     unit included) — the live escape hatch, the SAME wrapper as `label`.
  // (Unit applies to the shortcut/default forms, not to a c.expr you own.)
  // (No entityId: it inherits the card's `entity_id` — the static entity, or
  // the matched entity in a dynamic case.)
  local valueSlot(value) = {
    transform:
      if std.isObject(value) then value.transform
      else (if value == null then '$state' else '$attr.' + value) + UNIT,
  } + entityOf(value),

  // The card's optional `secondary` SLOT (a second line). A STRING -> that
  // attribute (no unit); c.expr('...') -> your expression. (Only built when the
  // author passes a secondary, so it need not handle null.)
  local secondarySlot(secondary) =
    {
      transform: exprOrAttr(secondary),
      default: '',
      bypassUnavailable: true,
    } + entityOf(secondary),

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

  // A CONSTANT click expression (no live value): a hardcoded literal used
  // verbatim, so it is just the string itself — no JSONata, no entity binding.
  local constOnclick(js) = js,

  // The placeholder a node uses to refer to its own backend-assigned id (the
  // SAME id the renderer injects as `{{id}}`); the build-phase hoist
  // (DashboardBuild.hoistInlineSurfaces / NodeIdToken) splices the real id in.
  // This is how a builder composes a trigger that references the surface id it
  // cannot mint: write '<NODE_ID>_<localKey>' and pair it with an
  // `inlineSurfaces: { <localKey>: ... }` marker.
  local NODE_ID = '@@NODE_ID@@',

  // The default tap descriptor (domain-resolved service call). Its onclick reads
  // `$entity_id`/`$domain`, so it is a LIVE expression (c.expr), not a literal.
  local defaultTap = { onclick: $.expr(serviceOnclick(defaultRoute)) },

  // Add the `onclick` slot for a tap descriptor (service / popup / navigate /
  // tab). A descriptor's onclick is either a `c.expr(...)` (a live click
  // expression reading the entity — bound here) or a bare-string literal (a
  // constant click, e.g. a popup open referencing a fixed surface id via NODE_ID,
  // which the hoist splices in). jsonnet, not the backend, composes the onclick.
  local tapSlot(tap) =
    if tap != null && std.objectHas(tap, 'onclick') then
      { onclick:
        if std.isObject(tap.onclick) then
          // The click expression is identity-derived: it inherits the card's
          // `entity_id` to resolve `$entity_id`/`$domain`, but reactive: false
          // keeps it OUT of the live-dependency set (its value never changes
          // with state), and bypassUnavailable: false keeps it resolving even
          // when the entity is unavailable.
          { bypassUnavailable: false, reactive: false } + tap.onclick
        else tap.onclick }
    else {},

  // Attach the inline-surfaces marker (a node-level map the backend hoists into
  // the `surfaces` registry, keying each by '<node-id>_<localKey>'). The trigger
  // already references those ids via NODE_ID, so the hoist only splices + lifts.
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
  serviceTap(action):: { onclick: $.expr(serviceOnclick('"' + action + '"')) },
  // TODO should these belong to popup? as the build function?
  // `mount` optionally targets a specific Mount node id (an inline panel);
  // absent ⇒ the overlay `#popups`. There is no exclusivity `group` any more —
  // an inline mount is exclusive by construction, an overlay stacks.
  openPopup(target, mount=null)::
    if std.isString(target) then
      { onclick: constOnclick('@post(\'/sse/surface/open/' + target + '\')') }
    else
      {
        // Reference the future surface id (NODE_ID_self) the hoist will mint, and
        // pair it with the inline content under the same local key.
        onclick: constOnclick('@post(\'/sse/surface/open/' + NODE_ID + '_self\')'),
        // 'self' is a jsonnet keyword, so quote the local key.
        inlineSurfaces: { 'self': {
          content: target,
          [if mount != null then 'mount']: mount,
        } },
      },
  closePopup(id):: { onclick: constOnclick('@post(\'/sse/surface/close/' + id + '\')') },
  navigate(slug):: { onclick: constOnclick(
    '@post(\'/sse/navigate/' + slug + '\'); history.pushState(null,\'\',\'/d/' + slug + '\')'
  ) },

  // ---- domain-aware slider config ----
  // The slider's controllable config per HA domain: the service to call, the
  // service_data key the value rides on, the range bounds, and which attribute
  // is the live position. The builder resolves these from the entity's $domain
  // AT RUNTIME (JSONata $lookup, the same mechanism the button's service uses) —
  // so one `c.slider(eo)` works for any of these domains, and a dynamic case
  // whose branch is not single-domain resolves each matched entity correctly.
  // Add a domain (cover/fan/climate…) by adding a row here, no builder change.
  // https://developers.home-assistant.io/docs/core/entity/light/
  local sliderSpec = {
    light: { action: 'light/turn_on', key: 'brightness', min: 1, max: 255, value: 'brightness' },
  },

  // A JSONata object literal { "<domain>": <spec[field]> } over the known
  // domains — `{"light":"light/turn_on"}` etc. (JSONata object syntax IS JSON).
  local sliderDomainMap(field) =
    std.manifestJsonMinified({
      [d]: sliderSpec[d][field]
      for d in std.objectFields(sliderSpec)
    }),
  // `$lookup(<map>, $domain)` — resolve a config field from the entity's domain.
  local sliderLookup(field) = '$lookup(' + sliderDomainMap(field) + ', $domain)',
  // The live position: read the domain's position attribute off $attr.
  local sliderValueLookup = '$lookup($attr, ' + sliderLookup('value') + ')',
  // A slider config slot: an explicit literal override, else the $domain lookup.
  // Config is identity-derived (reactive: false — never a live dependency) and
  // resolves even when the entity is unavailable (bypassUnavailable: false).
  local sliderConfig(override, field) =
    if override != null then '' + override
    else { transform: sliderLookup(field), reactive: false, bypassUnavailable: false },

  // ---- the component library: template + declared inputs + builder, together ----
  // Hidden (the dashboard imports specific fields, never the whole object). Each
  // entry's `build` returns a layout node referencing the entry by name; new
  // container/leaf kinds are added as one entry here (template + build) with no
  // Scala change. `cards` (below) is derived from these. `$` inside a `build` is
  // the library root, so it reaches the helpers/descriptors above and sibling
  // builders (e.g. tabs composes `$.button`).
  _components:: {
    // Containers: splice the backend-rendered children. Add new container
    // kinds (grids, titled sections, tabs…) by adding an entry here — no Scala
    // change needed.
    // Optional `class` LITERAL slot: extra CSS classes on the container (e.g.
    // `.tabbar`/`.tabs`), so a styled container reuses these generic builders
    // instead of a bespoke card. Absent -> the `{{#class}}` section is empty.
    fhrow: {
      template: '<div class="fh-row{{#class}} {{class}}{{/class}}">{{#children}}{{{html}}}{{/children}}</div>',
      slots: [],
      build(children, class=null):: {
        kind: 'component',
        card: 'fhrow',
        children: children,
      } + (if class != null then { slots: { class: class } } else {}),
    },
    fhcol: {
      template: '<div class="fh-col{{#class}} {{class}}{{/class}}">{{#children}}{{{html}}}{{/children}}</div>',
      slots: [],
      build(children, class=null):: {
        kind: 'component',
        card: 'fhcol',
        children: children,
      } + (if class != null then { slots: { class: class } } else {}),
    },

    // Static title text (not bound to an entity). `label` is a constant LITERAL
    // slot (a bare string) — there are no params; every template var is a slot.
    sectionTitle: {
      template: '<h2 class="section">{{label}}</h2>',
      slots: ['label'],
      build(label):: {
        kind: 'component',
        card: 'sectionTitle',
        slots: { label: label },
      },
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
    //
    // The three display fields share ONE convention — a plain string for the
    // field-specific shortcut, or `c.expr('<jsonata>')` for a live expression
    // over the entity ($state/$attr/$domain/$entity_id):
    //   label      null -> friendly_name; STRING -> literal text; c.expr -> live.
    //   value      null -> state (+ unit); STRING -> that attribute (+ unit);
    //              c.expr -> your expression verbatim, e.g.
    //              c.expr('$round($number($state), 1) & " kW"').
    //   secondary  null -> no second line; STRING -> an attribute; c.expr -> live.
    //   tap        null -> read-only, else a tap descriptor to call on click.
    entityCard: {
      template: |||
        <article class="card entity{{#tappable}} tappable{{/tappable}}"{{#tappable}}
          data-on:click="{{{onclick}}}"{{/tappable}}>
          <header>{{label}}</header>
          <span class="state">{{value}}</span>{{#secondary}}
          <span class="secondary">{{secondary}}</span>{{/secondary}}
        </article>
      |||,
      slots: ['label', 'value', 'entity_id'],
      build(eo, label=null, value=null, secondary=null, tap=null):: {
        kind: 'component',
        card: 'entityCard',
        // entity_id is the card's subject — the magical slot every other slot
        // inherits; the derived live-dependency set comes from the slots that
        // read it. `tappable` is a constant literal slot too.
        slots: {
          entity_id: eo.entity_id,
          [if tap != null then 'tappable']: '1',
          label: labelSlot(eo, label),
          value: valueSlot(value),
          [if secondary != null then 'secondary']: secondarySlot(secondary),
        } + tapSlot(tap),
      } + tapInline(tap),
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
    //
    // `eo` is the entity (or null for an action with no entity — a popup
    // open/close or a navigate); `action` is a tap descriptor (toggleTap /
    // serviceTap / openPopup / navigate), null defaulting to a domain-resolved
    // service call (which needs an entity). `entity_id` is only set when there's
    // an entity; the onclick slot reads it via `$entity_id` (unused by a constant
    // popup/navigate expression, so `''` is fine when absent).
    //   active  null -> a plain button; else a Datastar boolean expression that
    //           makes it a TAB (the `tab` class + a `data-class` highlight while
    //           the expression holds). Used by `c.tabs`.
    button: {
      template: |||
        <button class="card{{#active}} tab{{/active}}" data-on:click="{{{onclick}}}"{{#active}}
          data-class="{active: {{{active}}}}"{{/active}}>{{label}}</button>
      |||,
      slots: ['label', 'onclick'],
      build(eo=null, action=null, label=null, active=null):: (
        local tap = if action == null then defaultTap else action;
        {
          kind: 'component',
          card: 'button',
          // entity_id is the subject the onclick inherits to resolve
          // $entity_id/$domain (only when there's an entity); reactive: false
          // keeps the button out of the live set unless a live `label` slot
          // pulls the entity in. `active` is a constant literal slot.
          slots: {
            [if eo != null then 'entity_id']: eo.entity_id,
            [if active != null then 'active']: active,
            label: labelSlot(eo, label),
          } + tapSlot(tap),
        } + tapInline(tap)
      ),
    },

    // Value slider (brightness, target temperature…). The backend wraps it in
    // the id'd morph target; the template uses `{{id}}` only to derive a unique
    // per-instance signal name (`val_{{id}}`), working for both static and
    // dynamic instances without a separate `sig` input.
    //
    // DOMAIN-AWARE: `c.slider(eo)` configures itself from the entity's domain
    // (see `sliderSpec`) — the service/key/range/position resolve at runtime via
    // JSONata `$lookup($domain)`, the SAME mechanism the button's service uses.
    // So it works for a static light AND a dynamic `d.matched` (whose branch may
    // not be single-domain). Each config arg is an EDGE-CASE override:
    //   value  the slider position source: null -> the domain's position
    //          attribute; a STRING -> that attribute (e.g. 'brightness');
    //          c.expr('...') -> your expression. No unit is appended (it is a
    //          bare number for the range input's signal).
    //   action/key/min/max  null -> resolved from $domain; pass a value to
    //          override (e.g. `c.slider(eo, max=200)`).
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
      slots: ['label', 'state', 'value', 'action', 'min', 'max', 'key', 'entity_id'],
      build(eo, value=null, label=null, action=null, key=null, min=null, max=null):: {
        kind: 'component',
        card: 'slider',
        // entity_id is the card's subject — the template's action URL splices it
        // and every slot inherits it. action/key/min/max/value resolve from
        // $domain by default (or an explicit override).
        slots: {
          entity_id: eo.entity_id,
          label: labelSlot(eo, label),
          // state is the display header (inherits entity_id) — bypassUnavailable
          // defaults to true.
          state: {},
          // value is the range input's numeric position: opt OUT so an unavailable
          // entity falls back to `default` ('0') rather than the string "unavailable".
          value: {
            transform:
              if value == null then sliderValueLookup else exprOrAttr(value),
            default: '0',
            bypassUnavailable: false,
          },
          // action/min/max/key: $domain lookups (reactive: false), or a literal
          // override.
          action: sliderConfig(action, 'action'),
          min: sliderConfig(min, 'min'),
          max: sliderConfig(max, 'max'),
          key: sliderConfig(key, 'key'),
        },
      },
    },

    // Surface chrome (backend-only — no `build`; the renderer wraps a surface's
    // content as `children` and injects `id`/`closeAction`). Keeping these in
    // the card library, not hardcoded in Scala, means a re-skin happens here.
    //   popup    — the overlay `<dialog>` with a wrapper-supplied close control.
    //   tabPanel — the inline panel wrapper (no dialog chrome, no close).
    popup: {
      template: '<dialog id="{{id}}" open class="popup"><button class="popup-close" data-on:click="{{{closeAction}}}">✕</button>{{#children}}{{{html}}}{{/children}}</dialog>',
      slots: [],
    },
    tabPanel: {
      template: '<div id="{{id}}" class="tab-panel-content">{{#children}}{{{html}}}{{/children}}</div>',
      slots: [],
    },
  },

  // ---- shared card library: name -> { template, slots } ----
  // DERIVED from `_components` (the mechanical part). A node references a card by
  // name; the backend decodes this into its template + declared inputs. Dynamic
  // keys here are fine — `cards` is backend-facing only (authors call builders).
  cards: {
    [name]: {
      template: $._components[name].template,
      slots: $._components[name].slots,
    }
    for name in std.objectFields($._components)
  },

  // ---- public builders: static fields delegating to each component's build ----
  // These are what authors call (and what editors autocomplete).
  row:: $._components.fhrow.build,
  column:: $._components.fhcol.build,
  sectionTitle:: $._components.sectionTitle.build,
  entityCard:: $._components.entityCard.build,
  button:: $._components.button.build,
  slider:: $._components.slider.build,

  // A MOUNT: an addressable host a surface renders into — the inline analogue of
  // the page `#popups`. `mode` is 'inline' (a tab panel: inner/replace, one at a
  // time) or 'overlay' (stack dialogs). NOT a card — a structural `LayoutNode.Mount`
  // the backend renders directly (it bakes any default-open surface targeting it).
  // `signals` is an optional data-signals seed (a tab group's active-tab signal).
  mount(mode, signals=null):: {
    kind: 'mount',
    mode: mode,
  } + (if signals != null then { signals: signals } else {}),

  // TABS: pure composition over existing primitives — no `tabs` card, no tabs
  // logic in the backend. A column [a `.tabbar` row of buttons, an inline `mount`]
  // whose panels are ordinary inline surfaces (lifted by the hoist) sharing that
  // mount, so switching reuses the popup open machinery (inner-replace) and only
  // the open panel is rendered/streamed. The mount is the column's child index 1,
  // so its pathId is '<NODE_ID>_1' — what the surfaces name as their `mount`. The
  // active-tab signal `tab_<NODE_ID>` is seeded on the mount, set by each button
  // (to its index), and read by each button's `active` highlight.
  //   c.tabs([{ label: 'Lights', content: c.column([...]) }, ...])
  tabs(tabs)::
    local panelId = NODE_ID + '_1';   // the mount node = column child index 1
    local sig = 'tab_' + NODE_ID;     // per-group active-tab signal (holds an index)
    local sid(i) = NODE_ID + '_t' + i;  // surface id = idBase + '_' + localKey ('t'+i)
    {
      kind: 'component',
      card: 'fhcol',
      slots: { class: 'tabs' },
      inlineSurfaces: {
        ['t' + i]: {
          content: tabs[i].content,
          mount: panelId,
          chrome: 'tabPanel',
          stack: false,
          bakeInto: NODE_ID,
          bakeAs: 'panel',
          // The first tab is the default panel: baked into the tabs component + seeded open.
          [if i == 0 then 'defaultOpen']: true,
        }
        for i in std.range(0, std.length(tabs) - 1)
      },
      children: [
        $.row([
          $.button(
            label=tabs[i].label,
            action={ onclick: constOnclick(
              '@post(\'/sse/surface/open/' + sid(i) + '\'); $' + sig + ' = ' + i
            ) },
            active='$' + sig + ' == ' + i,
          )
          for i in std.range(0, std.length(tabs) - 1)
        ], class='tabbar'),
        $.mount(mode='inline', signals='{ ' + sig + ': 0 }'),
      ],
    },

  // Back-compat alias: the old read-only state card is just an entity card.
  stateCard(eo, label=null):: $._components.entityCard.build(eo, label=label),
  // NOTE: no `toggle`/`brightnessSlider` presets — `c.button(eo)` already
  // resolves its service from $domain at RUNTIME (so it toggles a light/switch/
  // fan), and `c.slider(eo)` resolves its whole config from $domain too (so it
  // is the brightness slider for a light). Both gain domain behavior from the
  // entity, not a preset.

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
  //     d.case(d.whenDomain('light'), c.slider(d.matched)),
  //   ], fallback=c.entityCard(d.matched, tap=c.toggleTap)))
  //   d.group(d.lowBattery(20), c.entityCard(d.matched))   // single card
  //
  // Self-reference via the named local `dyn` (not `self`/`$`), so the nested
  // result objects of when/group can still reach sibling helpers.
  dynamic::
    local dyn = {
      // ---- query predicates (the Predicate AST) ----
      cmp(property, op, value):: { kind: 'cmp', property: property, op: op, value: value },
      and(items):: { kind: 'and', items: items },
      or(items):: { kind: 'or', items: items },
      pnot(item):: { kind: 'not', item: item },
      // Matches every entity (domain is never literally this sentinel).
      always:: dyn.cmp('domain', 'ne', '__never__'),
      whenDomain(d):: dyn.cmp('domain', 'eq', d),
      whenState(s):: dyn.cmp('state', 'eq', s),
      whenDeviceClass(cls):: dyn.cmp('attr:device_class', 'eq', cls),
      // e.g. attrLessThan('battery_level', 20)
      attrLessThan(attr, value):: dyn.cmp('attr:' + attr, 'lt', value),
      stateLessThan(value):: dyn.cmp('state', 'lt', value),
      // Battery sensors expose the % as their STATE with device_class "battery"
      // (non-numeric states like "unavailable" simply don't match `state < x`).
      lowBattery(threshold):: dyn.and([
        dyn.whenDeviceClass('battery'),
        dyn.stateLessThan(threshold),
      ]),

      // The placeholder "current entity" for a case. Any leaf builder takes it
      // like a real dump entity; its slots leave `entityId` unset and so inherit
      // the matched entity, which the renderer injects as the `entity_id` param
      // per match (the `$self` sentinel is build-time only — it tells `labelSlot`
      // to emit a LIVE friendly_name default rather than a baked literal).
      matched:: { entity_id: '$self' },

      // One branch: a predicate + the card (built against `matched`) to render
      // when it matches. Keeps the card + its slots, but drops the build-time
      // `entity_id` slot (the renderer sets the matched entity as the subject per
      // match) and the unused children. Every other slot (label inheriting the
      // match, value/action) rides along.
      case(when, node):: {
        when: when,
        card: node.card,
        slots: {
          [k]: node.slots[k]
          for k in std.objectFields(node.slots)
          if k != 'entity_id'
        },
      },

      // Per-entity card DISPATCH: the FIRST branch whose predicate matches selects
      // the card; `fallback` is the card for entities matching no branch (omitted ⇒
      // they render nothing). Pass the result as the single card of `group`.
      // (`fallback`, not `else` — `else` is a jsonnet keyword.)
      when(branches, fallback=null):: {
        cases: branches +
               (if fallback != null then [dyn.case(dyn.always, fallback)] else []),
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
          else [dyn.case(dyn.always, card)],
      },
    };
    dyn,
}
