# Plan: what really drives the Pi's memory rise

The add-on's memory goes up with every release. This plan finds what holds the memory, on the
Pi's own workspace, and fixes what it can measure. Each fix is its own commit with the numbers
before and after.

## How it is measured

On x86 with the add-on's java line from `home-addon/run.sh` (`-Xms64M -Xmx512M
-XX:+UseSerialGC`, the jar and the isolate jar from `fh-dashboard/assembly`), plus
`-XX:ActiveProcessorCount=4` like the Pi and `-XX:NativeMemoryTracking=summary`. The data is
the live house (read-only) and a copy of the Pi's workspace (read-only through `GET /edit/file`).

The scenario: boot with no site-eval or minifier cache (the Pi's first boot after a release),
three SSE clients, then five edits of `site.pkl`, each a real evaluation. At each step: RSS
(anon/file), the NMT categories, and the heap's live set (after a full GC).

The Pi itself, 0.1.61 after 12.7 h (`/system/diagnostics`, 2026-11-15 23:02): cgroup `current`
698 MB, `anon` 383 MB, `file` 12 MB; heap committed 519 MB, which is the whole `-Xmx512M`
(tenured 205 MB used of 358); metaspace 135 MB; code cache 48 MB; 20 threads.

## Findings

1. **The evaluated site is never released.** pkl-core's stdlib is a JVM-wide static, and the
   Truffle inline caches in its AST keep the user code they last ran. A JFR old-object path
   from a full-GC survivor: `BaseModule$ListClass.instance` → the `List` class's AST →
   `mapIndexed` → `ApplyVmFunction2Node`'s direct-call cache → a workspace lambda's call target →
   `InvokeMethodVirtualNode.cachedReceiverClass`, a workspace `VmClass` → its prototype →
   frames → the module's cached values, which is the whole evaluated site. `Evaluator.close`
   does not reach these caches. In-process, the live set after each edit was 50 → 136 → 204 →
   301 MB (up to three evaluations pinned at once), then 55 once the caches went megamorphic.
   The heap grew to its 512 MB cap on the way and never gave it back: RSS 452 → 925 MB.
2. **glibc malloc arenas hold freed native memory.** The 64 MB-aligned anonymous mappings held
   ~160 MB RSS while the JVM's own malloc was 42 MB live (86 MB peak). `jcmd
   System.trim_native_heap` on a running server: RSS 650 → 526 MB.
3. **The heap does not shrink.** SerialGC with the default 40/70 free ratios keeps the heap at
   about 2.5 times the old generation's live data, and shrinks only in steps on full GCs. This
   is draft #471's finding, still unmerged.
4. **Metaspace is mostly our own classes.** 112 MB for 22k classes, of which the app loader's
   16.5k come to 93 MB, and 6,703 are lambda proxies. It grows with the code, so it grows with
   every release. Not addressed yet.
5. **The Pi's `current` is 300 MB above `anon + file`.** The report carries only those two
   keys, so the rest (kernel, sock, shmem, swap) cannot be placed from here.

## Fixes

First round, which forced a full GC before each reading and did not boot every run from the same
cache state. Settled means 30 s after the fifth edit.

| # | Change | Settled RSS / heap committed / live (MB) |
|---|--------|------------------------------------------|
| – | BASE (`slot-classes` e725e0b3) | 882 / 442 / 71 |
| a | `-XX:TrimNativeHeapInterval` | 659 / 342 / 68 |
| b | Site evaluation in a child JVM (`PklEvalChild`) | 650 / 246 / 49 |
| a+b | both | 491 / 217 / 49 |
| a+b+#471 | plus `MinHeapFreeRatio=10 MaxHeapFreeRatio=30 -ShrinkHeapInSteps` | 429 / 114 / 52 |

Controlled round: every boot with no site-eval or minifier cache, base = BASE as shipped, head =
a + b. RSS / live (MB):

| Reading | GC forced before each | | No GC forced | |
|---|---|---|---|---|
| | base | head | base | head |
| boot | 723 / 130 | 552 / 52 | 724 | 598 |
| peak over the five edits | 951 / 301 | 597 / 59 | 979 | 853 |
| settled | 832 / 72 | 555 / 53 | 979 | 836 |
| settled, then one full GC | 771 / 61 | 555 / 53 | 926 / 74 | 775 / 61 |

With no GC forced, head's heap still fills to ~496 MB committed: the server's own build garbage
(decode, validate, the renderers) is promoted, and the old generation expands instead of being
collected. That is #471's finding, and why its heap policy is measured next, on `memory-hunt-2`.

- **a, trim the native heap every 30 s** (`run.sh`, 173a757d).
- **b, evaluate the site in a child JVM** (0ff72f7f). Under `FH_PKL_EVAL=child`, which `run.sh`
  sets, `PklEvalChild` runs `evaluateOutputText` and exits, so the pinned graph and Pkl's JIT
  code go with it. The project, lockfile, import graph and site-eval cache stay in the server.
  Dev runs and tests evaluate in process; `PklEvalChildSuite` holds the child's JSON equal to
  the in-process JSON. The cost is time: an edit's evaluation took 0.6–0.8 s in process and
  1.9–2.0 s in the child, on x86 with 4 CPUs.
- **c, diagnostics report the whole `memory.stat` and the process's own RSS** (8c01b1cb), so
  finding 5 can be answered from the Pi after the next release.

## Open questions for the maintainer

1. Is a slower reload (b) acceptable on the Pi? It costs about 1.2 s more per evaluation on
   x86, likely several seconds on the Pi. The other option is to keep evaluating in-process
   and accept up to ~250 MB pinned after a few reloads.
2. #471's heap flags and `System.gc` after a build: with b, a build allocates little in the
   server, so is the explicit GC still worth it? Measured both ways below once decided.

## Remaining leads

- Metaspace (finding 4): an AppCDS/AOT cache would turn class metadata into file-backed pages.
  It needs a training run per release.
- Whether the GraalJS isolate's native memory goes back after the minifier closes it.
