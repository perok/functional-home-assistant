// CodeMirror 6 language support for Pkl, built on the Lezer parser generated
// from pkl.grammar. The styleTags mapping is a direct port of
// tree-sitter-pkl's queries/highlights.scm — same tokens, same roles — onto
// @lezer/highlight tags, so the editor's defaultHighlightStyle colours them
// without any bespoke theme.
import { LRLanguage, LanguageSupport } from "@codemirror/language"
import { styleTags, tags as t } from "@lezer/highlight"
import { parser } from "./pkl.parser.js"

const pklLanguage = LRLanguage.define({
  name: "pkl",
  parser: parser.configure({
    props: [
      styleTags({
        // keywords
        "module class typealias function for when if else let new import as extends amends in out": t.keyword,
        "external abstract open local hidden fixed const": t.modifier,
        "unknown nothing": t.keyword,
        "throw trace read ReadNullable ReadGlob ImportGlob": t.keyword,
        // builtin values
        "this outer super": t.self,
        ModuleExpr: t.keyword,
        Null: t.null,
        BooleanLiteral: t.bool,

        // literals
        Number: t.number,
        StringLiteral: t.string,

        // comments
        LineComment: t.lineComment,
        "BlockComment Shebang": t.comment,
        DocComment: t.docComment,

        // identifier roles (paths mirror highlights.scm's node captures)
        TypeName: t.typeName,
        Name: t.variableName,
        "ClassProperty/Name ClassProperty/TypeName": t.propertyName,
        "ObjectProperty/Name ObjectProperty/TypeName": t.propertyName,
        "MethodHeader/Name MethodHeader/TypeName": t.function(t.definition(t.variableName)),
        "TypedIdentifier/Name TypedIdentifier/TypeName": t.definition(t.variableName),
        "Class/Name Class/TypeName": t.definition(t.className),
        "TypeAlias/Name TypeAlias/TypeName": t.definition(t.typeName),

        // annotations
        Annotation: t.meta,
        "@": t.meta,

        // operators
        ArithOp: t.arithmeticOperator,
        CompareOp: t.compareOperator,
        LogicOp: t.logicOperator,
        "Not NonNull": t.logicOperator,
        "=": t.definitionOperator,
        is: t.operatorKeyword,

        // punctuation & brackets
        ", ; .": t.separator,
        "?.": t.derefOperator,
        ":": t.punctuation,
        "( )": t.paren,
        "[ ] [[ ]]": t.squareBracket,
        "{ }": t.brace,
        "< >": t.angleBracket,
      }),
    ],
  }),
  languageData: {
    commentTokens: { line: "//", block: { open: "/*", close: "*/" } },
    closeBrackets: { brackets: ["(", "[", "{", '"'] },
    indentOnInput: /^\s*[}\])]$/,
  },
})

export function pkl() {
  return new LanguageSupport(pklLanguage)
}
