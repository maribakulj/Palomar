# Independent reader of agent occurrences, straight from the source XML
# (no Palomar code): writes golden-mentions.edn for palomar.mentions-test.
# Run from the repository root: python3 -I test/fixtures/mentions/golden_reader.py > test/fixtures/mentions/golden-mentions.edn
import sys, re, xml.etree.ElementTree as ET

def ln(t): return t.split('}')[-1]
def kids(e, name): return [c for c in e if ln(c.tag) == name]
def txt(e): return (e.text or '').strip() if e is not None else ''

P, G, F = 'person', 'group', 'family'
def im_specs():
    s = {}
    for t, r in [('100','creator'),('101','performer'),('700','contributor'),('701','performer'),('702','collaborator'),
                 ('720','publisher'),('721','distributor'),('722','producer'),('725','producer'),('726','producer'),('727','manufacturer')]:
        s[t] = dict(kind='pf2', name=['a','m'], dates='d', type='agent')
    s['703'] = dict(kind='pf2', name=['a','m'], dates='d', type='provenance')
    for t in ['110','111','710','711','712','730','731','732','735','736','737','723']:
        s[t] = dict(kind=G, name=['a'], dates=None, type='agent')
    return s
def um_specs():
    s = {}
    for t in ['700','701','702']: s[t] = dict(kind=P, name=['a','b'], dates='f', type='agent')
    for t in ['710','711','712']: s[t] = dict(kind=G, name=['a'], dates=None, type='agent')
    for t in ['720','721','722']: s[t] = dict(kind=F, name=['a'], dates='f', type='agent')
    s['703'] = dict(kind=P, name=['a','b'], dates='f', type='provenance')
    s['713'] = dict(kind=G, name=['a'], dates=None, type='provenance')
    s['723'] = dict(kind=F, name=['a'], dates='f', type='provenance')
    return s
def m21_specs():
    s = {}
    for t in ['100','700']: s[t] = dict(kind='pf1', name=['a'], dates='d', type='agent')
    for t in ['110','710','111','711']: s[t] = dict(kind=G, name=['a'], dates=None, type='agent')
    return s
SPECS = {'intermarc': im_specs(), 'unimarc': um_specs(), 'marc21': m21_specs()}

def edn(v):
    if v is None: return 'nil'
    if isinstance(v, bool): return 'true' if v else 'false'
    if isinstance(v, int): return str(v)
    if isinstance(v, str) and v.startswith(':'): return v
    if isinstance(v, str): return '"' + v.replace('\\','\\\\').replace('"','\\"') + '"'
    if isinstance(v, list): return '[' + ' '.join(edn(x) for x in v) + ']'
    if isinstance(v, dict): return '{' + ' '.join(edn(k) + ' ' + edn(x) for k, x in v.items()) + '}'

def marc(fmt, path, picks):
    root = ET.parse(path).getroot()
    recs = [e for e in root.iter() if ln(e.tag) == 'record' and (fmt == 'marc21' or e.get('id'))]
    out = []
    for i in picks:
        r = recs[i]; seen = {}; ms = []
        for f in r:
            if ln(f.tag) not in ('datafield', 'controlfield'): continue
            tag = f.get('tag'); n = seen.get(tag, 0); seen[tag] = n + 1
            sp = SPECS[fmt].get(tag)
            if not sp or ln(f.tag) != 'datafield': continue
            sfs = [(s.get('code'), (s.text or '').strip()) for s in kids(f, 'subfield')]
            first = lambda c: next((v for k, v in sfs if k == c and v), None)
            parts = [first(c) for c in sp['name'] if first(c)]
            if not parts: continue
            kind = sp['kind']
            if kind == 'pf2': kind = F if (f.get('ind2') or '').strip() == '5' else P
            if kind == 'pf1': kind = F if (f.get('ind1') or '').strip() == '3' else P
            rels = [v for k, v in sfs if k == '4' and v]
            typ = sp['type']
            if fmt == 'intermarc' and typ == 'agent' and rels and re.fullmatch(r'4\d\d\d', rels[0]) and '4010' <= rels[0] <= '4990':
                typ = 'provenance'
            m = {':field': tag, ':occurrence': n, ':type': ':' + typ, ':name': ', '.join(parts), ':kind': ':' + kind,
                 ':dates': first(sp['dates']) if sp['dates'] else None, ':relators': rels}
            ms.append(m)
        out.append({':file': path, ':record': i, ':mentions': ms})
    return out

YEAR = re.compile(r'(?<!\d)([1-9]\d{2,3})(?!\d)')
def lido(path, picks):
    root = ET.parse(path).getroot()
    recs = [e for e in root.iter() if ln(e.tag) == 'lido']
    out = []
    for i in picks:
        r = recs[i]; ms = []
        wraps = [e for e in r.iter() if ln(e.tag) == 'eventWrap']
        for w in wraps:
            for si, es in enumerate(kids(w, 'eventSet'), 1):
                for ei_, ev in enumerate(kids(es, 'event'), 1):
                    key = f'{si}.{ei_}'
                    for ai, ea in enumerate(kids(ev, 'eventActor'), 1):
                        for ri, air in enumerate(kids(ea, 'actorInRole'), 1):
                            a = next(iter(kids(air, 'actor')), None)
                            if a is None: continue
                            names = [txt(v) for ns in kids(a, 'nameActorSet') for v in kids(ns, 'appellationValue') if txt(v)]
                            if not names: continue
                            t = next((v for k, v in a.attrib.items() if ln(k) == 'type'), '') or ''
                            t = t.strip()
                            kind = None
                            if re.match(r'(?i)^person', t): kind = ':person'
                            elif re.search(r'(?i)famil', t): kind = ':family'
                            elif re.search(r'(?i)corporat|institution|organi[sz]ation|company|firm|group', t): kind = ':group'
                            roles = kids(air, 'roleActor')
                            # a role's concept id as an IRI: its text when it is one, an AAT code under
                            # lido:source="AAT" as the Getty's IRI; the preferred one first
                            def iri(c):
                                v = txt(c); src = next((x for k, x in c.attrib.items() if ln(k) == 'source'), '').strip().lower()
                                if re.match(r'^[a-z][a-z0-9+.-]*:', v): return v
                                if src == 'aat' and re.fullmatch(r'\d+', v): return 'http://vocab.getty.edu/aat/' + v
                                return None
                            ids = [c for ro in roles for c in kids(ro, 'conceptID')]
                            pref = [c for c in ids if next((x for k, x in c.attrib.items() if ln(k) == 'pref'), '') == 'preferred']
                            rel = next((iri(c) for c in pref + ids if iri(c)), None)
                            vd = next(iter(kids(a, 'vitalDatesActor')), None)
                            b = txt(next(iter(kids(vd, 'earliestDate')), None)) if vd is not None else ''
                            d = txt(next(iter(kids(vd, 'latestDate')), None)) if vd is not None else ''
                            dated = bool(YEAR.search(b) or YEAR.search(d))
                            dates = f"{b or '....'}-{d or '....'}" if dated and kind in (':person', None) else None
                            ms.append({':event': key, ':position': f'{ai}.{ri}', ':type': ':agent', ':name': names[0], ':kind': kind,
                                       ':dates': dates, ':relators': [rel] if rel else []})
        out.append({':file': path, ':record': i, ':mentions': ms})
    return out

base = 'test/fixtures/documentary/'
im = base + 'intermarc/sru/intermarcXchange/'
entries = []
entries += marc('intermarc', im + 'bnf-sru-alexandre-dumas-50.xml', [13, 17])
entries += marc('intermarc', im + 'bib-music-recordings-start1-max30.xml', [11, 29])
entries += marc('intermarc', im + 'bib-julien-gracq-start1-max30.xml', [1, 9, 18])
entries += marc('unimarc', base + 'unimarc/sru/bnf-sru-hugo-unimarc.xml', [2, 7, 8])
entries += marc('marc21', base + 'marc21/marcxml/loc_collection.xml', [0, 1])
entries += lido(base + 'lido/ycba-oai-lido.xml', [0, 1, 2])
print(';; GENERATED by an independent Python reader of the source XML (not Palomar), lot N1 review;')
print(';; spot-checked by hand. Each record: its agent and provenance occurrences, in document order.')
print('[')
for e in entries: print(' ' + edn(e))
print(']')
