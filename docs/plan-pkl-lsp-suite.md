# Plan: a record of what the editor offers

A dashboard or component author writes Pkl in an editor running pkl-lsp: VS Code, Neovim,
or the add-on's `/edit` page, which `LspBridge` connects to the staged `pkl-lsp.jar`. Whether
completion, hover and go-to-definition work through our library depends on how the library is
written: a builder typed `LayoutNode` hid a card's builders after `.columns(3)` until #694. Nothing
records which shapes the editor follows and which it does not, so each API decision guesses.

This plan adds a suite that asks pkl-lsp, over the same stdio protocol `/edit` uses, about
marked positions in real workspaces, and a doc listing the answers. The doc is the design guide;
the suite keeps it true.

## What a case is

```scala
Case(
  name = "card builders after a layout builder",
  source = "c.entityCard(light).columns(3).‸",   // ‸ is the cursor
  ask = Completion(includes = Set("tapAction", "label")),
  status = Works
)
```

- `source` is pasted into an entry module of a bootstrapped workspace, so imports are what an
  author has: `@fh-dashboard/components.pkl` as `c`, the dump's entities.
- `ask` is one of `Completion(includes)`, `Hover(contains)`, `Definition(file)`, or
  `Diagnostic(contains)` (a wrong value the editor should flag before the build does).
- `status` is `Works` or `Gap(why)`. A `Gap` asserts that the answer is still missing: when
  pkl-lsp or the library starts answering, the case fails with "now works: mark it `Works`",
  so the record cannot go stale in the hopeful direction either.

## Mechanics

1. **Workspace:** `PklWorkspace.bootstrapInto` plus `DumpPackage.seedFromText` over
   `HouseFixture`, as `UseCaseSuite` builds one. Its manifest names its own module cache, so
   pkl-lsp resolves `@fh-dashboard` with no network.
2. **Client:** a test-side LSP client on fs2's process API, reusing `LspBridge`'s framing
   (`encodeFrame`/`toFrames` become `private[runtime]`) and its JDK choice. One pkl-lsp
   process per suite; one document per case, opened, asked, closed.
3. **Handshake,** from what made earlier manual checks look like failures (memory, 2026-10-25):
   `capabilities.workspace.workspaceFolders: true`; answer `workspace/configuration` with real
   values, never `null`; send `pkl/syncProjects` as a request after that reply; the document
   exists on disk before `didOpen`.
4. **Jar:** `Test / envVars` gets `PKL_LSP_JAR` from `stagePklLsp`, as `run` does. CI runs the
   suite; it is not tagged `Slow` (that tag means "needs a browser").
5. **The doc:** `docs/pkl-editor-support.md`, one table per kind (case, status, why). The suite
   renders the table from the cases and compares it with the file's generated section,
   failing with the text to paste when they differ, the way the goldens do.

## First cases

| Case | Expected today |
|---|---|
| card builders after `.columns(3)` (#694's `: this`) | works? (unchecked) |
| `c.` completes the components through the package import | works (2026-10-25, 0.8.0) |
| a subclass's builders after an inherited one (`c.progress(s).label(…).`) | works? |
| string-literal union values (`stick("‸")`, `style("‸")`) | unknown |
| `new {}` inferred from a parameter type (0.33): members inside it | unknown |
| `e.` inside `render((e) -> …)` on a set | unknown |
| hover on a builder shows its doc comment | unknown |
| go-to-definition on `c.entityCard` lands in `components/entity.pkl` | unknown |
| a slot name typo, an `Edge` value outside the union: diagnostic | unknown |

The unknowns are the point: the first run fills the table, and each `Gap` is then a design
question with evidence (reshape the library, or report to pkl-lsp).

## Not in scope

- Editor-specific behaviour (VS Code snippets, Neovim UI). The protocol answers are the record.
- Changing the library to close a gap. Each one is its own round, decided from the table.

## Open

- Whether a case's `source` should also be built by the dashboard build, so a case cannot
  test code the build refuses. Recommended: yes, cheap with the workspace already there.
