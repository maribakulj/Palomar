import json, glob, os, sys, re
sys.path.insert(0, os.path.dirname(__file__))
import compare as C
root, name = sys.argv[1], sys.argv[2]
if os.path.exists(os.path.join(root, 'people.json')): C.PEOPLE.update(json.load(open(os.path.join(root, 'people.json'))))
for d in sorted(glob.glob(os.path.join(root, 'pairs', '*'))):
    yo = json.load(open(os.path.join(d, 'la.json')))['object']
    if not os.path.exists(os.path.join(d, name)): continue
    po, _ = C.load_palomar(os.path.join(d, name))
    ypb, ppb = yo.get('produced_by') or {}, (po or {}).get('produced_by') or {}
    ymk = C.agents(ypb, 'carried_out_by', 'Primary Artist') or ypb.get('carried_out_by', [])
    pmk = ppb.get('carried_out_by', [])
    lido = open(os.path.join(d, 'lido.xml'), encoding='utf-8').read()
    quals = sorted(set(q for q in re.findall(r'<lido:attributionQualifierActor>([^<]+)<', lido)))
    lost = [y.get('_label') for y in ymk if not any(C.keys_ycba(y) & C.keys_palomar(p) for p in pmk)]
    extra = [p.get('_label') for p in pmk if not any(C.keys_ycba(y) & C.keys_palomar(p) for y in ymk)]
    if lost or extra: print(os.path.basename(d), 'lost:', lost, 'extra:', extra, 'qualifiers:', quals)
