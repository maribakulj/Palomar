#!/bin/bash
# The fetch of the external test (lot O5), as it was run on 2026-11-11 (23:23 to 23:26 UTC).
# Reconstructed into one file after the run from the commands typed in the session; the commands
# and their parameters are those that ran. It was never run from the data directory: OUT is a
# new, empty directory outside the repository, and the scripts live here.
#
#   dev/eval/bnf-external/fetch.sh OUT     # 1. the five pages, in selection order
#   (ids.txt: the $3 ids of the kept records' person headings, listed with Palomar's own reader)
#   dev/eval/bnf-external/fetch.sh OUT aut ids.txt
#   (pseudo-ids.txt: the 331 targets of the served authorities, listed the same way)
#   dev/eval/bnf-external/fetch.sh OUT pseudo pseudo-ids.txt
set -eu
OUT=$1; KIND=${2:-bib}; IDS=${3:-}
SRU=https://catalogue.bnf.fr/api/SRU
mkdir -p "$OUT/$KIND"; cd "$OUT/$KIND"
if [ "$KIND" = bib ]; then
  for n in "voltaire" "musset" "christine de pizan" "mardrus" "rabelais"; do
    f=$(echo "$n" | tr ' ' '-')
    q="(bib.author all \"$n\") and (bib.recordtype any \"mon\")"
    curl -sS -G -D "$f.headers" -o "$f.xml" --max-time 120 "$SRU" \
      --data-urlencode "version=1.2" --data-urlencode "operation=searchRetrieve" \
      --data-urlencode "query=$q" --data-urlencode "recordSchema=intermarcxchange" \
      --data-urlencode "maximumRecords=100" --data-urlencode "startRecord=1"
    echo "$n $(grep -m1 -o '<srw:numberOfRecords>[0-9]*' "$f.xml")"
    sleep 1
  done
else
  for id in $(cat "$IDS"); do
    curl -sS -G -D "$id.headers" -o "$id.xml" --max-time 60 "$SRU" \
      --data-urlencode "version=1.2" --data-urlencode "operation=searchRetrieve" \
      --data-urlencode "query=aut.recordid any \"$id\"" --data-urlencode "recordSchema=intermarcxchange" \
      --data-urlencode "maximumRecords=10" --data-urlencode "startRecord=1"
  done
fi
