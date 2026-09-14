# UNIMARC fixtures — BnF SRU (MARCXChange)

Real UNIMARC bibliographic records from the BnF Catalogue général SRU API
(`recordSchema=unimarcXchange`), the BnF's public **diffusion** format. Used by
`palomar.plugins.unimarc-test` — the third MARC-family spoke (MARC21 / INTERMARC /
UNIMARC).

| File | Query | Records |
|------|-------|--------:|
| `bnf-sru-hugo-unimarc.xml`     | `bib.anywhere all "victor hugo"` | 50 |
| `bnf-sru-verne-unimarc.xml`    | `bib.author all "jules verne"`   | 50 |
| `bnf-sru-flaubert-unimarc.xml` | `bib.author all "flaubert"`      | 47 |

Licence: **Licence Ouverte / Etalab** (BnF open data). Verified real UNIMARC
(`<mxc:record format="UNIMARC">`, tags `200` title, `700 $a/$b/$o` author,
`210 $d` date, `010` ISBN, `101` language), no BOM, no mojibake.

UNIMARC tag semantics differ from MARC21/INTERMARC; the importer maps `200 $a` →
title, `7xx $a` → agent, `210 $d` → date, `010/011 $a` → identifier, `856 $u` →
digital object, `3xx $a` → note. Note: UNIMARC carries the agent ISNI in `7xx $o`
(not `$1`); lifting it to a first-class identified agent (as INTERMARC does from
`100 $1`) is a later slice.
