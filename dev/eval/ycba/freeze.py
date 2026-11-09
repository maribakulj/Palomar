"""Freeze the YCBA benchmark: a corpus manifest naming each pair and its bytes.

    python3 -I dev/eval/ycba/freeze.py <dir> <manifest.json> [palomar-commit]

The manifest names what was compared, not the data: each pair's TMS number,
its Linked Art object URI, when it was fetched (the file's time), and the
SHA-256 of its LIDO and of its Linked Art. YCBA's data is never committed
(Open Data Terms of Use); its identifiers and digests are, so a run can be
checked offline against the corpus it claims (verify.py) and the corpus
fetched again (refresh.py) to see what YCBA changed.
"""
import datetime, glob, hashlib, json, os, sys

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(65536), b''): h.update(b)
    return h.hexdigest()

def when(path):
    return datetime.datetime.fromtimestamp(os.path.getmtime(path), datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

def pair_entry(d):
    la = os.path.join(d, 'la.json'); lido = os.path.join(d, 'lido.xml')
    obj = json.load(open(la))['object'].get('id')
    return {'tms': os.path.basename(d), 'object': obj, 'fetched_at': when(lido),
            'lido_sha256': sha(lido), 'la_sha256': sha(la)}

if __name__ == '__main__':
    root, out = sys.argv[1], sys.argv[2]
    commit = sys.argv[3] if len(sys.argv) > 3 else None
    pairs = [pair_entry(d) for d in sorted(glob.glob(os.path.join(root, 'pairs', '*')))
             if os.path.exists(os.path.join(d, 'la.json')) and os.path.exists(os.path.join(d, 'lido.xml'))]
    people = os.path.join(root, 'people.json'); page = os.path.join(root, 'p1.json')
    m = {'benchmark': 'ycba', 'manifest_version': 1,
         'source': {'activity_page': 'https://ycba-lux.s3.amazonaws.com/activity_stream/page1.json',
                    'activity_page_sha256': sha(page) if os.path.exists(page) else None,
                    'sample': 'HumanMadeObject items of the page, shuffled with seed 8, the first with a LIDO record',
                    'seed': 8, 'lido': 'https://harvester-bl.britishart.yale.edu/oaicatmuseum/OAIHandler (GetRecord, lido)'},
         'licence': 'Data Source: Yale Center for British Art, under its Open Data and Data Services Terms of Use; the data is not redistributed',
         'palomar_commit': commit,
         'people_sha256': sha(people) if os.path.exists(people) else None,
         'pairs': pairs}
    with open(out, 'w') as f: json.dump(m, f, indent=1, sort_keys=True); f.write('\n')
    print(len(pairs), 'pairs frozen')
