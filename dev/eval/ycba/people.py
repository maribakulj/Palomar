"""Fetch the people and groups YCBA's productions name, with their equivalent ids.

    python3 -I dev/eval/ycba/people.py <dir>     # writes <dir>/people.json

compare.py matches makers by these ids; without them, by name only.
"""
import json, glob, os, sys, time, urllib.request

def fetch_people(root):
    urls = set()
    for f in glob.glob(os.path.join(root, 'pairs', '*', 'la.json')):
        pb = json.load(open(f))['object'].get('produced_by') or {}
        for node in [pb] + pb.get('part', []):
            for k in ('carried_out_by', 'influenced_by'):
                for a in node.get(k, []):
                    if a.get('id'): urls.add(a['id'])
    out = {}
    for u in sorted(urls):
        for i in range(3):
            try:
                with urllib.request.urlopen(u, timeout=60) as r:
                    d = json.loads(r.read()); out[u] = [e.get('id') for e in d.get('equivalent', []) if e.get('id')]; break
            except Exception:
                time.sleep(2 * (i + 1))
        time.sleep(0.2)
    json.dump(out, open(os.path.join(root, 'people.json'), 'w'))
    return len(out)

if __name__ == '__main__':
    print(fetch_people(sys.argv[1]), 'people')
