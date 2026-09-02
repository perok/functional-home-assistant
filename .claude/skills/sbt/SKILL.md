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

## Squeezing the box down to CI's shape

A timing flake that only ever fails on GitHub usually needs the runner's
CORE COUNT, not its OS. This box has 22 cores; the runner has a handful.

```bash
sbt shutdown                                   # the flag is read at server START
echo '-XX:ActiveProcessorCount=2' > .jvmopts   # repo ROOT
sbt 'fh-datastar-view/testFull'
rm .jvmopts && sbt shutdown                    # put it back
```

Three things this cost real time to learn:

- **`.jvmopts` is the only channel that works.** `-J`, `SBT_OPTS` and `-D` do
  NOT reach the long-lived server — same trap as env vars and test flags.
- **It only takes effect on a fresh server.** Without the `shutdown` you are
  measuring the old JVM and will believe a false negative. Confirm with
  `tr '\0' '\n' < /proc/<sbt-pid>/cmdline | grep ActiveProcessorCount`.
- **DELETE it when done.** It is gitignored now, but a stale one silently
  throttles every later build and benchmark on this checkout — including
  `Jmh/run`, where it quietly invalidates the numbers.

Fewer cores is not the whole of CI, though: there the sbt step runs
alongside `pkl test` and `scala-cli test` in the parallel block. To emulate
that, run background CPU hogs next to the suite. Measured limit of this
technique — nine local `testFull` runs (plain, 2-core, 2-core + load) all
passed while `UiSmokeSuite` was failing on CI, so it reproduces LOAD but not
whatever else the runner does differently.
