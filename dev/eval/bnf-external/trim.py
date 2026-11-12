import re, sys, xml.dom.minidom as _m
from xml.parsers import expat
keep = lambda t: t[0] == '1' or t[0] == '7' or t in ('245', '260')
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
p = expat.ParserCreate(); p.Parse(s.encode('utf-8'), True)  # well-formed or raises
pat = re.compile(r'\s*<mxc:datafield tag="(\d{3})"[^>]*>.*?</mxc:datafield>', re.S)
out = pat.sub(lambda m: m.group(0) if keep(m.group(1)) else '', s)
p = expat.ParserCreate(); p.Parse(out.encode('utf-8'), True)
open(dst, 'w', encoding='utf-8').write(out)
tags = sorted(set(re.findall(r'<mxc:datafield tag="(\d{3})"', out)))
print(dst, len(s), '->', len(out), 'records', out.count('<srw:record>'), 'tags', tags)
