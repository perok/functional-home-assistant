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
