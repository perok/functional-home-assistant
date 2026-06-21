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
    .fh-cell{display:contents}
    /* Components consume the design tokens; fallbacks keep them usable unthemed. */
    body{background:var(--primary-background-color,#fafafa);color:var(--primary-text-color,#212121)}
    .card{background:var(--card-background-color,#fff);color:var(--primary-text-color,#212121);
      border:1px solid var(--divider-color,rgba(0,0,0,.12));border-radius:var(--ha-card-border-radius,12px)}
    .section{color:var(--primary-color,#03a9f4)}
    .state{color:var(--secondary-text-color,#727272)}
    button.card{background:var(--primary-color,#03a9f4);color:var(--text-primary-color,#fff);border:none;cursor:pointer}
    input[type=range]{accent-color:var(--accent-color,#ff9800)}
  |||,
}
