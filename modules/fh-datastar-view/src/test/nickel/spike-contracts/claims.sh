#!/usr/bin/env bash
# Every claim the contract design rests on, as an exit code.
#
#   ./claims.sh
#
# Read alongside ../spike-rank2/claims.sh: the two scripts assert the SAME
# properties and disagree about half of them, which is the comparison.
#
# The editor claims shell out to ../lsp-probe.py --diagnostics, which exits 1 if
# nls published anything and 0 if it stayed silent. They take ~10s each because
# proving "nothing arrived" means waiting.
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

echo "== the recursion the static design cannot write =="
check 0 "a self-referencing tree contract typechecks"       nickel typecheck lib3/core.ncl
check 0 "  … a recursive ADT with recursive payloads"       nickel typecheck lib3/expr.ncl
check 0 "  … components"                                    nickel typecheck lib3/comp.ncl
check 0 "  … the query"                                     nickel typecheck lib3/query.ncl
check 0 "the ported dashboard exports"                      nickel export dash3.ncl
check 0 "a query case may render a SUBTREE, not just a leaf" nickel export case-renders-subtree.ncl

echo "== and what that costs: nothing is checked before eval =="
check 0 "a wrong entity two levels down: typecheck is GREEN" nickel typecheck bad3.ncl
check 1 "  … and eval catches it, naming both sites"         nickel export bad3.ncl
check 0 "a missing capability: typecheck is GREEN"           nickel typecheck capability.ncl
check 1 "  … eval catches it"                                nickel export capability.ncl

echo "== the editor, measured rather than assumed =="
check 1 "nls underlines the static design's deep error"      ../lsp-probe.py --diagnostics spike-rank2/bad2.ncl
check 0 "nls is SILENT on the identical contract error"      ../lsp-probe.py --diagnostics spike-contracts/bad3.ncl

echo "== why there is no middle design =="
check 1 "a static signature may not mention a tree contract" nickel typecheck hybrid-type-mentions-contract.ncl
check 0 "a Dyn-typed tree works -- with \`| Dyn\` per child"   nickel typecheck hybrid-dyn-tree.ncl
check 1 "  … and not without them: no implicit upcast"       nickel typecheck hybrid-dyn-tree-without.ncl

echo
if [ "$fail" -eq 0 ]; then echo "all claims hold"; else echo "SOME CLAIMS FAILED"; fi
exit "$fail"
