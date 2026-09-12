#!/usr/bin/env bash
# Every claim the folded design rests on, as an exit code.
#
#   ./claims.sh
#
# `nickel test` cannot carry these: a typecheck-only error EVALUATES fine, so a
# green doctest says nothing about whether the typechecker looked.
#
# NOT run here: renderB.ncl, which instantiates the fold's `r` at a recursive
# contract and CRASHES nickel (stack overflow, not a diagnostic). Run it by hand
# if you want to see it; it would abort this script.
set -uo pipefail
cd "$(dirname "$0")"

fail=0

check() { # <expected-exit> <description> <cmd...>
  local want=$1 desc=$2; shift 2
  local out; out=$("$@" 2>&1); local got=$?
  if [ "$got" -eq "$want" ]; then
    printf '  ok    %s\n' "$desc"
  else
    printf '  FAIL  %s (wanted exit %s, got %s)\n%s\n' "$desc" "$want" "$got" "$out"
    fail=1
  fi
}

echo "== the encoding works =="
check 0 "rank-2 polymorphism is accepted at all"            nickel typecheck rank2.ncl
check 0 "a 3-deep tree typechecks across two imports"       nickel typecheck use2.ncl
check 0 "every library module typechecks"                   nickel typecheck lib2/core.ncl
check 0 "  … comp"                                          nickel typecheck lib2/comp.ncl
check 0 "  … expr"                                          nickel typecheck lib2/expr.ncl
check 0 "  … query"                                         nickel typecheck lib2/query.ncl
check 0 "  … render"                                        nickel typecheck lib2/render.ncl
check 0 "the ported dashboard typechecks"                   nickel typecheck dash2.ncl
check 0 "the ported dashboard exports"                      nickel export dash2.ncl

echo "== it catches what the old design could not =="
check 1 "a wrong entity TWO levels down inside the tree"    nickel typecheck bad2.ncl

echo "== but only where the typechecker was invited in =="
check 0 "an UNGATED dashboard is green while checking nothing" nickel typecheck gate-none.ncl

echo "== the limits that shaped the design =="
check 1 "an enum variant cannot carry a rank-2 payload"     nickel typecheck enum-rank2.ncl
check 1 "\`@\` cannot produce polymorphic elements"           nickel typecheck produce-polymorphic-append.ncl
check 1 "\`map\` cannot produce polymorphic elements"         nickel typecheck produce-polymorphic-map.ncl
check 0 "… but an array literal can, and consuming is fine" nickel typecheck produce-polymorphic.ncl

echo
if [ "$fail" -eq 0 ]; then echo "all claims hold"; else echo "SOME CLAIMS FAILED"; fi
exit "$fail"
