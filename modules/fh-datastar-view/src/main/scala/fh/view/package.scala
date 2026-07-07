package fh

/** Datastar-driven Home Assistant dashboard frontend.
  *
  * Two-phase architecture:
  *   - the dashboard is authored as Pkl: a shared library of named cards
  *     (Mustache templates) plus a recursive layout tree of component nodes
  *     that reference cards by name (containers splice their children; leaves
  *     bind entity slots). Pkl emits template strings and static node params;
  *     it never injects runtime values. `DashboardBuild` fetches the live
  *     entity dump and evaluates the Pkl into the `{ cards, card }` model
  *     (`card` is the root layout node).
  *   - build phase (`fh.view.build` / `BuildApp`): evaluates + persists the
  *     `dashboard.json` artifact for inspection/CI.
  *   - runtime phase (`fh.view.runtime` / `ServerApp`): evaluates the same Pkl
  *     **in memory** on startup, holds live entity state, and pushes
  *     re-rendered HTML fragments over Datastar SSE on every state change
  *     (diffing so unchanged fragments are not re-sent).
  */
package object view
