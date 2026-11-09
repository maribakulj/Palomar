"""Sample the YCBA pairs (network): a LIDO record and YCBA's Linked Art each.

    python3 -I dev/eval/ycba/sample.py <dir> <n>      # needs <dir>/p1.json

The HumanMadeObject items of the activity page, shuffled with seed 8; the
first <n> with a LIDO record. refresh.py fetches a frozen corpus's pairs.
"""
import json, os, random, sys, time, urllib.request

OAI = "https://harvester-bl.britishart.yale.edu/oaicatmuseum/OAIHandler?verb=GetRecord&metadataPrefix=lido&identifier=oai:tms.ycba.yale.edu:"

def get(url):
    err = None
    for i in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as r: return r.read()
        except Exception as e:
            time.sleep(2 * (i + 1)); err = e
    raise err

def tms_of(la):
    return [i['content'] for i in la.get('identified_by', [])
            if any(c.get('_label') == 'System-Assigned Number' for c in i.get('classified_as', []))]

def fetch_pair(root, url, tms=None):
    """Fetch one pair into <root>/pairs/<tms>; its TMS number, or None when it has no LIDO."""
    la = json.loads(get(url))
    tms = tms or (tms_of(la) or [None])[0]
    if not tms: return None
    lido = get(OAI + tms)
    if b'lido:lido' not in lido: return None
    vis = [json.loads(get(v['id'])) for v in la.get('shows', []) if v.get('id')]
    d = os.path.join(root, 'pairs', tms); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'lido.xml'), 'wb').write(lido)
    json.dump({'object': la, 'visual': vis}, open(os.path.join(d, 'la.json'), 'w'))
    time.sleep(0.3)
    return tms

if __name__ == '__main__':
    out, n = sys.argv[1], int(sys.argv[2])
    page = json.load(open(os.path.join(out, 'p1.json')))
    objs = [i['object']['id'] for i in page['orderedItems'] if i['object'].get('type') == 'HumanMadeObject']
    random.seed(8); random.shuffle(objs)
    got = 0
    for u in objs:
        if got >= n: break
        if fetch_pair(out, u): got += 1
    print(got, "pairs")
