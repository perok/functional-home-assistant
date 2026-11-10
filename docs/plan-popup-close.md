# Plan: a popup close writes no markup

Part of the regions work (`docs/plan-regions.md` on branch `regions`, §8), done ahead of it.

When the last popup closes, the server writes the chrome's popup host itself, as the literal
`<div id="popups"></div>` with an `outer` patch: in `Server.scala` around line 1056 (the close in
the host swap) and in `Server.orphanedPopup` (around line 2825, a popup this dashboard no longer
has). The chrome owns that element (`theme.pkl:123`, `theme-beer.pkl:133`:
`<div id="popups">{{{popups}}}</div>`), so an `outer` replace with a literal drops any attribute a
theme puts on it, and it is markup Scala writes that Pkl already wrote.

**State:** not built.

## Change

- Both places send a `remove` patch with the selector `#popups > *` and no elements, instead of
  the literal. The pinned bundle's `remove` mode removes every element the selector matches
  (`querySelectorAll`; `PatchMode.Remove` exists in `Datastar.scala`). The host element itself
  stays, attributes and all.
- Send it only where the host can hold something, as today's conditions already do (a close with
  no new surface; a requested popup the dashboard lacks): on an empty host the bundle logs
  "PatchElementsNoTargetsFound".
- Use `Dashboard.PopupHostId` for the id, as today; no new literal.

## Tests

- `RendererSuite` and any server suite asserting the literal frame move to asserting the `remove`
  frame (`mode remove`, selector `#popups > *`, no elements). `RendererSuite:687–690` asserts the
  page's own `<div id="popups"></div>` from the chrome, which does not change.
- One test that a close leaves an attribute a theme put on `#popups`: the frame sent carries no
  `#popups` element at all.

## Docs

- `docs/architecture-rendering-pipeline.md`, wherever it describes the close; grep for
  `orphanedPopup` and `id="popups"` in `docs/`.

## Done when

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` is green, and `git grep -F
'<div id="popups"></div>' -- modules/*/src/main` finds nothing.
