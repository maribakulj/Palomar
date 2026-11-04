import json, glob, os, re, sys, collections, unicodedata
root = sys.argv[1]
def norm(s):
    s = unicodedata.normalize('NFKD', s or '').encode('ascii','ignore').decode().lower()
    s = re.split(r',\s*(?:\d|ca\.|active|born|died|fl\.)', s)[0]
    return re.sub(r'[^a-z ]', '', s).strip()
def prod_actors(pb):
    top, parts = set(), collections.defaultdict(set)
    if not pb: return top, parts
    for a in pb.get('carried_out_by', []): top.add(norm(a.get('_label','')))
    for p in pb.get('part', []):
        role = ' / '.join(c.get('_label','') for c in p.get('classified_as', [])) or p.get('_label','?')
        for a in p.get('carried_out_by', []): parts[role].add(norm(a.get('_label','')))
    return top, parts
def subjects(vis_list, key):
    out = set()
    for v in vis_list:
        for s in v.get(key, []): out.add(norm(s.get('_label','')))
    return out
c = collections.Counter(); ex = collections.defaultdict(list)
for d in sorted(glob.glob(os.path.join(root, 'pairs', '*'))):
    tid = os.path.basename(d)
    y = json.load(open(os.path.join(d, 'la.json'))); yo = y['object']
    lines = [json.loads(l) for l in open(os.path.join(d, 'palomar-la.json')) if l.strip()]
    po = next((l for l in lines if l.get('type') == 'HumanMadeObject'), None)
    pv = [l for l in lines if l.get('type') == 'VisualItem']
    if po: pv += [v for v in po.get('shows', []) if isinstance(v, dict) and v.get('type') == 'VisualItem']
    c['records'] += 1
    if not po: c['palomar-no-object'] += 1; continue
    # creators: YCBA Primary Artist part vs Palomar top-level carried_out_by
    ytop, yparts = prod_actors(yo.get('produced_by'))
    ptop, pparts = prod_actors(po.get('produced_by'))
    yprim = yparts.get('Primary Artist', set()) | ytop
    yall = yprim | set().union(*yparts.values()) if yparts else yprim
    pall = ptop | (set().union(*pparts.values()) if pparts else set())
    c['ycba-creators'] += len(yprim); c['palomar-creators'] += len(ptop)
    c['creators-agree'] += len(yprim & ptop)
    if yprim != ptop: ex['creators'].append((tid, sorted(yprim), sorted(ptop), dict((k, sorted(v)) for k,v in pparts.items())))
    c['ycba-actors'] += len(yall); c['palomar-actors'] += len(pall); c['actors-agree'] += len(yall & pall)
    # subjects
    yrep, yab = subjects(y['visual'], 'represents'), subjects(y['visual'], 'about')
    prep, pab = subjects(pv, 'represents'), subjects(pv, 'about')
    c['ycba-represents'] += len(yrep); c['ycba-about'] += len(yab)
    c['palomar-represents'] += len(prep); c['palomar-about'] += len(pab)
    c['subjects-ycba-represents-palomar-about'] += len(yrep & pab)
    c['subjects-same-label-any'] += len((yrep|yab) & (prep|pab))
    # custody
    yc = {norm(g.get('_label','')) for g in yo.get('current_owner', []) + yo.get('current_custodian', [])}
    pc = {norm(g.get('_label','')) for g in po.get('current_owner', []) + po.get('current_custodian', [])}
    c['custody-ycba'] += bool(yc); c['custody-palomar'] += bool(pc); c['custody-agree'] += bool(yc & pc)
    # types and dimensions, materials
    yt = {t.get('id') for t in yo.get('classified_as', [])}; pt = {t.get('id') for t in po.get('classified_as', [])}
    c['types-ycba'] += len(yt); c['types-palomar'] += len(pt); c['types-agree'] += len(yt & pt)
    c['dims-ycba'] += len(yo.get('dimension', [])); c['dims-palomar'] += len(po.get('dimension', []))
    c['made_of-ycba'] += len(yo.get('made_of', [])); c['made_of-palomar'] += len(po.get('made_of', []))
    rd = open(os.path.join(d,'palomar-rules.edn')).read()
    m = dict(re.findall(r':([a-z-]+) (\d+)', rd)); c['rules-depicted'] += int(m['depicted']); c['rules-about'] += int(m['about']); c['hand-P62'] += int(m['hand-depicts'])
for k, v in c.items(): print(f'{k:45s} {v}')
print('\nexamples of creator disagreement:')
for e in ex['creators'][:8]: print(' ', e)
