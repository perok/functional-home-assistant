#!/usr/bin/env bash
# PreToolUse guard for Edit|Write: blocks two known-bad edit targets.
#   1. Any path under a folder literally named "generated" — build output,
#      wiped by doCodegen; edits there are silently lost.
#   2. The `version:` line in home-addon/config.yaml — that line is the
#      release trigger (see CLAUDE.md), never an incidental edit.
set -euo pipefail

input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

if [[ -z "$file_path" ]]; then
  exit 0
fi

if [[ "$file_path" =~ (^|/)generated(/|$) ]]; then
  echo "Blocked: '$file_path' is inside a folder named 'generated' — treated as build output (doCodegen wipes ha.generated on every run). Edit the generator/source instead, or tell the user if this specific directory is meant to be hand-edited." >&2
  exit 2
fi

if [[ "$file_path" == *home-addon/config.yaml ]]; then
  old=$(echo "$input" | jq -r '.tool_input.old_string // empty')
  new=$(echo "$input" | jq -r '.tool_input.new_string // empty')
  content=$(echo "$input" | jq -r '.tool_input.content // empty')
  combined=$(printf '%s\n%s\n%s' "$old" "$new" "$content")
  if echo "$combined" | grep -Eq '^version:'; then
    echo "Blocked: home-addon/config.yaml's version: line is the release trigger, bumped only by the maintainer (see CLAUDE.md). Ask the user before touching it." >&2
    exit 2
  fi
fi

exit 0
