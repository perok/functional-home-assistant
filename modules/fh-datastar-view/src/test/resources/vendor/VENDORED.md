# Vendored test-only assets

The Playwright smoke suite (`fh.view.smoke`) runs Chromium fully offline — this
session's egress policy blocks `cdn.jsdelivr.net` (where `theme-beer.pkl` and
`Server.DatastarCdn` point in production), so the browser needs local copies of
what it would otherwise fetch. `TestServer.served` pre-seeds an `AssetCache`
from these files instead of hitting the network (see `VendoredAssets.scala`).

These are **test fixtures, not production assets** — production still serves
from the real CDNs (or `AssetCache.build`'s real fetch) unchanged.

| File | Source | Version | License |
|---|---|---|---|
| `datastar.js` | `raw.githubusercontent.com/starfederation/datastar/v1.0.2/bundles/datastar.js` — byte-identical to what `Server.DatastarCdn` (`cdn.jsdelivr.net/gh/...@v1.0.2/...`) serves in production, jsdelivr's `/gh/` CDN being a mirror of that exact GitHub tag. `raw.githubusercontent.com` is reachable from this session even though `cdn.jsdelivr.net` isn't. (An earlier attempt vendored the `@starfederation/datastar` NPM package instead and rebundled it with esbuild — do NOT do that again: NPM's `1.0.0-beta.*` line uses a materially different, non-colon attribute syntax with no `init` plugin, so `data-init`/`data-on:click` silently never fired.) | `v1.0.2` — same tag `Server.DatastarCdn` pins | MIT |
| `beer.min.css`, `beer.min.js` | `beercss` npm package, `dist/cdn/` | `4.0.23` — same version `theme-beer.pkl` pins | MIT |
| `material-symbols-outlined.woff2` | `beercss` npm package, `dist/cdn/` | `4.0.23` | Apache-2.0 (Material Symbols) |

Not vendored (tolerated 404s against the fake client, since `AssetCache`
degrades per-URL failures to "keep the original CDN URL" and BeerCSS's
`cacheCss` sub-resource fetch already tolerates individual misses): the
`rounded`/`sharp`/`subset` Material Symbols weights and BeerCSS's decorative
`.shape` SVGs — nothing this project's card templates reference.

To refresh `datastar.js`: bump `Server.DatastarCdn`'s tag, then
`curl https://raw.githubusercontent.com/starfederation/datastar/<tag>/bundles/datastar.js`.
To refresh the BeerCSS files: bump `theme-beer.pkl`'s `beerVersion` together
with the version here, then re-run the `npm install beercss@<version>` +
copy-from-`dist/cdn` steps.
