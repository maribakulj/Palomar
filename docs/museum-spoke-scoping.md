# Museum spoke — down-projection LRMoo → CIDOC-CRM (scoping)

The "output universality" piece: serialise the LRMoo WEMI graph as CIDOC-CRM (and
later Linked Art) so museum / Linked-Art tooling can consume what the hub produces.
This document scopes it — what must be decided, with the trade-offs — **before**
any code, grounded in the actual LRMoo v1.0 ontology, not in ADR 0013's slogan.

## The honest finding (verified against `LRMoo_v1.0.owl`)

ADR 0013 says down-projection is "free — walk up the F-class hierarchy". Checking
the ontology, that is **true for classes, lossy for relations and for two of the
classes**:

| LRMoo (what we mint) | CRM super (OWL `subClassOf` / `subPropertyOf`) | note |
|---|---|---|
| `F1_Work`          | `E89_Propositional_Object` | clean |
| `F2_Expression`    | `E73_Information_Object`   | **shares E73 with F3** |
| `F3_Manifestation` | `E73_Information_Object`   | **shares E73 with F2** |
| `F5_Item`          | `E24_Physical_Human-Made_Thing` | clean |
| `R3_is_realised_in`| `P130_shows_features_of`   | generic ("shows features of") |
| `R4_embodies`      | `P165_incorporates`        | generic |
| `R7_exemplifies`   | `P128_carries`             | generic |
| `R33_has_string`   | `P3_has_note`              | generic; in LRMoo R33's domain is F12_Nomen |

Two real losses if we *replace* LRMoo with plain CRM:

1. **Class collapse.** `F2_Expression` and `F3_Manifestation` both rdf:type
   `E73_Information_Object` — a pure-CRM consumer can no longer tell Expression from
   Manifestation by type. (Work=E89, Item=E24 stay distinct.)
2. **Relation generalisation.** The WEMI relations become generic CRM properties
   (P130/P165/P128). They stay mutually distinct *for our subset*, but lose their
   WEMI-specific meaning, and several other LRMoo relations also map to P130 — so a
   richer projection would collapse them.

The corollary drives the whole design: **down-projection is free only if it is
*additive*** — keep the LRMoo F/R triples and *add* the CRM super-type / super-
property triples. A pure-CRM *replacement* is lossy and must be reported as such.

## Decisions to settle

### D-M1 — Target vocabulary: plain CRM, Linked Art, or both?
- **(a) Plain CIDOC-CRM** — the direct, verified super-class/-property walk.
- **(b) Linked Art** — the de-facto museum interchange, but a CRM *application
  profile*: object-centric, JSON-LD, **no first-class WEMI** → flattens Work /
  Expression / Manifestation harder than plain CRM, and needs its own patterns.
- **(c) Both.**
- **Recommendation: (a) plain CRM first.** It is mechanical and faithful (the OWL
  gives the exact axioms); Linked Art is a lossier profile best layered on top once
  the CRM mapping and its loss accounting exist.

### D-M2 — Additive (lossless) vs replacement (pure CRM, lossy)?
- **(a) Additive** — emit each entity's LRMoo type **and** its CRM super-type, each
  WEMI relation **and** its CRM super-property. Lossless; an LRMoo consumer reads
  F/R, a CRM consumer reads E/P. This is the genuinely "free" reading.
- **(b) Replacement** — emit only E/P. Lossy: E73 collapse (D-M finding 1) and
  relation generalisation (finding 2) → reported as **export-edge loss**
  (`:under-specified` for the E73 collapse, `:coerced` for the generalised
  relations), reusing the loss model.
- **Recommendation: (a) as the default ("CRM-compatible LRMoo"), (b) behind the
  same seam** for consumers that want pure CRM and accept the (now *reported*) loss.

### D-M3 — Mapping source: hardcode the subset or read the OWL?
- **(a) Hardcode** the WEMI subset (4 classes + 3 relations + R33), verified once
  against the OWL (done, table above).
- **(b) Read `LRMoo_v1.0.owl`** at load and follow `subClassOf` / `subPropertyOf`.
- **Recommendation: (a) hardcode.** It mirrors how `lrmoo.clj` already hardcodes the
  WEMI vocabulary (local names = spec identifiers); the shipped subset is tiny.
  Reading the full OWL is over-engineering until the projected vocabulary grows.

### D-M4 — Where it lives.
- A new `palomar.plugins.lrmoo.crm` namespace: the F→E / R→P maps plus a CRM-aware
  exporter that *transforms the existing* `export/triples` seq (no parallel graph
  walk). The N-Triples renderer is reused unchanged.
- **Recommendation: yes** — a CRM module in the LRMoo plugin, over the existing
  triple seq.

### D-M5 — Serialisation.
- The additive CRM triples need nothing new — **N-Triples** carries them. Turtle /
  JSON-LD (and the Linked-Art JSON-LD framing) are the natural companions to D-M1(b)
  and ADR 0013's still-unbuilt serialisations.
- **Recommendation: additive CRM in N-Triples first**; Turtle / JSON-LD with Linked
  Art later.

## Proposed first slice (pending the decisions above)

`palomar.plugins.lrmoo.crm`:
- the verified `f-class→e-class` and `r-property→p-property` maps (WEMI subset);
- `crm-augment` over `export/triples`: for each LRMoo type triple add the CRM
  super-type triple, for each WEMI relation add the CRM super-property triple
  (**additive, lossless**);
- `->ntriples` of the augmented seq.

Tests: a hand-built WEMI record exports with **both** `F1_Work` and
`E89_Propositional_Object`, **both** `R4_embodies` and `P165_incorporates`, etc.;
the Bovary vertical exports CRM-compatible triples end-to-end.

Second slice (optional): the pure-CRM replacement exporter, reporting the E73
collapse and relation generalisation as export-edge loss.

This also lets us **correct ADR 0013** honestly: down-projection is "free" only in
the additive sense; replacement is lossy, and now measured.
