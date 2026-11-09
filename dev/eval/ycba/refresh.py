"""The refreshed benchmark: fetch again the pairs a manifest names (network).

    python3 -I dev/eval/ycba/refresh.py <manifest.json> <dir>

Fetches each pair's Linked Art by its object URI and its LIDO by its TMS
number into <dir>/pairs, the same pairs as the frozen corpus, not a new
sample, then the people they name (people.json). <dir> must be new or empty:
a frozen corpus is never written over. Then `verify.py <dir> <manifest.json>`
says which YCBA changed since; a pair YCBA no longer serves is missing.
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from sample import fetch_pair
from people import fetch_people

if __name__ == '__main__':
    m, root = json.load(open(sys.argv[1])), sys.argv[2]
    if os.path.isdir(root) and os.listdir(root):
        sys.exit(f"refresh.py: {root} is not empty; refresh into a new directory, never over a frozen corpus")
    got = [p['tms'] for p in m['pairs'] if fetch_pair(root, p['object'], p['tms'])]
    print(len(got), 'of', len(m['pairs']), 'pairs fetched;', fetch_people(root), 'people')
