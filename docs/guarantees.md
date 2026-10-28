# What Palomar guarantees — and what it does not

*State of 2026-10-26, after the post-audit corrections (C1–C5). No release is
tagged yet; this file is the reference until one is, and each release should
carry its own copy.*

Every guarantee below is a behaviour a test pins. If the test is removed or
fails, the guarantee is withdrawn. Anything not listed is not promised.
`palomar.guarantees-test` checks that every test named here exists and that
the formats below are exactly those the code registers.

## Formats

Source formats: dc, eac-cpf, ead, edn, iiif, intermarc, intermarc-ng, marc21, mods, oai, unimarc

Target formats: crm, crm-only, dc, edn, iiif, jsonld, linked-art, marc21, mods, nquads, ntriples, prov, trig, turtle

`edn` is the pivot itself (ADR 0034); `oai` an OAI-PMH response read through
the spoke of its metadata format (ADR 0037). The EAD spoke is experimental
(ADR 0044, 0045).

## Guaranteed

| Guarantee | Pinned by |
|-----------|-----------|
| For the source spokes and every decodable target (N-Triples, CRM, CRM-only, JSON-LD, Linked Art, DC, MARC21, MODS, IIIF, PROV-O, N-Quads), every string value of every record is either present in the output or named by a loss diagnostic. No silent loss on the fixture corpus. | `palomar.fidelity-test/every-conversion-accounts-for-every-value` |
| An exporter that omits a value without reporting it is caught. | `palomar.fidelity-test/a-silent-omission-is-caught` |
| For the source spokes and the N-Triples, CRM, CRM-only and N-Quads targets, every relation the RDF view expresses (a WEMI link, a CRM or SKOS reference) is written — by its predicate or, in pure CRM, its super-property — or named by a loss. | `palomar.fidelity-test/every-relation-survives-in-the-rdf-outputs` |
| An export that drops a relation while keeping every string is caught. | `palomar.fidelity-test/a-dropped-relation-is-caught` |
| In the N-Triples family a value counts as preserved only on its own node: the right text on the wrong resource is caught. | `palomar.fidelity-test/the-right-text-on-the-wrong-node-is-caught` |
| Minted ids are injective in their parts: no two distinct tuples share a key (ADR 0040). | `palomar.model-entity-test/tuple-keys-are-injective`, `palomar.rules-test/mint-keys-are-tuples-not-joined-strings` |
| Distinct OAI identifiers give distinct record ids, and the id gives its identifier back. | `palomar.plugins.oai-test/record-ids-are-injective`, `palomar.plugins.oai-test/an-oai-identifier-makes-a-readable-record-id` |
| An alignment accepted in one record never identifies another record's homonym; a link scoped to no record is refused (ADR 0041). | `palomar.reconcile-test/an-accepted-alignment-does-not-identify-a-homonym` |
| Every agent a record names takes its own accepted link, keeps its own label, and a link a record does not take is listed with its reason (ADR 0046). | `palomar.reconcile-test/every-creator-of-a-record-is-reconciled`, `palomar.reconcile-test/a-record-already-identified-in-part-takes-the-rest` |
| Two creator-less records with one title are two Expressions; two records' same place or publisher name are two nodes. | `palomar.plugins.lrmoo.project-test/a-title-without-a-creator-identifies-nothing`, `palomar.plugins.events-test/a-place-name-is-not-a-place-across-records` |
| EAD and EAC-CPF (1.x and 2.0) selectors are absolute, start at the root element read, and find each value in the node they name: each name entry, origination, digital object and date by its rank. | `palomar.evidence-test/every-archival-xpath-finds-the-value-it-attests` |
| In PROV-O, an OAI-PMH record's selectors point into its metadata document, named by its digest and derived from the harvested OAI record and the response that carried it. | `palomar.plugins.oai-test/provenance-runs-from-the-response-to-the-xpath` |
| An OAI-PMH response's deleted records reach `convert`'s result. | `palomar.plugins.oai-test/each-record-goes-through-the-spoke-of-its-format` |
| A manifest names the code that ran: a changed source file changes its digest. | `palomar.manifest-test/the-manifest-names-the-code-that-ran` |
| A publisher or contributor never keys a Work; only a creator (or an agent of unstated role) does. | `palomar.plugins.lrmoo.project-test/a-publisher-alone-does-not-key-a-work` |
| An original and its translation are one Work and two Expressions. | `palomar.plugins.lrmoo.project-test/an-original-and-its-translation-are-one-work-two-expressions` |
| A Manifestation is exported to Linked Art as a text (`LinguisticObject`), only an F5 Item as a physical object; one document per Manifestation and per Item. | `palomar.plugins.lrmoo.linked-art-test/every-manifestation-and-item-gets-its-own-document` |
| Linked Art output for the MARC21 sample validates against the official `text.json` schema with zero errors. | `palomar.eval.linked-art-conformance-test/our-marc-output-validates-strictly` |
| A CRM super-class alone (`E89`, `E24`, `E73`) is never downcast to a WEMI entity; it is reported. | `palomar.plugins.lrmoo.crm-import-test/external-crm-super-classes-are-not-downcast` |
| Entity titles are written to RDF through an E41 Appellation (`P1` → `P190`), never as R33 or P190 on the entity itself, and read back. | `palomar.plugins.lrmoo.crm-import-test/a-title-round-trips-through-its-appellation` |
| External N-Triples are parsed by the RDF 1.1 grammar; a syntax error is located, never guessed. | `palomar.plugins.lrmoo.ntriples-test/syntax-errors-are-located-never-guessed` |
| Rejected, superseded and retracted assertions feed no rule and reach no output as a fact; the PROV-O record keeps them, reified, with their status. | `palomar.rules-test/record-triples-leaves-out-assertions-no-longer-in-force`, `palomar.convert-test/a-rejected-claim-is-never-exported`, `palomar.convert-test/the-prov-record-keeps-the-history` |
| No RDF output holds an invalid IRI: the writers refuse one, keyword ids are percent-encoded. | `palomar.plugins.lrmoo.export-test/no-invalid-iri-is-ever-written` |
| The LRMoo, CRM, pure-CRM and PROV-O outputs of every spoke fixture respect the domains and ranges of CIDOC CRM 7.1.3, LRMoo 1.0 and PROV-O. | `palomar.ontology-test/every-rdf-output-respects-its-vocabularies` |
| Two rules deriving one fact keep both justifications. | `palomar.runtime-test/alternative-justifications-are-recorded-once` |
| One fact is one assertion whatever status it was derived under, and a rule re-deriving a rejected claim does not bring it back. | `palomar.runtime-test/the-same-fact-is-one-assertion-whatever-its-status` |
| An assertion has a content-derived id that survives curation; equal values hash alike whatever their construction. | `palomar.model-identity-test/an-assertion-keeps-its-id-through-curation`, `palomar.model-identity-test/equal-values-have-one-canonical-form` |
| Every curation decision is kept on the record (verdict, statuses, reason, curator, time) and written to PROV-O as an activity that used, and may invalidate, the statement. | `palomar.curate-test/decisions-travel-with-the-record`, `palomar.plugins.lrmoo.prov-test/a-curation-decision-is-a-prov-activity` |
| The N-Quads and TriG datasets keep facts, proposals, history and provenance in separate named graphs: an uncurated inference is never in the facts graph, a rejected claim only in the history graph. | `palomar.plugins.lrmoo.dataset-test/an-uncurated-inference-is-a-proposal-not-a-fact`, `palomar.plugins.lrmoo.dataset-test/a-rejected-claim-moves-to-the-history-graph` |
| A target that states facts (every target but PROV-O, N-Quads, TriG and EDN) holds no proposed claim unless `--with-proposals` asks; the report counts what it withheld. | `palomar.convert-test/a-fact-export-states-no-uncurated-inference`, `palomar.cli-test/convert-states-facts-only-unless-asked` |
| Two runs of the same conversion write manifests that differ only in their times; a changed mapping changes its digest. | `palomar.manifest-test/the-same-conversion-reproduces-its-manifest`, `palomar.manifest-test/a-changed-mapping-is-named` |
| Every MARC, MODS and Dublin Core value records an XPath that finds it in the source document; the evidence survives normalisation and projection and is written to PROV-O as a Web Annotation. | `palomar.evidence-test/every-xpath-finds-the-value-it-attests`, `palomar.evidence-test/evidence-survives-normalisation-and-projection` |
| A reconciliation proposal is a proposed SKOS mapping claim, curated like any claim; only an accepted exact match to an authority id joins records on one agent; no `owl:sameAs` is ever written. | `palomar.reconcile-test/a-proposal-is-a-proposed-skos-mapping-not-a-merge` |
| A IIIF manifest missing a Presentation 3.0 MUST is not emitted, and the reason is reported. | `palomar.plugins.iiif.export-test/an-invalid-manifest-is-refused-with-a-diagnostic` |
| MARC field occurrences keep their subfields together, in order, with their indicators; the leader is kept. | `palomar.plugins.marc21-test/each-field-occurrence-keeps-its-subfields-together-and-in-order` |
| Distinct source identifiers do not share a record id (barring a 40-bit digest collision when slugging changed the identifier). | `palomar.plugins.record-id-test/identifiers-that-slug-alike-stay-distinct` |
| MARC flat dumps and SRU pages stream in bounded memory, and a streamed record equals the batch one. | `palomar.convert-stream-test/an-sru-page-streams-like-a-flat-dump` (memory: `docs/eval/scale.md`) |
| `convert --stream` never destroys an existing `--out` before the new one is in place. | `palomar.cli-test/convert-stream-replaces-existing-out-only-on-success` |

## Not guaranteed

- **Fidelity is checked on the fixture corpus.** In the N-Triples family a
  value is checked on its own node; in the other formats by value, so a value
  that also occurs elsewhere in the output counts as present. The audit can
  miss a loss, never invent one. Turtle output is not decoded; relations are
  checked only for the N-Triples family. A loss on another predicate of the
  same subject can stand for a value it does not concern.
- **Semantic correctness beyond the tests.** The mappings are checked against
  the published standards they cite (LRMoo 1.0/1.1.1, Linked Art, IIIF 3.0, the
  LoC crosswalks) where a test says so, not exhaustively.
- **Precision of Work clustering on a whole catalogue.** The evaluations
  measure it on joinable subsets (`docs/eval/bibr-work-clustering.md`,
  `docs/eval/entity-resolution.md`).
- **Archival and museum domains.** EAD units project to RiC-O (ADR 0045),
  checked against RiC-O 1.1 on the fixtures only: the spoke stays
  experimental until validated on a real corpus. There is no LIDO spoke; the
  only CRM events are publication and production (ADR 0036).
- **Institutional validation of INTERMARC-NG.** Its spoke is tested on
  synthetic data only.
- **Entity id collisions.** Ids carry a 64-bit hash; the policy and its limits
  are in `palomar.model/mint-entity-id`.
- **Batch memory.** `convert` holds the corpus and its output in memory; only
  `convert --stream` (MARC family) is bounded.
