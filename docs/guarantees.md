# What Palomar guarantees — and what it does not

*State of 2026-10-25, after the interoperability audit follow-up (audit S42: "a
versioned manifest of the guarantees actually delivered"). No release is tagged
yet; this file is the reference until one is, and each release should carry its
own copy.*

Every guarantee below is a behaviour a test pins. If the test is removed or
fails, the guarantee is withdrawn. Anything not listed is not promised.

## Guaranteed

| Guarantee | Pinned by |
|-----------|-----------|
| For the seven source spokes and every decodable target (N-Triples, CRM, CRM-only, JSON-LD, Linked Art, DC, MARC21, MODS, IIIF, PROV-O), every string value of every record is either present in the output or named by a loss diagnostic. No silent loss on the fixture corpus. | `palomar.fidelity-test/every-conversion-accounts-for-every-value` |
| An exporter that omits a value without reporting it is caught. | `palomar.fidelity-test/a-silent-omission-is-caught` |
| A publisher or contributor never keys a Work; only a creator (or an agent of unstated role) does. | `palomar.plugins.lrmoo.project-test/a-publisher-alone-does-not-key-a-work` |
| An original and its translation are one Work and two Expressions. | `palomar.plugins.lrmoo.project-test/an-original-and-its-translation-are-one-work-two-expressions` |
| A Manifestation is exported to Linked Art as a text (`LinguisticObject`), only an F5 Item as a physical object; one document per Manifestation and per Item. | `palomar.plugins.lrmoo.linked-art-test/every-manifestation-and-item-gets-its-own-document` |
| Linked Art output for the MARC21 sample validates against the official `text.json` schema with zero errors. | `palomar.eval.linked-art-conformance-test/our-marc-output-validates-strictly` |
| A CRM super-class alone (`E89`, `E24`, `E73`) is never downcast to a WEMI entity; it is reported. | `palomar.plugins.lrmoo.crm-import-test/external-crm-super-classes-are-not-downcast` |
| Entity titles are written to RDF through an E41 Appellation (`P1` → `P190`), never as R33 or P190 on the entity itself, and read back. | `palomar.plugins.lrmoo.crm-import-test/a-title-round-trips-through-its-appellation` |
| External N-Triples are parsed by the RDF 1.1 grammar; a syntax error is located, never guessed. | `palomar.plugins.lrmoo.ntriples-test/syntax-errors-are-located-never-guessed` |
| Rejected, superseded and retracted assertions feed no rule and reach no output. | `palomar.rules-test/record-triples-leaves-out-assertions-no-longer-in-force`, `palomar.convert-test/a-rejected-claim-is-never-exported` |
| Two rules deriving one fact keep both justifications. | `palomar.runtime-test/alternative-justifications-are-recorded-once` |
| A IIIF manifest missing a Presentation 3.0 MUST is not emitted, and the reason is reported. | `palomar.plugins.iiif.export-test/an-invalid-manifest-is-refused-with-a-diagnostic` |
| MARC field occurrences keep their subfields together, in order, with their indicators; the leader is kept. | `palomar.plugins.marc21-test/each-field-occurrence-keeps-its-subfields-together-and-in-order` |
| Distinct source identifiers do not share a record id (barring a 40-bit digest collision when slugging changed the identifier). | `palomar.plugins.record-id-test/identifiers-that-slug-alike-stay-distinct` |
| MARC flat dumps and SRU pages stream in bounded memory, and a streamed record equals the batch one. | `palomar.convert-stream-test/an-sru-page-streams-like-a-flat-dump` (memory: `docs/eval/scale.md`) |
| `convert --stream` never destroys an existing `--out` before the new one is in place. | `palomar.cli-test/convert-stream-replaces-existing-out-only-on-success` |

## Not guaranteed

- **Fidelity is checked by value, on the fixture corpus.** A value that also
  occurs elsewhere in an output counts as present; the audit can miss a loss,
  never invent one. Turtle output is not decoded. A loss on another predicate of
  the same subject can stand for a value it does not concern.
- **Semantic correctness beyond the tests.** The mappings are checked against
  the published standards they cite (LRMoo 1.0/1.1.1, Linked Art, IIIF 3.0, the
  LoC crosswalks) where a test says so, not exhaustively.
- **Precision of Work clustering on a whole catalogue.** The evaluations
  measure it on joinable subsets (`docs/eval/bibr-work-clustering.md`,
  `docs/eval/entity-resolution.md`).
- **Archival and museum domains.** There is no EAD, EAC-CPF, RiC-O or LIDO
  spoke, and no CIDOC CRM event model (production, acquisition, exhibition…).
- **Institutional validation of INTERMARC-NG.** Its spoke is tested on
  synthetic data only.
- **Entity id collisions.** Ids carry a 64-bit hash; the policy and its limits
  are in `palomar.model/mint-entity-id`.
- **Batch memory.** `convert` holds the corpus and its output in memory; only
  `convert --stream` (MARC family) is bounded.
