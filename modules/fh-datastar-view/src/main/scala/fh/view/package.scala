package fh

/** Datastar-driven Home Assistant dashboard frontend.
  *
  * Two-phase architecture:
  *   - the dashboard is authored as jsonnet: a shared library of named Mustache
  *     templates plus a recursive layout tree of templated component nodes
  *     (containers splice their children; leaves bind entity slots). Jsonnet
  *     emits template strings and static node params; it never injects runtime
  *     values. `DashboardBuild` fetches the live entity dump and evaluates the
  *     jsonnet into the `{ templates, layout }` model.
  *   - build phase (`fh.view.build` / `BuildApp`): evaluates + persists the
  *     `dashboard.json` artifact for inspection/CI.
  *   - runtime phase (`fh.view.runtime` / `ServerApp`): evaluates the same
  *     jsonnet **in memory** on startup, holds live entity state, and pushes
  *     re-rendered HTML fragments over Datastar SSE on every state change
  *     (diffing so unchanged fragments are not re-sent).
  */
package object view
