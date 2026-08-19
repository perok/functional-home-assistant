#!/usr/bin/env bash
# Assert what `nickel typecheck` does and does not catch.
#
#   ./typecheck-claims.sh
#
# These claims cannot be `nickel test` doctests: a typecheck-only error (no type
# alias, import-is-Dyn) EVALUATES fine, so a doctest would pass while the claim
# is false. Each fixture in typecheck/ declares its expected outcome in its
# first line; this script checks the exit code matches.
set -u
cd "$(dirname "$0")" || exit 1

fail=0

expect() { # expect <FAIL|PASS> <file> <substring-of-error>
  local want=$1 file=$2 needle=${3-}
  local out rc
  out=$(nickel typecheck "$file" 2>&1)
  rc=$?
  local got=PASS
  [ $rc -ne 0 ] && got=FAIL

  if [ "$got" != "$want" ]; then
    printf '%-28s want %s, got %s\n' "$(basename "$file")" "$want" "$got"
    fail=1
    return
  fi
  if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF "$needle"; then
    printf '%-28s %s, but message lacked: %s\n' "$(basename "$file")" "$got" "$needle"
    fail=1
    return
  fi
  printf '%-28s %s\n' "$(basename "$file")" "$got"
}

echo "== what static typing DOES catch"
expect FAIL typecheck/adt-exhaustive.ncl  "missing row \`Axis\`"
expect PASS typecheck/adt-exhaustive-ok.ncl
expect FAIL typecheck/static-call-site.ncl "These types are not compatible"

echo
echo "== why lib/ cannot use it"
expect FAIL typecheck/no-alias.ncl        "Static types and contracts are not compatible"
expect FAIL typecheck/import-is-dyn.ncl   "Found an expression of type \`Dyn\`"
expect FAIL typecheck/merge-is-untyped.ncl

echo
echo "== and so, on the library we actually have"
expect PASS typecheck/lib-typechecks-clean.ncl

echo
if [ $fail -eq 0 ]; then
  echo "all claims hold"
else
  echo "SOME CLAIMS NO LONGER HOLD -- the language changed, or the claim was wrong"
fi
exit $fail
