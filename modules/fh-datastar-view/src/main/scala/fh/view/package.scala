package fh

/** Datastar-driven Home Assistant dashboard frontend.
  *
  * Two-phase architecture:
  *   - build phase (`fh.view.build`): jsonnet composes a `dashboard.json`
  *     artifact ({ templates, registry, layout }) once. Jsonnet emits Mustache
  *     template strings; it never injects runtime values.
  *   - runtime phase (`fh.view.runtime`): a long-running http4s server reads
  *     `dashboard.json`, holds live entity state, and pushes re-rendered HTML
  *     fragments over Datastar SSE on every state change.
  */
package object view
