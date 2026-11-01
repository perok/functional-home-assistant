#!/usr/bin/env bash
# Assert what `nickel typecheck` does and does not catch.
#
#   ./typecheck-claims.sh
#
# These claims cannot be `nickel test` doctests: a typecheck-only error (no type
# alias, no recursive type) EVALUATES fine, so a doctest would pass while the
# claim is false. Each fixture in typecheck/ declares its expected outcome in
# its first line; this script checks the exit code matches.
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
    printf '%-34s want %s, got %s\n' "$(basename "$file")" "$want" "$got"
    fail=1
    return
  fi
  if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF "$needle"; then
    printf '%-34s %s, but message lacked: %s\n' "$(basename "$file")" "$got" "$needle"
    fail=1
    return
  fi
  printf '%-34s %s\n' "$(basename "$file")" "$got"
}

echo "== static typing works, and it crosses module boundaries"
expect PASS typecheck/import-carries-its-type.ncl
expect FAIL typecheck/import-catches-a-bad-call.ncl "lacks \`entity_id\`"
expect FAIL typecheck/static-call-site.ncl          "These types are not compatible"
expect FAIL typecheck/adt-exhaustive.ncl            "missing row \`Axis\`"
expect PASS typecheck/adt-exhaustive-ok.ncl
expect PASS typecheck/dyn-bridge.ncl
expect FAIL typecheck/dashboard-capability.ncl      "lacks \`colourTemp\`"
expect PASS typecheck/dashboard-capability-ok.ncl

echo
echo "== the real limits"
expect FAIL typecheck/no-alias.ncl            "Static types and contracts are not compatible"
expect FAIL typecheck/no-recursive-type.ncl   "unbound identifier \`Node\`"
expect FAIL typecheck/merge-is-untyped.ncl
expect PASS typecheck/merge-is-untyped-workaround.ncl
expect FAIL typecheck/unannotated-import-is-dyn.ncl "Found an expression of type \`Dyn\`"
expect FAIL typecheck/module-annotation-must-be-outermost.ncl "Found an expression of type \`Dyn\`"
expect PASS typecheck/module-annotation-outermost-ok.ncl
expect FAIL typecheck/dyn-needs-contract-application.ncl

echo
echo "== and what a CONTRACT-annotated library would cost"
expect PASS typecheck/lib-typechecks-clean.ncl

echo
if [ $fail -eq 0 ]; then
  echo "all claims hold"
else
  echo "SOME CLAIMS NO LONGER HOLD -- the language changed, or the claim was wrong"
fi
exit $fail
