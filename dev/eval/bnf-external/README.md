# The external test's fetch scripts (lot O5)

The scripts that fetched and shaped the fixtures of
`test/fixtures/er-gold/bnf-external/`
(`docs/eval/person-reconciliation-external.md`).

- `fetch.sh` — the SRU requests. Reconstructed into one file after the run
  from the commands that ran; the parameters are unchanged.
- `trim.py` — N3's trim (leader, control fields, 1XX, 245, 260, 7XX); checks
  well-formed XML before and after.
- `assemble.py` — concatenates the authority responses' `<srw:record>`
  elements, in id order, under one wrapper.
- `log.py` — writes `fetch-log.edn` from the saved response headers.
- `ids.txt`, `pseudo-ids.txt` — the authority ids queried.
- `verify-postrun.sh` — checks `postrun.edn`, the record of the code the
  run used: it recomputes the SHA-256 of `src/` (the tree digest of
  `post/tree-sha`) and of each listed namespace from `git show
  <base-commit>:<path>`, and compares. This is how the record is checked;
  the test suite does not compare it with the working tree, which later
  lots change. Run it from the repository root.

They were run with `python3 -I`, from this script directory, on a download
directory outside the repository. Nothing was ever executed from the data
directory. On 2026-11-12, re-running `trim.py`, `assemble.py` and `log.py`
on the raw responses gave the committed fixtures byte for byte. The raw
responses are not committed (2.8 MB); their SHA-256 are in
`raw-sha256.edn`, and their headers in `raw-headers/`.

`log.py` takes the download directory and the output path, and reads the id
lists from `../scripts-o5/` next to the download directory, as in the
session: adjust the path if you re-run it.
