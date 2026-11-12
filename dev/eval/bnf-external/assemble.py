import re, sys
from xml.parsers import expat
kind, srcdir, idfile, dst = sys.argv[1:5]
ids = sorted(set(open(idfile).read().split()))
recs = []; missing = []
for i in ids:
    s = open(f'{srcdir}/{i}.xml', encoding='utf-8').read()
    p = expat.ParserCreate(); p.Parse(s.encode('utf-8'), True)
    bl = re.findall(r'<srw:record>.*?</srw:record>', s, re.S)
    if bl: recs += bl
    else: missing.append(i)
what = {'aut': 'BnF person authority records, cited by the records\' person headings ($3),',
        'pseudo': 'BnF pseudonym authority records, the targets of the cited authorities\' 331 links,'}[kind]
head = f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- {what} (INTERMARC Xchange), fetched 2026-11-11 from the BnF SRU API
     (https://catalogue.bnf.fr/api/SRU, recordSchema=intermarcxchange), one query per record:
     aut.recordid any "<id>". Each <srw:record> below is copied verbatim from its own response;
     the responses are concatenated here, sorted by id, under one searchRetrieveResponse wrapper
     written for this fixture (lot O5). Ids not served (0 records): see README.
     Source : Bibliotheque nationale de France. Licence Ouverte / Etalab 2.0. -->
<srw:searchRetrieveResponse xmlns:srw="http://www.loc.gov/zing/srw/" xmlns="http://catalogue.bnf.fr/namespaces/InterXMarc" xmlns:ixm="http://catalogue.bnf.fr/namespaces/InterXMarc" xmlns:mn="http://catalogue.bnf.fr/namespaces/motsnotices" xmlns:sd="http://www.loc.gov/zing/srw/diagnostic/">
<srw:version>1.2</srw:version>
<srw:numberOfRecords>{len(recs)}</srw:numberOfRecords>
<srw:records>
'''
out = head + '\n'.join(recs) + '\n</srw:records>\n</srw:searchRetrieveResponse>\n'
p = expat.ParserCreate(); p.Parse(out.encode('utf-8'), True)
open(dst, 'w', encoding='utf-8').write(out)
print(dst, 'records', len(recs), 'missing', len(missing))
open(dst + '.missing', 'w').write('\n'.join(missing))
