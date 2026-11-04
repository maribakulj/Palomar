import json, sys, time, urllib.request, os, random
out = sys.argv[1]; n = int(sys.argv[2])
ctx = None
def get(url):
    for i in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as r: return r.read()
        except Exception as e:
            time.sleep(2*(i+1)); err = e
    raise err
page = json.load(open(os.path.join(out, 'p1.json')))
objs = [i['object']['id'] for i in page['orderedItems'] if i['object'].get('type') == 'HumanMadeObject']
random.seed(8); random.shuffle(objs)
got = 0; os.makedirs(os.path.join(out, 'pairs'), exist_ok=True)
for u in objs:
    if got >= n: break
    la = json.loads(get(u))
    tms = [i['content'] for i in la.get('identified_by', []) if any(c.get('_label') == 'System-Assigned Number' for c in i.get('classified_as', []))]
    if not tms: continue
    lido = get("https://harvester-bl.britishart.yale.edu/oaicatmuseum/OAIHandler?verb=GetRecord&metadataPrefix=lido&identifier=oai:tms.ycba.yale.edu:" + tms[0])
    if b'<lido:lido' not in lido and b'lido:lido' not in lido: continue
    vis = [json.loads(get(v['id'])) for v in la.get('shows', []) if v.get('id')]
    d = os.path.join(out, 'pairs', tms[0]); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'lido.xml'), 'wb').write(lido)
    json.dump({'object': la, 'visual': vis}, open(os.path.join(d, 'la.json'), 'w'))
    got += 1; time.sleep(0.3)
print(got, "pairs")
