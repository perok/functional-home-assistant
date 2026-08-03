# `pkl project resolve <dir>` ignores the project's `evaluatorSettings` (`http.rewrites`, `moduleCacheDir`, `allowedResources`); `pkl project resolve .` honors them

## Summary

A project's own `evaluatorSettings` are applied to `project resolve` **only when
the CLI's working directory is inside the project**. When the project is passed
as a *directory argument* from elsewhere — `pkl project resolve path/to/project`
— its `http.rewrites` are silently ignored (the resolver dials the original
host literally) and its `moduleCacheDir` is ignored too (the default
`~/.pkl/cache` is used instead).

This makes the documented mirror-server setup for air-gapped / self-hosted
package registries
(<https://pkl-lang.org/blog/using-packages-in-air-gapped-environments.html#mirror-server>)
break under any tool that invokes `pkl project resolve` with directory
arguments — notably the **IntelliJ plugin**, which syncs a workspace by running
`pkl project resolve <dir> [<dir>…]` from the content root, and therefore fails
with `Error connecting to host …` even though the project declares a working
rewrite.

- Pkl version: **Pkl 0.31.0 (Linux, native)** (also reproduced with pkl-core 0.31.1 as the comparison library)
- OS: Linux x86_64

## Reproduction

A minimal project whose only dependency lives on a placeholder host, with a
rewrite pointing at a mirror (a dead port here, so the *rewrite application
itself* is visible in the error message):

```
mkdir -p demo && cat > demo/PklProject <<'EOF'
amends "pkl:Project"

evaluatorSettings {
  http {
    rewrites {
      ["https://example.invalid/"] = "http://127.0.0.1:9999/mirror/"
    }
  }
}

dependencies {
  ["foo"] { uri = "package://example.invalid/foo@1.0.0" }
}
EOF
```

**A) cwd inside the project — the rewrite IS applied** (the error names the
rewritten target, which is the expected behavior; with a live mirror this
resolves successfully):

```
$ cd demo && pkl project resolve .
Exception when making request `GET https://example.invalid/foo@1.0.0`:
Error connecting to host `127.0.0.1`. (request was rewritten: https://example.invalid/foo@1.0.0 -> http://127.0.0.1:9999/mirror/foo@1.0.0)
```

**B) project as a directory argument — the rewrite is silently ignored** (the
resolver dials the placeholder host literally; no "request was rewritten"
note):

```
$ pkl project resolve demo
Exception when making request `GET https://example.invalid/foo@1.0.0`:
Error connecting to host `example.invalid`.
```

The same asymmetry applies to `moduleCacheDir`: with a warm project-declared
cache, invocation A resolves fully offline, invocation B re-fetches into
`~/.pkl/cache`.

Both the `--http-rewrite` CLI flag and OS-user-level `~/.pkl/settings.pkl`
rewrites ARE applied in both invocation modes — only the *project's own*
`evaluatorSettings` are dropped in dir-argument mode. Also verified: it makes
no difference whether the settings are literal in the `PklProject`, come
through an `amends` chain, or are derived from `read()`-ing a sibling file —
only the working directory matters.

## Expected

`pkl project resolve <dir>` resolves each project *with that project's own
evaluator settings*, identically to running `pkl project resolve .` inside it.
(Or, if `evaluatorSettings` are deliberately scoped to `eval`/`test`/`repl`
only — as the `Project.evaluatorSettings` docs suggest — then invocation A
should not apply them either, and the current cwd-dependent behavior is the
inconsistency.)

## Actual

Whether the project's `http.rewrites`/`moduleCacheDir` are honored depends on
the CLI's working directory, not on which project is being resolved.

## Impact

- The IntelliJ plugin's project sync (`pkl project resolve <dir>…` from the
  content root) cannot resolve dependencies served through a project-declared
  mirror rewrite; the failure surfaces as
  ``Error connecting to host `example.invalid`.`` with no hint that a rewrite
  was declared but skipped.
- Workaround: duplicate the rewrite into the OS-user-level
  `~/.pkl/settings.pkl` (or pass `--http-rewrite`), which is honored in both
  modes — but that is per-user global state duplicating per-project
  configuration.

### 0.32 makes the settings.pkl workaround insufficient

`evaluatorSettings.allowedResources` is skipped in dir-argument mode for the
same reason, and from **0.32** that is no longer cosmetic: the resource
allowlist is now checked against the **rewritten** url as well as the original.
A mirror served over plain http (a LAN host with no certificate) is therefore
refused, because the defaults admit `https:` and never `http:`.

The settings.pkl workaround does not cover it — `pkl:settings` has **no**
`allowedResources` property (`Cannot find property 'allowedResources' in module
'pkl.settings'`), so the rewrite can be supplied globally but the matching
allowance cannot. Verified on **Pkl 0.32.1 (Linux, native)**, with the rewrite
in `settings.pkl` and the allowlist in the project:

```
$ cd ws && pkl project resolve                   # honors the project
Exception when making request `GET https://fh.invalid/x@1.0.0`:
Error connecting to host `127.0.0.1`. (request was rewritten: … -> http://127.0.0.1:9/x@1.0.0)

$ pkl project resolve ws --settings settings.pkl # dir-arg: project ignored
Refusing to read resource `http://127.0.0.1:9/x@1.0.0` because it does not
match any entry in the resource allowlist (`--allowed-resources`).
```

The only remaining lever is the `--allowed-resources` flag, which **replaces**
the defaults rather than extending them, and must list the pre-rewrite scheme
as well as the post-rewrite origin (both are checked):

```
pkl project resolve ws --settings settings.pkl \
  --allowed-resources "prop:,env:,file:,modulepath:,package:,projectpackage:,https:,^http://127[.]0[.]0[.]1:9/"
```

That is not reachable from the IntelliJ plugin's sync UI, so on 0.32 a workspace
whose mirror is plain http cannot be synced from the IDE — while the same
project resolves fine from inside its own directory.

Our workaround is `scripts/pkl-fh`: the plugin is configured with a path to a
Pkl executable, so that path can be a wrapper which forwards every argument and
injects `--http-rewrite` + `--allowed-resources`, read from the workspace's own
`.fh/machine.json`. It only injects for the subcommands that accept the flags,
so `--version`/`format` still pass through. That works, but it is a workaround
for configuration the project already declares and the resolver already loaded.
