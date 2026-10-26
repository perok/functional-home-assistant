# Plan: a device's parts by rank (#541)

`progressOf` (#627) finds the right sensors in the house it was tuned on, but no algorithm shows
in its code: rule levels are worked out in one place, candidates filtered in a second, ties broken
by an `if` chain in a third, and two special cases sit inline. Words the rules cannot change
(`generic`, "charge", "target", the `battery` device class) live outside `defaults.progress`.
Agreed in chat on 2026-10-26: keep the rules API and rewrite the choosing.

## The algorithm

Each role is a `Role`: its name, the shape every sensor in it has, and its rules. A role is
filled in two steps:

1. **Candidates:** the sensors that fit its shape and that one of its rules names, more
   specifically than an `ignore` rule names them. A running total is never one.
2. **Rank:** the candidate whose score is highest, compared field by field:
   1. how specific the rule naming it is: an entity, a `Match`, words;
   2. how many words it shares with the job's anchor, so a total and a status are the same job's;
   3. the plainer name: fewer words ("Cycle phase" over "Cycle sub phase");
   4. a primary sensor over a config or diagnostic one.

   A tie at the top chooses none, and the author names that part on the card.

A job is built around its **anchor**: its countdown, else its percentage. `progressOf` chooses
one anchor and builds the job at it (`jobAt`). Several jobs per device (E5) changes only the
first step, to every anchor that is a job; telling a job's countdown from a consumable's needs E4
first, so E5 is not built here.

## What goes

- The battery while charging (Q34): the battery is never a bar on its own. A car's charge fills
  out of where its countdown started, like the washer's; `.percent(battery)` puts the battery on
  the card, where it always shows.
- `generic`, the charging and battery words, `Played`, `roleNames` with `getProperty`, the
  separate `shapes` table and `pick`.

## Checks and docs

- `progress.test.pkl`: the real house finds the same parts except the car (no battery bar, a bar
  from where its charge started). The battery fact changes with Q34; an entity rule outranking an
  `ignore` word is added.
- ADR 0013 states the rank and "a tie chooses none"; ADR 0015 and `writing-dashboards.md` lose
  the battery while charging.
