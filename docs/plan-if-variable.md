# Plan: an If chosen by a node variable (#510)

## What an author writes

```pkl
c.iff(window.valueIs("30d"))
  .then(c.title("Month"))
  .`else`(c.historyChart(s).chosen())
```

`c.iff` takes a condition on a variable as well as one on entity state. Else and else-if
work as today. `is` is a Pkl keyword, so the method is `valueIs`, beside `q`'s `stateIs`.

A condition cannot mix the two: `valueIs` is not a predicate, so `and`/`or` cannot combine
it with one. A mixed condition (the issue's Alternative B) stays a design note.

## How it works

The branches are a bake group selected by the variable, as a `c.select`'s panels already
are (`Variable.selecting`, ADR 0033):

- `then` is the member selected at the value.
- `else` is a new kind of member, selected at **every other value**:
  `VarActivation.otherwise` / `Activation.Var(…, otherwise = true)`.
- A variable-selected group always shows a member, and a write is refused when no member
  takes the value (`Renderer.refusals`). So an If over a variable always has the fallback
  member, empty when the author gave no `else`; otherwise every other value would be
  refused for the whole variable.

Server: `SurfaceGraph.memberAt` falls back to the `otherwise` member, and `Surface.selectedAt`
is none for it. That one change covers selection, refusals and the swap on a write.
`validate`: the declared value needs no member of its own where a fallback exists; two
fallbacks in one group is an error.

## Tests

- Pkl facts: the members and activations an If over a variable builds, with and without
  `else`.
- Through the server: the branch at the declared value, after writing the value, and after
  writing another value (the fallback), with no refusal.
- `validate`: a declared value only the fallback takes builds; two fallbacks fail.

## Docs touched

ADR 0007 (conditional sections), ADR 0033 (node variables), the rendering pipeline's bake
group description if it names how a member is chosen, terminology if it defines a member's
selection.
