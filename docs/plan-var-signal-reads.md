# Plan: a variable read in a bool signal slot

The open tab is lit twice over. `cellClassAtRender("fh-chosen", v.valueIs(choice))` writes the
class into the first HTML for the viewer, and `TabButton.active` keeps it live: a Datastar string
spelled in Pkl from `Variable.below.chosen`, around an `@@VAR:<name>@@` token that `DashboardBuild`
splices with the declarer's id. `validate` refuses a variable read in a signal slot, since a
signal is shared by every viewer. The splice resolves a declarer with a scope walk of its own
(`DashboardBuild.spliceDeclarers`), beside `Dashboard.scopeOf`, so the regions plan's R5, where a
popup starts with the root's scope, would have to change both.

**State:** agreed in chat 2026-11-15 as its own PR, stacked on `regions` (#679) and below the
regions plan's R1; not built.

## Design

- **`v.valueIs(x)` in a bool signal slot binds the variable's own signals** at the node:
  `String($<pending> || $<committed>) === 'x'`, in `VarKey`'s names, the declarer resolved where
  the node sits (`VarGraph.keyAt`, as `tap.pending` is filled). No frame is ever sent for it: the
  server writes the committed signal, the press writes the pending one, and the shell seeds both
  for every declarer. It reads the ask while one is out, as every selection display does (ADR
  0025); the server's `v.valueIs(x)` reads the commit.
- **The document form writes it for the viewer**, so the first HTML, and a page without JS, shows
  the open tab: what `cellClassAtRender` does today, folded into the slot.
- **Any other variable read in a signal stays refused**: `v.value`, a step after the read, a read
  beside state. `v.value` in a text signal would be the same lowering, left until a card needs it.
- The tab button is `.cellClassWhen("fh-chosen", v.valueIs(choice))`, and nothing in Pkl spells a
  signal name.

**Goes:**
- `TabButton.active` and its slot.
- `cellClassAtRender`, `Cell.atRender`, `Ready.AtRender`, `Validate.atRenderErrors` and the
  renderer's `atRender`.
- `Variable.declarer`, `below`, `own` and `Spelling`, with `tapMod.pendingSignal` and
  `pendingOrCommittedOf`.
- `DashboardBuild.declarerToken`, `spliceDeclarers` and the hoist's declarer scopes.
- `VarSignalNamesSuite`, which pairs `Spelling` with `VarKey` and has nothing left to pair, and
  `components.test.pkl`'s "a variable spells its declarer" fact. The fact that a variable's name
  is a token stays, read without `declarer`.

## Steps

One commit, or two if the deletions read better apart.
- Tests:
  - a tab lit in the first HTML for the viewer's value;
  - lit on a press before the commit, and back on a refusal;
  - another card's `cellClassWhen(v.valueIs(x))` behaving the same;
  - no `datastar-patch-signals` entry for it, the negative check `SignalSlotSuite` makes;
  - `validate` refusing `v.value` and a read beside state in a signal, naming the slot.
- Wire snapshots and goldens move only on the tab buttons; read the diff, then
  `dashboardSnapshotsUpdate`.
- `RenderBench` before and after, since a template changes.
- Docs:
  - ADR 0033: where a variable is read; `@@VAR` leaves its future-work entry on `@@NODE_ID@@`.
  - ADR 0017: a signal slot that reads a variable. ADR 0025: the display reads the ask.
  - ADRs 0008, 0012 and 0023 where they name `atRender` or the token.
  - The pipeline doc, `terminology.md`, `writing-components.md` and the module `CLAUDE.md`.

This plan is deleted when it lands.
