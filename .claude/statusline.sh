#!/usr/bin/env bash
# Claude Code status line — https://code.claude.com/docs/en/statusline
#
# Its first job is one question, answered without reading: is this session
# inside the agentbox, or on the host with your real keys, your real gh
# account and your whole home directory? Those two look identical in a
# terminal otherwise, and the box exists precisely so they are not the same
# thing to be wrong about.
#
# It deliberately says "contained", not "safe". Egress from the box is
# unrestricted and every mount is readable from inside it, so the chips list
# what was actually handed in rather than implying a boundary the README is
# careful not to claim (`devbox readme`, "What the box can reach").
set -uo pipefail

json=$(cat)

# One jq call, not seven: this runs on every assistant message.
#
# Joined on US (\037) passed as --arg, not @tsv: tab is IFS *whitespace*, so
# bash collapses a run of them, and an absent worktree then shifts every later
# field left — the context bar silently starts rendering the cost.
IFS=$'\037' read -r MODEL DIR WORKTREE PCT COST MINS <<EOF
$(printf '%s' "$json" | jq -r --arg s $'\037' '[
    (.model.display_name // "?"),
    (.workspace.current_dir // .cwd // ""),
    (.workspace.git_worktree // .worktree.branch // ""),
    ((.context_window.used_percentage // -1) | floor | tostring),
    ((.cost.total_cost_usd // 0) * 100 | round / 100 | tostring),
    (((.cost.total_duration_ms // 0) / 60000) | floor | tostring)
  ] | join($s)' 2>/dev/null)
EOF

: "${MODEL:=?}" "${PCT:=-1}" "${COST:=0}" "${MINS:=0}"
COST=$(printf '%.2f' "$COST" 2>/dev/null) || COST="?"

R=$'\033[0m' B=$'\033[1m' DIM=$'\033[2m'
RED=$'\033[31m' GREEN=$'\033[32m' YELLOW=$'\033[33m' CYAN=$'\033[36m'

# --- where am I running --------------------------------------------------
# AGENTBOX=1 is baked into the image's Env (flake.nix), so it is a property of
# the filesystem this is executing on, not something a stray host export can
# fake the way an opt-in AGENTBOX_* wrapper variable could.
chips=()
if [ "${AGENTBOX:-0}" = "1" ]; then
  badge="${GREEN}${B}▣ agentbox${R}"
  # Each chip is evidence found INSIDE the box, never a host-side AGENTBOX_*
  # variable: the box does not see those, and reading them would report the
  # opt-in that was asked for rather than what actually arrived.
  [ -n "${SSH_AUTH_SOCK:-}" ] && chips+=("ssh")
  [ -d "$HOME/.ssh" ] && chips+=("ssh-keys")
  [ -e /run/gh-token ] && chips+=("gh")
  [ -n "${FH_PLAYWRIGHT_WS:-}" ] && chips+=("browser")
  if [ -S "${GNUPGHOME:-$HOME/.gnupg}/S.gpg-agent" ]; then
    chips+=("gpg")
  else
    chips+=("${YELLOW}unsigned${R}")
  fi
  [ -n "${AGENTBOX_DASHBOARD_URL:-}" ] && chips+=(":${AGENTBOX_DASHBOARD_URL##*:}")
else
  badge="${RED}${B}△ host${R}${DIM} not contained${R}"
fi

# --- git -----------------------------------------------------------------
cd "${DIR:-.}" 2>/dev/null || true

git_bits=""
if branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null) ||
  branch=$(git rev-parse --short HEAD 2>/dev/null); then
  git_bits="${CYAN}⑂ ${branch}${R}"

  # -uno: scanning untracked files walks target/ and node_modules, which is the
  # slow half of `git status` and shows nothing worth a chip.
  dirty=$(git status --porcelain -uno 2>/dev/null | wc -l)
  [ "$dirty" -gt 0 ] && git_bits+=" ${YELLOW}●${dirty}${R}"

  if ab=$(git rev-list --left-right --count '@{upstream}...HEAD' 2>/dev/null); then
    behind=${ab%%[[:space:]]*} ahead=${ab##*[[:space:]]}
    [ "$ahead" != 0 ] && git_bits+=" ↑$ahead"
    [ "$behind" != 0 ] && git_bits+=" ↓$behind"
  else
    git_bits+="${DIM} no upstream${R}"
  fi

  # workspace.git_worktree is absent in the main working tree, so empty is the
  # answer here, not a gap to fill in.
  [ -n "${WORKTREE:-}" ] && git_bits+=" ${DIM}│${R} ⌂ ${WORKTREE}"
fi

# --- context -------------------------------------------------------------
# used_percentage is null until the first API response, and again after a
# /compact until the next one — hence -1 rather than a misleading 0%.
if [ "$PCT" -ge 0 ] 2>/dev/null; then
  filled=$((PCT / 10))
  if [ "$PCT" -ge 90 ]; then
    bar_color=$RED
  elif [ "$PCT" -ge 70 ]; then
    bar_color=$YELLOW
  else
    bar_color=$GREEN
  fi
  bar=""
  for i in 0 1 2 3 4 5 6 7 8 9; do
    if [ "$i" -lt "$filled" ]; then bar+="▓"; else bar+="░"; fi
  done
  ctx="${bar_color}${bar}${R} ${PCT}%"
else
  ctx="${DIM}░░░░░░░░░░ --%${R}"
fi

sep="${DIM} │ ${R}"
line1="$badge"
if [ ${#chips[@]} -gt 0 ]; then
  line1+="${DIM} · ${R}$(
    IFS=' '
    printf '%s' "${chips[*]}"
  )"
fi
[ -n "$git_bits" ] && line1+="${sep}${git_bits}"

# %s, not %b: the colours above are already real escape bytes from $'…', and
# %b would additionally expand any backslash that turns up in a branch name.
printf '%s\n' "$line1"
printf '%s\n' "${B}${MODEL}${R}${sep}${ctx}${sep}\$${COST}${sep}${MINS}m"
