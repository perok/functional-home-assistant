# Plan: markup the server writes comes from Pkl

Part of the regions work (`docs/plan-regions.md` on branch `regions`, §9), done ahead of it. The
rule: Scala renders markup Pkl wrote and fills only values it owns. Two places break it today.

**State:** not built.

## 1. The failed-fetch label

`Fetches.label(tone, text)` (`fetch/Fetches.scala:64`) writes, by hand, the structure of
`core/text.pkl`'s `label(tone, body)`, and `FailureLabelSuite` holds the two copies equal.

- The library emits the label as a template the build compiles like any card's: one entry on the
  dashboard (beside `css`, as `entry.pkl` puts the base CSS), with `{{tone}}` and `{{text}}`
  holes, built from `text.pkl`'s `label` so there is one copy.
- The runtime renders it with the tone and text where it calls `Fetches.label` today. The text is
  still a constant ("… unavailable"), escaped as any slot value.
- `Fetches.label` and `FailureLabelSuite` go; the comment at `text.pkl:65–66` goes with them.
- A test that a failed fetch renders the label with the library's classes, through the compiled
  template (the property `FailureLabelSuite` held, now by construction).

## 2. The offline banners

`Server.scala` (around lines 1925–1930) writes the two offline banners inline in the page shell:
the stream banner (`fh-offline fh-offline-sse`, two messages and a Reload button) and the HA
banner (`fh-offline fh-offline-ha`), passed through `themeClasses.rewriteTemplate`.

- They become a theme template, `Theme.offlineBanners` in `theme.pkl` with the current markup as
  the default, so a theme can restyle and translate them. `theme-beer.pkl` keeps the default.
- The signals they read are Pkl constants in the library, not typed into the template: `_sse` and
  the HA-down signal (`Server.HaDownSignal`). A Scala test holds the Pkl constants equal to the
  server's names, as `VarSignalNamesSuite` does for variables.
- The server places the rendered template where the literal is now, still inside the root
  `data-signals` div, still through `rewriteTemplate`. The root div, its seeds and handlers stay
  in Scala.
- The existing tests on the banners keep passing unchanged; the visual snapshots must not move.

## Docs

`docs/architecture-rendering-pipeline.md` where it lists what the shell writes; `theme.pkl`'s
theme-author notes (the new field); `docs/writing-components.md` only if it mentions the label's
copy.

## Done when

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` is green with the wire snapshots
regenerated deliberately (`sbt dashboardSnapshotsUpdate`, diff read: only the new label template
and theme field), and no Scala source under `src/main` writes `fh-label` or `fh-offline` markup.
