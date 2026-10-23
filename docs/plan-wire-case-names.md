# Plan — wire discriminators are the Scala case names, verbatim (#616)

Stacked on #617 (#615). Every discriminator the dashboard wire carries is spelled exactly as the
Scala case it decodes to, so no decoder renames anything and a reader learns one rule, not three.

## Today

- `LayoutNode`, `Predicate`, `SortKey`, `Activation`, `VarAt`, `Access`: one shared `Configuration`
  (`Dashboard.scala`, top), `toLowerCase` plus `SetNode` → `set`.
- `Transform.Stage`: `stage`, `toLowerCase`.
- `Transform.Simple`: `op` is the `Simple.Op` case in lower camel case; `kind` is `value` / `match`,
  read by the one hand-written dispatch (ADR 0028).

Nothing checks that Pkl writes a name Scala accepts until a decode fails.

## The change

| Family | Pkl writes |
|---|---|
| `LayoutNode` | `kind: "Component"` (class `Node`), `"SetNode"` |
| `Predicate` | `"Cmp"`, `"Count"`, `"First"`, `"And"`, `"Or"`, `"Not"` (classes `PAnd`, `POr`, `PNot`) |
| `SortKey` | `"Prop"`, `"Holds"` |
| `Activation` | `"User"`, `"Var"`, `"State"` |
| `VarAt` | `"Value"`, `"Index"`, `"Otherwise"` |
| `Access` | `"Public"`, `"Authenticated"`, `"Admin"`, `"Users"` |
| `Stage` | `stage: "Passthrough"` (a class now, so it is checked), `"Chart"` |
| `Simple` | `kind: "Op"` / `"Match"`; `op` is the case name, equal to the Pkl class name (`"Attr"`) |

- Every `withTransformConstructorNames` is removed.
- `SetNode` stays `SetNode` in Scala: `LayoutNode.Set` would shadow `scala.Set`, so the wire says
  `"SetNode"`.
- `Simple`'s `kind` follows the same rule: its cases are `Op` and `Match`.
- Each discriminator is declared as a string-literal type (`kind: "Cmp" = "Cmp"`), so reflection
  can read it and Pkl refuses any other spelling. `Access` is the exception: it assigns a plain
  String (`kind = "Public"`), because `SiteAccessSuite` proves that an unreadable site rule falls
  back to the default rather than to public, and a literal type would refuse that rule in Pkl
  first. Its literals are pinned by `WireShapeSuite`'s decode test instead.
- Pkl class names stay as they are (`Node`, `PAnd`, `UserActivation`): renaming them is an author-
  facing change the issue does not ask for. `Simple`'s operator classes already match.

## The check

`WireShapeSuite` reads every library class's discriminator literal through `pkl:reflect` and checks
it against the Scala case names, taken from each family's `Mirror` rather than a list in the test.
A class with a literal discriminator in no family fails too, so a new one cannot go unchecked. For
`Simple.Op`, the class name must also equal its `op`.

## Not changing

- Plain string values that are not discriminators: a comparison's `op` (`"eq"`, matched ignoring
  case), `reads`, `Region.fill`, a `SignalBind` (`"class:x"`).
- `Transform.Simple.key` / `Transform.Stage.key`: signal names and cache keys, not the wire.
- `ha-api`'s `type`, which is Home Assistant's own names.

## Tests

Simple edits only: wire JSON written by hand in suites changes its `kind` / `op` / `stage` strings,
and the snapshots are regenerated.

## Docs

ADR 0028 §3 (the `kind` / `op` spelling), and any ADR or Pkl doc comment that quotes a lowercase
discriminator.

## Done when

- No `withTransformConstructorNames` in the dashboard model.
- `WireShapeSuite` checks every discriminator against the Scala case names.
- This plan is deleted.
