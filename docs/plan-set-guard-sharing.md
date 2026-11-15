# Plan: equal set guards shared in the compile

Done ahead of the regions plan (#679, `docs/plan-regions.md` §3 on the `regions` branch), whose R2
would otherwise carry it. Stacked on `call-sets-variable`.

A set's guard is written once by the author (`q.from(xs).where((e) -> …)`) and lands once per
candidate: `query.pkl`'s `buildSet` folds presence into each clause per candidate, since registry
facts settle differently per candidate. Where the residuals are equal, the wire carries the same
expression N times (`snapshots/sets.json`: two candidates, one distinct guard). Two places then
compile and run each copy on its own:
- `Ready.Compile.set` compiles every clause's `when` (`condition(cl.when)`, `Ready.scala:280`),
  and `MemberGraph` decides each candidate's clauses against its own state.
- `Evaluator.present` compiles every candidate's guard for a `count()`/`present()`/`any()` input
  (`inner(g, scope)` per id, `Evaluator.scala:509`).

The compiled run already takes the member's state as an argument, so one run serves every
candidate whose guard is equal.

**State:** agreed in chat (2026-11-15); not built. The wire does not change; grouping guards on
the wire is under the regions plan's "Later".

## Design

- **Compile once per key.** Within one set (and one `Present`), clauses whose `when` has the same
  `ValueExpr.key` share one compiled `Condition`, as equal expressions already share a signal and
  a CEL program.
- **Run once per decision when it reads no member.** A guard that reads no member (it names every
  entity it reads: a `q.entity(x)` term; on an area or floor set every guard) gives the same
  answer for every candidate, so a decision runs it once and reuses the answer. One that reads the
  member shares its compiled form, never its answer.
- "Reads the member" is the existing `ValueExpr.readsMissingSubject`.

## Steps

One branch, `set-guard-sharing`, on `call-sets-variable`.

1. **A test first:** two members with equal guards and different states are decided apart (set
   members and `present()`); a guard that reads no member decides every member alike; an area set
   still decides.
2. **The sharing**, in `Ready.Compile.set`, `MemberGraph`'s decision and `Evaluator.present`.
3. **Measure:** `publishSetOrdered`, `leaderRow`, `tickRender` and `validated` timing before and
   after, plus a set of many candidates with a member-free guard. If nothing moves, record it in
   `docs/experiments.md` and keep only what still simplifies the code.
4. **Docs:** ADR 0003's line on per-candidate guards; the pipeline doc if it names how a set is
   decided. Then this plan is deleted.
