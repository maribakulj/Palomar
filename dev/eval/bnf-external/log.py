import re, sys
S = sys.argv[1]; dst = sys.argv[2]
def date(h):
    return re.search(r'^Date: (.*?)\r?$', open(h).read(), re.M).group(1)
def num(x):
    return int(re.search(r'<srw:numberOfRecords>(\d+)', open(x, encoding='utf-8').read()).group(1))
def recs(x):
    return open(x, encoding='utf-8').read().count('<srw:record>')
def q(s): return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
out = [';; The SRU requests of the external test (lot O5), in the order made, with the Date header',
       ';; of each response (the server\'s clock). Written from the saved response headers.',
       '{:queries', ' [']
for n in ['voltaire', 'musset', 'christine de pizan', 'mardrus', 'rabelais']:
    f = n.replace(' ', '-')
    x = f'{S}/bib/{f}.xml'
    k = num(x) >= 100
    out.append(f'  {{:query {q(n)} :file {q("records/bnf-sru-"+f+"-100.xml" if k else "")} :http-date {q(date(f"{S}/bib/{f}.headers"))} :number-of-records {num(x)} :records {recs(x)} :kept? {"true" if k else "false"}}}')
out.append(' ]')
for key, d, idf in [(':authorities', 'aut', 'ids.txt'), (':pseudonyms', 'pseudo', 'pseudo-ids.txt')]:
    out.append(f' {key}'); out.append(' [')
    for i in sorted(set(open(f'{S}/../scripts-o5/{idf}').read().split())):
        out.append(f'  {{:id {q(i)} :http-date {q(date(f"{S}/{d}/{i}.headers"))} :number-of-records {num(f"{S}/{d}/{i}.xml")}}}')
    out.append(' ]')
out[-1] = ' ]}'
open(dst, 'w').write('\n'.join(out) + '\n')
