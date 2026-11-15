# Plan: a call can set a variable

Step P of the regions plan (#679, `docs/plan-regions.md` §9 on the `regions` branch), done ahead
as its own PR.

From the page's side a press is one thing, whatever it reaches: a declared call named by id, the
values it posts, and a pending ask the server commits or refuses. Today two of them are spelled
apart. A service call is a `CallDecl` the template posts through `tapMod.post` to
`sse/call/:slug/:callId` (#702). A variable's choice (`v.choose(x)`: a tab, a select option, a
window chooser) is a `tapMod.SetVar`, a `Click` whose `onclick` a CEL expression assembles
(`core/variable.pkl:90`) from `Variable.below`'s signal names and the `@@VAR:<name>@@` declarer
token, posting to `sse/var/:slug/:declarer/:name/:value?group=`.

After this a variable's choice is a declared call too, routed to this viewer's variables instead
of HA.

**State:** agreed in chat (2026-11-15); not built.

## Design

### The wire

A call is one or the other, a union decoded by `kind`, so no call does both:

```scala
enum CallDecl derives CanEqual:              // decoded by `kind`
  case Service(service: ValueExpr,           // "service": today's four fields
               target: ListMap[IdKind, List[ValueExpr]],
               data: ListMap[String, ValueExpr] = ListMap.empty,
               exclude: ListMap[IdKind, List[ValueExpr]] = ListMap.empty)
  case SetVar(variable: String, value: Option[ValueExpr])   // "setVar": None clears
```

- `CallId.of` hashes the kind with the fields, so no service call's id moves.
- The decoder only picks the case by `kind`; each Pkl declaration class carries its `kind`,
  which `WireShapeSuite` pairs with the Scala case.
- Everything that reads a call's fields today (`doing`, `all`, the seen rule, `Validate`'s call
  rules, `Ready`'s compile, `Renderer.Pressed`) takes the `Service` case; the `SetVar` case has
  one field, its value.

### The author's side

```pkl
c.tap.lights.on(l)       // a service call, as today
v.choose("week")         // a SetVar call; the value a `varMod.Value?`, null clearing it
v.choosing(position)     // the value a press posts (a drag): text, checked as a `varMod.Value`
```

- `tapMod.SetVar` stops being a `Click`: it is a call, placed by `tapMod.post` as a service call
  is. `Call` and `SetVar` share a base holding what a card places (`busy`, `icon`, `label`);
  the service fields stay on `Call`.
- `v.choosing(p)` takes a `slotMod.PostedRead` of type `text`. No card needs it yet; it is the
  same path as a literal, so it is built and tested here or dropped (ponytail) — decide at the
  first commit.

### The press

- **The variable is named, not its declarer.** The server resolves the declarer from the
  pressing node (`?node=`), as the compile resolves a read of it, and `validate` refuses a
  `SetVar` placed by a node with no declarer above it. The `@@VAR` token goes, and with it
  `Variable.declarer`.
- **The body** is `{conn, posted, seen}` for every call: `tapMod.post` adds `conn: $conn`, since a
  `payload` replaces the signals the bundle would otherwise send. A `SetVar` needs the viewer's
  session; a forgotten `conn` is re-minted (ADR 0024) as `sse/var` does now. A service call
  ignores `conn`: a call field reading a variable stays refused.
- **Pending:** the click starts with `{{{tap.pending}}}`, which the renderer fills for a `SetVar`
  with the pending signal of the variable's declarer at that node and the value asked
  (`$_var_<declarer>__<name>__pending = 'week';`), so the press shows at once, byte for byte the
  assignment `choose` writes today. A clear sets nothing pending (`pending || committed` cannot
  hold "nothing"), so the commit shows it.
- **The server** runs today's `setVar` (`Server.scala:1124`): the value checked
  (`Renderer.refusals`), committed, the readers re-rendered. A refusal clears the pending signal
  itself, from the declaration, so `?group=` (sent only by `choose`) goes.
- **Clearing** removes the viewer's choice: the variable reads its declared value again, the
  commit patches that value, and its URL parameter goes.
- **Goes:** the `sse/var/…` route, `choose`'s CEL click string, `Variable.below`'s use in
  `choose`. `below`/`Spelling` stay for `TabButton.active` until the regions plan's R1.

## Steps

One branch, `call-sets-variable`, off main.

1. **Spike the Pkl names** (`choose(null)`, `choosing`, the shared base class) against pkl-core
   0.33 before writing them.
2. **The union.** `CallDecl` by `kind` in Pkl and Scala, `CallId` over it, every reader on the
   `Service` case. Wire snapshots change by the `kind` field only; goldens byte-identical.
3. **`SetVar` is a call.** `v.choose` builds one; the route dispatches by kind; `tap.pending`;
   `conn` in the body; `sse/var` goes; clearing.
   - Goldens and snapshots move: the click bytes of every tab, select option and window chooser
     (`branches`, `cards`, `charts`, and the rest that place one). Read the diff by hand:
     `dashboardGoldensVs` normalises ids only.
   - The suites posting to `sse/var` (`VarTapSuite`, `TabVariableSuite`, `SelectSuite`,
     `IfVariableSuite`, `ControlChooserSuite`, `SetMemberVarsSuite`, `MemberNodesSuite`,
     `ResumeSuite`, `LightSliderSuite`, `SliderControlSelectSuite`, `SurfaceGraphSuite`,
     `PklDashboardBehaviourSuite`, `UiSmokeSuite`) move to the call route through one helper,
     their assertions unchanged. `components.test.pkl`'s URL fact becomes a fact on the
     declaration.
   - New: a refused value clears the pending signal; a clear reads the declared value and drops
     the URL parameter; a `SetVar` with no declarer above is refused at build; a forgotten `conn`
     is re-minted.
   - RenderBench before and after.
4. **Docs**, in the commit that changes each: ADRs 0016 (what a tap does), 0023 (the allowlist
   holds variable choices), 0024 (the body's `conn`), 0025 (pending from `tap.pending`), 0033 (a
   choice is a call; clearing), 0002's route list; the pipeline doc's routes; the module
   `CLAUDE.md` tap paragraph. Then this plan is deleted.

## Open questions

1. **Names**: `choosing`, the shared base class, `SetVar` as both the Pkl class and the wire
   kind.
