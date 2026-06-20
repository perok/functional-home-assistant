package fh

/** Datastar-driven Home Assistant dashboard frontend.
  *
  * Two-phase architecture:
  *   - build phase (`fh.view.build`): jsonnet composes a `dashboard.json`
  *     artifact ({ templates, layout }) once — a shared library of named
  *     Mustache templates plus a recursive layout tree of rows/columns and
  *     component/dynamic leaves. Jsonnet emits template strings and static node
  *     params; it never injects runtime values.
  *   - runtime phase (`fh.view.runtime`): a long-running http4s server reads
  *     `dashboard.json`, holds live entity state, and pushes re-rendered HTML
  *     fragments over Datastar SSE on every state change (diffing so unchanged
  *     fragments are not re-sent).
  */
package object view
