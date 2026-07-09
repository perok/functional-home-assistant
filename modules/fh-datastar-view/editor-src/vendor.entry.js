// Everything the editor's app.js needs, re-exported so esbuild can bundle it
// (and its transitive deps) into one self-contained vendor.js with a single
// @codemirror/state instance. app.js imports these from "./vendor.js".
export { EditorState, Compartment } from "@codemirror/state"
export { EditorView, keymap, lineNumbers, highlightActiveLine } from "@codemirror/view"
export { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
export {
  StreamLanguage, LanguageSupport, syntaxHighlighting,
  defaultHighlightStyle, indentOnInput, bracketMatching,
} from "@codemirror/language"
export { closeBrackets } from "@codemirror/autocomplete"
export { LSPClient, languageServerExtensions, languageServerSupport } from "@codemirror/lsp-client"
