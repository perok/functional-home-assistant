---
name: sbt
description: sbt build-tool tips specific to this repo — invocation flags and gotchas that differ from sbt defaults. Use when running, scripting, or troubleshooting sbt commands here.
---

## Overview

My own notes to you.

## General

- SBT 2 does not need -batch.

## Invocation

- **Several commands go in ONE quoted argument, separated by `;`.** The sbt 2
  thin client JOINS its argv into a single command string, so `sbt 'a' 'b'`
  is parsed as the one command `a b` and dies with `Expected whitespace
  character`. Write `sbt 'a; b'`. (Cost three CI rounds on #115 before it was
  read rather than remembered.)
- **A dashed project name is not a Scala identifier**, so `set` needs
  `LocalProject`: `sbt 'set LocalProject("fh-datastar-view")/Test/parallelExecution := false'`
  — the bare `set fh-datastar-view/...` form fails with `Not found: fh`.
- **The warning gate is a MODE, not a flag you pass.** `sbt tpolecatCiMode
  <task>` reproduces what CI enforces (`-Werror`) in a running server;
  `SBT_TPOLECAT_CI=1` only reaches a server that has not started yet, which is
  the same reason `-D`/env vars never reach the tests.

## Reproducing a CI-only flake locally

**Ask first WHICH process is slow.** A smoke-suite flake lives in CHROMIUM, and
throttling the JVM does not touch it — nine `testFull` runs at
`ActiveProcessorCount=2`, some with competing CPU hogs, all passed while
`UiSmokeSuite` was failing on CI. The browser is a separate process.

**Browser (the smoke suites):** `FH_SMOKE_CPU_THROTTLE=20` applies CDP
`Emulation.setCPUThrottlingRate` in `SmokeSuite.withPage`. This reproduced a
CI-only failure in 3 of 4 runs, which is what made it fixable at all.
`FH_SMOKE_TRACE_URL=1` alongside it records every `history.replaceState`, which
is how the URL mirror was cleared of blame.

**JVM (everything else):** `.jvmopts` in the repo root, `-XX:ActiveProcessorCount=2`.

```bash
sbt shutdown                                   # BOTH forms are read at server START
echo '-XX:ActiveProcessorCount=2' > .jvmopts
FH_SMOKE_CPU_THROTTLE=20 sbt 'fh-datastar-view/testFull'
rm .jvmopts && sbt shutdown
```

Three things that make either one silently do nothing:

- **`sbt shutdown` first, always.** Env vars AND `.jvmopts` reach only a server
  that has not started yet. Forgetting this produced a clean run that proved
  nothing — twice, in one session; the second time the trace read
  `"not-traced"` and gave it away.
- **`.jvmopts` is the only JVM channel.** `-J`, `SBT_OPTS` and `-D` do not
  reach the long-lived server.
- **Delete `.jvmopts` when done.** It is gitignored now, but a stale one
  throttles every later build and every `Jmh/run`, invalidating benchmark
  numbers rather than failing.
