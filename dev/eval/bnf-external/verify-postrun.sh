#!/bin/bash
# Checks test/fixtures/er-gold/bnf-external/postrun.edn against git: recomputes the SHA-256 of
# every listed file, and the tree digest of src/, from the base commit it names (git show
# <commit>:<path>), never from the working tree. Run from the repository root.
#   dev/eval/bnf-external/verify-postrun.sh
# The src/ digest is the one palomar.eval.person-reconciliation-external-post/tree-sha computes:
# the SHA-256 of the lines "<path> <sha256>\n", one per file under src/, sorted by path.
set -eu
P=test/fixtures/er-gold/bnf-external/postrun.edn
commit=$(grep -o ':base-commit "[0-9a-f]*"' "$P" | cut -d'"' -f2)
echo "base commit $commit"
fail=0
check() { # name recorded recomputed
  if [ "$2" = "$3" ]; then echo "ok    $1 $3"; else echo "DIFF  $1 recorded $2, at $commit $3"; fail=1; fi
}
[ "$(grep -o '"test/[^"]*\.clj" "[0-9a-f]*"' "$P" | wc -l)" -gt 0 ] || { echo "no file listed"; exit 1; }
src_rec=$(grep -o ':src "[0-9a-f]*"' "$P" | cut -d'"' -f2)
src_now=$(git ls-tree -r --name-only "$commit" src | LC_ALL=C sort | while read -r f; do
  printf '%s %s\n' "$f" "$(git show "$commit:$f" | sha256sum | cut -c1-64)"; done | sha256sum | cut -c1-64)
check src/ "$src_rec" "$src_now"
while read -r path sha; do
  path=${path//\"/}; sha=${sha//\"/}
  check "$path" "$sha" "$(git show "$commit:$path" | sha256sum | cut -c1-64)"
done < <(grep -o '"test/[^"]*\.clj" "[0-9a-f]*"' "$P")
if [ "$fail" = 0 ]; then echo "postrun.edn matches $commit"; else echo "postrun.edn does NOT match $commit"; exit 1; fi
