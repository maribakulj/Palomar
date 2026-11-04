"""Palomar's Linked Art against the Yale Center for British Art's own, pair by pair.

    python3 -I dev/eval/ycba/compare.py <dir> [palomar file name …]

Each name is a Palomar output beside each pair (convert.clj); one column each.
Actors are matched by identity when people.json (people.py) is there: a YCBA
person is its own URI and its equivalents (ULAN, Wikidata, LoC…), and matches
the Palomar actor written with one of them. Otherwise, and for an actor
Palomar names by no authority, by name, after normalisation (accents, case,
dates and qualifiers stripped).
"""
import json, glob, os, re, sys, collections, unicodedata

def norm(s):
    s = unicodedata.normalize('NFKD', s or '').encode('ascii', 'ignore').decode().lower()
    s = re.split(r',\s*(?:\d|ca\.|active|born|died|fl\.)', s)[0]
    s = re.sub(r'\b(?:follower|circle|workshop|studio|school|manner|after|formerly|attributed)\s+(?:of|to)?\b', '', s)
    return re.sub(r'[^a-z ]', '', s).strip()

PEOPLE = {}

def canon(u):
    u = (u or '').strip().lower().replace('https://', 'http://').rstrip('/')
    return u.replace('www.wikidata.org/wiki/', 'www.wikidata.org/entity/')

def keys_ycba(a):
    ks = {canon(a.get('id'))} | {canon(e) for e in PEOPLE.get(a.get('id'), [])}
    return ks | {'name:' + norm(a.get('_label', ''))}

def keys_palomar(a):
    i = a.get('id', '')
    return {canon(i)} if i and not i.startswith('urn:') else {'name:' + norm(a.get('_label', ''))}

def agents(pb, key, want=None):
    """The agents of `key` in a production and its parts (only parts classified `want`, if given)."""
    out = []
    if not pb: return out
    if want is None: out += pb.get(key, [])
    for p in pb.get('part', []):
        if want is None or any(c.get('_label') == want for c in p.get('classified_as', [])): out += p.get(key, [])
    return out

def matched(ys, ps):
    """How many of the agents `ys` (YCBA) one of `ps` (Palomar) is."""
    pk = [keys_palomar(a) for a in ps]
    return sum(1 for y in ys if any(keys_ycba(y) & k for k in pk))


def subjects(visuals, key):
    return {norm(s.get('_label', '')) for v in visuals for s in v.get(key, [])}

def load_palomar(path):
    lines = [json.loads(l) for l in open(path) if l.strip()]
    po = next((l for l in lines if l.get('type') == 'HumanMadeObject'), None)
    pv = [l for l in lines if l.get('type') == 'VisualItem']
    return po, pv

def compare(root, name):
    c = collections.Counter()
    for d in sorted(glob.glob(os.path.join(root, 'pairs', '*'))):
        p = os.path.join(d, name)
        if not os.path.exists(p): continue
        y = json.load(open(os.path.join(d, 'la.json'))); yo = y['object']
        try: po, pv = load_palomar(p)
        except Exception: po, pv = None, []
        c['records'] += 1
        if not po: c['no object written'] += 1; continue
        ypb, ppb = yo.get('produced_by') or {}, po.get('produced_by') or {}
        ymk = agents(ypb, 'carried_out_by', 'Primary Artist') or ypb.get('carried_out_by', [])
        pmk = ppb.get('carried_out_by', [])
        c['makers: YCBA (its Primary Artist part)'] += len(ymk); c['makers: Palomar (carried_out_by)'] += len(pmk)
        c['makers: YCBA found in Palomar'] += matched(ymk, pmk)
        c['makers: Palomar, not YCBA\'s Primary Artist'] += sum(1 for p in pmk if not any(keys_ycba(y) & keys_palomar(p) for y in ymk))
        yall = agents(ypb, 'carried_out_by')
        c['makers: Palomar, in no part of YCBA\'s production'] += sum(1 for p in pmk if not any(keys_ycba(y) & keys_palomar(p) for y in yall))
        yin, pin = agents(ypb, 'influenced_by'), agents(ppb, 'influenced_by')
        c['influences: YCBA'] += len(yin); c['influences: Palomar'] += len(pin); c['influences: YCBA found in Palomar'] += matched(yin, pin)
        yr, ya = subjects(y['visual'], 'represents'), subjects(y['visual'], 'about')
        pr, pa = subjects(pv, 'represents'), subjects(pv, 'about')
        c['subjects represented (distinct labels): YCBA'] += len(yr); c['subjects represented (distinct labels): Palomar'] += len(pr)
        c['subjects about (distinct labels): YCBA'] += len(ya); c['subjects about (distinct labels): Palomar'] += len(pa)
        c['subjects: same label, any relation'] += len((yr | ya) & (pr | pa))
        yc = {g.get('id') for g in yo.get('current_owner', []) + yo.get('current_custodian', [])}
        pc = {g.get('id') for g in po.get('current_owner', []) + po.get('current_custodian', [])}
        c['custodian given: YCBA'] += bool(yc); c['custodian given: Palomar'] += bool(pc)
        yt = {t.get('id') for t in yo.get('classified_as', [])}; pt = {t.get('id') for t in po.get('classified_as', [])}
        c['types: YCBA'] += len(yt); c['types: Palomar'] += len(pt); c['types: both'] += len(yt & pt)
        ym_ = {t.get('id') for t in yo.get('made_of', [])}; pm_ = {t.get('id') for t in po.get('made_of', [])}
        c['materials: YCBA'] += len(ym_); c['materials: Palomar'] += len(pm_); c['materials: both'] += len(ym_ & pm_)
        c['dimensions: YCBA'] += len(yo.get('dimension', [])); c['dimensions: Palomar'] += len(po.get('dimension', []))
    return c

if __name__ == '__main__':
    root, names = sys.argv[1], (sys.argv[2:] or ['palomar-la.json'])
    if os.path.exists(os.path.join(root, 'people.json')): PEOPLE.update(json.load(open(os.path.join(root, 'people.json'))))
    cols = [compare(root, n) for n in names]
    keys = list(dict.fromkeys(k for c in cols for k in c))
    print('| | ' + ' | '.join(names) + ' |'); print('|---|' + '---|' * len(names))
    for k in keys: print(f'| {k} | ' + ' | '.join(str(c.get(k, 0)) for c in cols) + ' |')
