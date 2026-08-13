#!/usr/bin/env bash
# PreToolUse guard for Bash: nudges toward the Edit/Write tools instead of
# shell-based file mutation (sed -i, perl -i, python file writes, cat/tee
# redirection). Those bypass Edit's read-before-write and unique-match
# checks and are error-prone (heavy escaping, silent overwrites). Scratch
# and /tmp paths are exempt — this is about tracked source, not throwaway
# files.
#
# Escape hatch: prefix the command with `ALLOW_SHELL_EDIT=1 ` for genuine
# bulk/multi-file mechanical edits (renames, repo-wide find+sed), where
# Read+Edit per file would be far more expensive than one shell command.
# Documented in CLAUDE.md — use deliberately, not as a way around a single
# edit that Edit/Write would handle fine.
set -euo pipefail

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')

if [[ -z "$cmd" ]]; then
  exit 0
fi

if echo "$cmd" | grep -q 'ALLOW_SHELL_EDIT=1'; then
  exit 0
fi

if echo "$cmd" | grep -Eq '/tmp/|scratchpad'; then
  exit 0
fi

if echo "$cmd" | grep -Eq \
  -e 'sed[[:space:]]+-i' \
  -e 'perl[[:space:]]+-[a-zA-Z]*i' \
  -e "python3?[[:space:]].*open\([^)]*['\"][wa]['\"]" \
  -e 'python3?[[:space:]].*\.write\(' \
  -e '(^|[;&|])[[:space:]]*cat[[:space:]][^|;&]*>' \
  -e '(^|[;&|])[[:space:]]*tee[[:space:]]'; then
  echo "Blocked: this looks like a shell-based file edit (sed -i/perl -i/python write/cat>/tee). Use the Edit or Write tool instead — it's more transparent and catches stale-file and ambiguous-match errors that shell edits don't. For a genuine bulk/multi-file mechanical edit, prefix the command with ALLOW_SHELL_EDIT=1 (see CLAUDE.md)." >&2
  exit 2
fi

exit 0
