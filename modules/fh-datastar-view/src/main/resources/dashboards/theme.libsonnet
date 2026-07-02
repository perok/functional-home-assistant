// The dashboard theme: design tokens + the stylesheets/CSS that realise them.
//
// Everything visual lives here, NOT in the server — including the choice of CSS
// framework. This default theme uses Pico; a different theme could drop Pico
// and ship its own `stylesheets`/`styles`. The renderer emits the tokens as
// `:root` custom properties (with `tokensDark` under prefers-color-scheme:dark)
// and injects `stylesheets` as <link>s and `styles` inline.
//
// Contract with the components/renderer (the class names they emit):
//   containers -> `.fh-row` / `.fh-col`, entity wrapper -> `.fh-cell`,
//   cards -> `.card`, section titles -> `.section`, state text -> `.state`.
local tokens = import 'tokens.libsonnet';

{
  tokens: tokens.light,
  tokensDark: tokens.dark,

  stylesheets: [
    'https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css',
  ],

  // The dashboard frame: the `#dashboard` swap target (what navigate/reload
  // inner-patch) plus the popup overlay host. The theme owns the WHOLE chrome —
  // including the popup `<dialog>` + its ✕/close-`@post`, inlined here (the theme
  // is self-contained: it imports no component library). The backend only fills
  // `{{{body}}}` and reads no card name. A theme with no popups drops the
  // `<dialog>`. Contract: keep an element with `id="dashboard"` around
  // `{{{body}}}` (the swap target) and `#popups-body` as the popup content host
  // (a surface inner-replaces into it; `swapHost`/`POST /sse/popup/close`).
  chrome: |||
    <main class="container" id="dashboard">{{{body}}}</main>
    <dialog id="popups" open class="popup">
      <button class="popup-close" data-on:click="@post('/sse/popup/close')">✕</button>
      <div id="popups-body"></div>
    </dialog>
  |||,

  styles: |||
    /* Drive Pico's palette from the design tokens so its chrome matches; the
       tokens flip under prefers-color-scheme (see the :root block), so Pico
       follows the browser too. */
    :root{
      --pico-background-color:var(--primary-background-color,#fafafa);
      --pico-color:var(--primary-text-color,#212121);
      --pico-card-background-color:var(--card-background-color,#fff);
      --pico-muted-color:var(--secondary-text-color,#727272);
      --pico-primary:var(--primary-color,#03a9f4);
      --pico-primary-background:var(--primary-color,#03a9f4);
      --pico-primary-inverse:var(--text-primary-color,#fff);
    }
    .fh-row{display:flex;gap:1rem;flex-wrap:wrap}
    .fh-col{display:flex;flex-direction:column;gap:1rem}
    /* A column placed directly in a row becomes one equal-width column, sharing
       the row evenly; min-width:0 lets the equal shares hold with wide content,
       align-self keeps it top-aligned rather than stretching to the row height. */
    .fh-row>.fh-col{flex:1 1 0;min-width:0;align-self:flex-start}
    .fh-cell{display:contents}
    /* Components consume the design tokens; fallbacks keep them usable unthemed. */
    body{background:var(--primary-background-color,#fafafa);color:var(--primary-text-color,#212121)}
    .card{background:var(--card-background-color,#fff);color:var(--primary-text-color,#212121);
      border:1px solid var(--divider-color,rgba(0,0,0,.12));border-radius:var(--ha-card-border-radius,12px)}
    .section{color:var(--primary-color,#03a9f4)}
    .state{color:var(--secondary-text-color,#727272)}
    /* Entity card: secondary info line + tappable affordance. */
    .entity .secondary{display:block;font-size:.8em;color:var(--secondary-text-color,#727272)}
    .card.tappable{cursor:pointer}
    button.card{background:var(--primary-color,#03a9f4);color:var(--text-primary-color,#fff);border:none;cursor:pointer}
    input[type=range]{accent-color:var(--accent-color,#ff9800)}
    /* Tabs: a bar of buttons over one inline panel; the active tab is flagged
       client-side via a signal (see the tabButton's data-class). */
    .tabs{display:flex;flex-direction:column;gap:1rem}
    .tabbar{display:flex;gap:.5rem;flex-wrap:wrap}
    .tabbar .tab{background:transparent;color:var(--primary-text-color,#212121);
      border:1px solid var(--divider-color,rgba(0,0,0,.12))}
    .tabbar .tab.active{background:var(--primary-color,#03a9f4);color:var(--text-primary-color,#fff);border-color:transparent}
    .tab-panel{display:contents}

    /* Popup overlay: the theme-owned host (the `chrome` `<dialog>` above), a fixed-position
       card floated over the page. It ships `open` in the markup (a static
       attribute, never toggled server-side) and hides via CSS alone whenever
       its inner region (`#popups-body`) is empty — no signal, no server
       state; open patches content in (shown), close patches it empty (hidden). */
    dialog.popup{position:fixed;inset:10vh 1rem auto;margin:0 auto;z-index:10;
      border:none;border-radius:var(--ha-card-border-radius,12px);
      background:var(--card-background-color,#fff);color:var(--primary-text-color,#212121);
      padding:1.5rem;max-width:min(90vw,32rem);box-shadow:0 4px 24px rgba(0,0,0,.2)}
    dialog.popup:has(#popups-body:empty){display:none}
    .popup-close{position:absolute;top:.5rem;right:.5rem;background:transparent;
      border:none;color:var(--secondary-text-color,#727272);cursor:pointer;font-size:1rem}
  |||,
}
