"""The frozen benchmark, offline: are the pairs in <dir> the corpus the manifest names?

    python3 -I dev/eval/ycba/verify.py <dir> <manifest.json>

Exits 0 when every pair the manifest names is there with the same bytes, and
so are people.json (the ids makers are matched by) and the activity page the
sample was drawn from, when the manifest names them; 1 otherwise, listing
each one missing or changed. A comparison of a corpus that does not verify is
not the benchmark: it is a newer corpus (refresh.py).
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from freeze import sha

def check(root, manifest):
    bad = []
    for p in manifest['pairs']:
        d = os.path.join(root, 'pairs', p['tms'])
        for f, k in (('lido.xml', 'lido_sha256'), ('la.json', 'la_sha256')):
            path = os.path.join(d, f)
            if not os.path.exists(path): bad.append((p['tms'], f, 'missing'))
            elif sha(path) != p[k]: bad.append((p['tms'], f, 'changed'))
    return bad

def check_context(root, manifest):
    """people.json and the activity page: missing or changed, as (file, why)."""
    bad = []
    for f, k in (('people.json', 'people_sha256'), ('p1.json', None)):
        want = manifest.get(k) if k else manifest.get('source', {}).get('activity_page_sha256')
        if not want: continue
        path = os.path.join(root, f)
        if not os.path.exists(path): bad.append((f, 'missing'))
        elif sha(path) != want: bad.append((f, 'changed'))
    return bad

if __name__ == '__main__':
    root, m = sys.argv[1], json.load(open(sys.argv[2]))
    bad, ctx = check(root, m), check_context(root, m)
    for tms, f, why in bad: print(tms, f, why)
    for f, why in ctx: print(f, why)
    print(f"{len(m['pairs']) - len({b[0] for b in bad})} of {len(m['pairs'])} pairs as frozen"
          + (f"; {', '.join(f for f, _ in ctx)} not as frozen" if ctx else ''))
    sys.exit(1 if bad or ctx else 0)
